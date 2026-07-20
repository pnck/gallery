package io.github.pnck.gallery.data.sync

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.PhotoEntity
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.ContentHash
import io.github.pnck.gallery.provider.ICloudStorageProvider
import io.github.pnck.gallery.provider.upload.UploadSessionStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

private const val TAG = "gallery-sync"

/** Files uploaded in parallel (each on its own HTTP/1.1 tunnel connection). */
private const val PARALLEL_UPLOADS = 2

/**
 * Upload loop shared by workers (T-301 core, PRD §7.1). Framework-free: the
 * @HiltWorker wrappers in :app own WorkManager/Result semantics; this class owns
 * the state machine transition PENDING_UPLOAD → SYNCED.
 *
 * Reliability contract (backup-first):
 *  - TRUE RESUMABLE: the provider resumes from the server-confirmed offset in
 *    [sessions]; a retry never restarts a file from byte 0.
 *  - NO HEAD-OF-LINE BLOCKING: a retryable failure marks the file and moves on;
 *    the batch only asks WorkManager for a retry at the END if anything
 *    retryable remains. Files past the attempt cap are excluded by the query
 *    until reconcile / explicit user sync resets their counter.
 *  - NO DOUBLE UPLOADS: each row is atomically claimed (attempt counter bump
 *    conditional on still-PENDING), so a targeted run racing the bulk sweep
 *    can't upload the same file twice. Batches themselves are serialized.
 */
