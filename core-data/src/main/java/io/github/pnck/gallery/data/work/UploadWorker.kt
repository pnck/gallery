package io.github.pnck.gallery.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Silent background upload (T-301, PRD §7.1). Skeleton.
 *
 * Contract when implemented:
 *  - iterate PhotoDao.getPendingUploads(), upload via ICloudStorageProvider
 *  - success → markAsSynced; retryable error (429/IO) → Result.retry() (exponential backoff)
 *  - large files use chunked sessions so each wake-up only sends remaining chunks
 *    (10-minute execution window, PRD §4.4)
 *  - constraints: NetworkType.CONNECTED (+ optional WiFi-only / charging-only)
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // T-301: DI wiring (Hilt) + upload loop land with M2.
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "upload_pending_photos"
    }
}
