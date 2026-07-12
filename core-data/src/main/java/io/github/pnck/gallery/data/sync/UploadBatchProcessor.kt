package io.github.pnck.gallery.data.sync

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.ContentHash
import io.github.pnck.gallery.provider.ICloudStorageProvider

private const val TAG = "gallery-sync"

/**
 * Upload loop shared by workers (T-301 core, PRD §7.1). Framework-free: the
 * @HiltWorker wrappers in :app own WorkManager/Result semantics; this class owns
 * the state machine transition PENDING_UPLOAD → SYNCED.
 */
class UploadBatchProcessor(
    private val photoDao: PhotoDao,
    private val provider: ICloudStorageProvider,
    private val resolver: ContentResolver,
) {

    sealed interface Outcome {
        /** All pending items processed (some may have failed permanently). */
        data class Done(val uploaded: Int, val failed: Int) : Outcome

        /** Hit a retryable error (429/5xx/IO) — ask WorkManager for backoff retry. */
        data class Retry(val uploadedSoFar: Int) : Outcome
    }

    /** Live per-item progress for the backup banner (Google-Photos style). */
    data class Progress(val done: Int, val total: Int, val currentUri: String?, val pct: Int)

    /**
     * @param targetIds when non-null, only these photo ids are uploaded (multi-select
     *   sync); null uploads every PENDING_UPLOAD row (the full background sweep).
     */
    suspend fun processPending(
        targetIds: List<String>? = null,
        onProgress: (Progress) -> Unit = {},
    ): Outcome {
        val pending = if (targetIds.isNullOrEmpty()) {
            photoDao.getPendingUploads()
        } else {
            photoDao.getPendingByIds(targetIds)
        }
        if (pending.isEmpty()) return Outcome.Done(0, 0)
        Log.i(TAG, "upload: ${pending.size} pending (targeted=${!targetIds.isNullOrEmpty()})")

        var uploaded = 0
        var failed = 0
        pending.forEachIndexed { index, photo ->
            val localUri = photo.localUri ?: run {
                Log.w(TAG, "upload: skip ${photo.id} — no localUri")
                failed++
                return@forEachIndexed
            }
            val uri = Uri.parse(localUri)
            val mime = resolver.getType(uri) ?: "image/jpeg"
            onProgress(Progress(done = index, total = pending.size, currentUri = localUri, pct = 0))

            when (
                val result = provider.uploadFile(uri, mime) { pct ->
                    onProgress(Progress(index, pending.size, localUri, pct))
                }
            ) {
                is ApiResult.Success -> {
                    photoDao.markAsSynced(photo.id, result.data.id, result.data.provider.name)
                    // Persist the content hash so local/cloud identity is recoverable
                    // even if the state machine later breaks (PRD §3.5).
                    (result.data.contentHash as? ContentHash.Md5)?.let {
                        photoDao.setContentHash(photo.id, "MD5", it.value)
                    }
                    uploaded++
                    Log.i(TAG, "upload: OK ${photo.id} -> ${result.data.id}")
                    onProgress(Progress(index + 1, pending.size, localUri, 100))
                }
                is ApiResult.Error -> {
                    Log.w(TAG, "upload: FAILED ${photo.id} code=${result.code} retryable=${result.retryable} — ${result.message}")
                    if (result.retryable) return Outcome.Retry(uploaded)
                    else failed++ // permanent failure: stays PENDING_UPLOAD, next batch retries
                }
            }
        }
        Log.i(TAG, "upload: done uploaded=$uploaded failed=$failed")
        return Outcome.Done(uploaded, failed)
    }
}
