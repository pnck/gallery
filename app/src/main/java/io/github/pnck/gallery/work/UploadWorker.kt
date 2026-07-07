package io.github.pnck.gallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.pnck.gallery.data.sync.UploadBatchProcessor
import io.github.pnck.gallery.provider.AuthManager

/**
 * Silent background upload (T-301, PRD §7.1). Thin WorkManager shell around
 * :core-data's UploadBatchProcessor:
 *  - retryable errors (429/5xx/IO) → Result.retry() with exponential backoff
 *  - progress exposed via setProgress for the timeline indicator (PRD §9.1)
 *  - no authorized account → success (nothing to do until the user signs in)
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: UploadBatchProcessor,
    private val authManager: AuthManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!authManager.isAuthorized()) return Result.success()

        return when (val outcome = processor.processPending { done, total ->
            setProgressAsync(progressData(done, total))
        }) {
            is UploadBatchProcessor.Outcome.Done -> Result.success(
                Data.Builder()
                    .putInt(KEY_UPLOADED, outcome.uploaded)
                    .putInt(KEY_FAILED, outcome.failed)
                    .build(),
            )
            is UploadBatchProcessor.Outcome.Retry -> Result.retry()
        }
    }

    private fun progressData(done: Int, total: Int): Data =
        Data.Builder().putInt(KEY_PROGRESS_DONE, done).putInt(KEY_PROGRESS_TOTAL, total).build()

    companion object {
        const val KEY_UPLOADED = "uploaded"
        const val KEY_FAILED = "failed"
        const val KEY_PROGRESS_DONE = "progress_done"
        const val KEY_PROGRESS_TOTAL = "progress_total"
    }
}