class UploadBatchProcessor(
    private val photoDao: PhotoDao,
    private val provider: ICloudStorageProvider,
    private val resolver: ContentResolver,
    private val sessions: UploadSessionStore,
) {

    sealed interface Outcome {
        /** All pending items processed (some may have failed permanently). */
        data class Done(val uploaded: Int, val failed: Int) : Outcome

        /** Some files hit retryable errors (429/5xx/IO) — WorkManager backs off
         *  and retries; confirmed chunk progress is persisted either way. */
        data class Retry(val uploadedSoFar: Int) : Outcome
    }

    /** Live per-item progress for the backup banner (Google-Photos style). */
    data class Progress(val done: Int, val total: Int, val currentUri: String?, val pct: Int)

    /** One batch at a time process-wide (targeted vs bulk are both idempotent,
     *  but interleaving them doubles tunnel pressure for zero gain). */
    private val batchMutex = Mutex()

    /**
     * @param targetIds when non-null, only these photo ids are uploaded (multi-select
     *   sync); null uploads every PENDING_UPLOAD row (the full background sweep).
     *   A targeted run is an explicit user action, so it first resets the attempt
     *   counters of those ids — reviving files the sweep had given up on.
     */
    suspend fun processPending(
        targetIds: List<String>? = null,
        onProgress: (Progress) -> Unit = {},
    ): Outcome = batchMutex.withLock {
        if (!targetIds.isNullOrEmpty()) photoDao.resetUploadAttempts(targetIds)
        val pending = if (targetIds.isNullOrEmpty()) {
            photoDao.getPendingUploads()
        } else {
            photoDao.getPendingByIds(targetIds)
        }
        if (pending.isEmpty()) return Outcome.Done(0, 0)
        Log.i(TAG, "upload: ${pending.size} pending (targeted=${!targetIds.isNullOrEmpty()})")

        val uploaded = AtomicInteger()
        val failed = AtomicInteger()
        val retryable = AtomicInteger()
        val doneCount = AtomicInteger()
        val gate = Semaphore(PARALLEL_UPLOADS)

        coroutineScope {
            pending.map { photo ->
                async {
                    gate.withPermit {
                        when (val r = uploadOne(photo) { pct ->
                            onProgress(Progress(doneCount.get(), pending.size, photo.localUri, pct))
                        }) {
                            FileResult.OK -> uploaded.incrementAndGet()
                            FileResult.FAILED -> failed.incrementAndGet()
                            FileResult.RETRYABLE -> retryable.incrementAndGet()
                            FileResult.SKIPPED -> Unit // claimed elsewhere / gone
                        }
                        val done = doneCount.incrementAndGet()
                        onProgress(Progress(done, pending.size, photo.localUri, 100))
                    }
                }
            }.forEach { it.await() }
        }

        Log.i(TAG, "upload: done uploaded=$uploaded failed=$failed retryable=$retryable")
        return if (retryable.get() > 0) Outcome.Retry(uploaded.get()) else Outcome.Done(uploaded.get(), failed.get())
    }

    private enum class FileResult { OK, FAILED, RETRYABLE, SKIPPED }

    private suspend fun uploadOne(photo: PhotoEntity, onProgress: (Int) -> Unit): FileResult {
        // Atomic claim: 0 = the row left PENDING_UPLOAD (another worker claimed it,
        // or a state transition moved it) — never upload it twice.
        if (photoDao.claimUpload(photo.id, System.currentTimeMillis()) == 0) {
            Log.i(TAG, "upload: skip ${photo.id} — claimed elsewhere or no longer pending")
            return FileResult.SKIPPED
        }
        val localUri = photo.localUri ?: return FileResult.FAILED.also {
            Log.w(TAG, "upload: skip ${photo.id} — no localUri")
        }
        val uri = Uri.parse(localUri)

        // Media access can be partial/revoked (Android 13/14 "Selected photos"),
        // and the resumable protocol needs an lseek-capable fd (chunk bodies
        // position by FileChannel; an unseekable provider would otherwise burn
        // all retries in quadratic read-and-discard). Both are PERMANENT
        // per-file skips, not retryable network errors — fail fast and move on.
        val seekable = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.channel.position(0L) }
            } != null
        }.getOrDefault(false)
        if (!seekable) {
            Log.w(TAG, "upload: skip ${photo.id} — unreadable or unseekable $localUri")
            return FileResult.FAILED
        }

        // The resumable protocol needs the exact total for every Content-Range.
        // Unknown size → fail fast BEFORE any bytes go out (never stream with an
        // unknown length — Drive would 400/411 after the whole body).
        val totalBytes = photo.sizeBytes.takeIf { it > 0 } ?: sizeOf(uri) ?: run {
            Log.w(TAG, "upload: skip ${photo.id} — unknown file size")
            return FileResult.FAILED
        }
        val mime = resolver.getType(uri) ?: "image/jpeg"

        // Content identity: reuse the stored MD5; compute + persist it if missing
        // (hashes are lazy, computed here at upload time — invariant #3). The
        // provider verifies the final cloud object against it.
        val md5 = photo.contentHashValue.takeIf { photo.contentHashType == "MD5" && !it.isNullOrEmpty() }
            ?: computeMd5(uri)?.also { photoDao.setContentHash(photo.id, "MD5", it) }

        return when (
            val result = provider.uploadFile(photo.id, uri, mime, totalBytes, md5, sessions, onProgress)
        ) {
            is ApiResult.Success -> {
                photoDao.markAsSynced(photo.id, result.data.id, result.data.provider.name)
                // Persist the content hash so local/cloud identity is recoverable
                // even if the state machine later breaks (PRD §3.5).
                val hash = (result.data.contentHash as? ContentHash.Md5)?.value
                    ?: (provider.getFileMetadata(result.data.id) as? ApiResult.Success)
                        ?.data?.contentHash?.let { it as? ContentHash.Md5 }?.value
                if (hash != null) photoDao.setContentHash(photo.id, "MD5", hash)
                Log.i(TAG, "upload: OK ${photo.id} -> ${result.data.id}")
                FileResult.OK
            }
            is ApiResult.Error -> {
                Log.w(TAG, "upload: FAILED ${photo.id} code=${result.code} retryable=${result.retryable} — ${result.message}")
                if (result.retryable) FileResult.RETRYABLE else FileResult.FAILED
            }
        }
    }

    private fun sizeOf(uri: Uri): Long? =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0).takeIf { it > 0 } else null
        }

    private fun computeMd5(uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
