package io.github.pnck.gallery.data.sync

import android.content.ContentResolver
import android.net.Uri
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.ICloudStorageProvider

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

    suspend fun processPending(onItemProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Outcome {
        val pending = photoDao.getPendingUploads()
        if (pending.isEmpty()) return Outcome.Done(0, 0)

        var uploaded = 0
        var failed = 0
        pending.forEachIndexed { index, photo ->
            val localUri = photo.localUri ?: run { failed++; return@forEachIndexed }
            val uri = Uri.parse(localUri)
            val mime = resolver.getType(uri) ?: "image/jpeg"

            when (val result = provider.uploadFile(uri, mime) { /* per-file % unused for now */ }) {
                is ApiResult.Success -> {
                    photoDao.markAsSynced(photo.id, result.data.id, result.data.provider.name)
                    uploaded++
                    onItemProgress(index + 1, pending.size)
                }
                is ApiResult.Error ->
                    if (result.retryable) return Outcome.Retry(uploaded)
                    else failed++ // permanent failure: stays PENDING_UPLOAD, next batch retries
            }
        }
        return Outcome.Done(uploaded, failed)
    }
}
