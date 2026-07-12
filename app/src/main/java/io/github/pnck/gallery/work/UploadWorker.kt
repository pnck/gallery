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

        // Non-empty → multi-select targeted sync; absent → full background sweep.
        val targetIds = inputData.getStringArray(KEY_TARGET_IDS)?.toList()

        var lastKey = ""
        return when (
            val outcome = processor.processPending(targetIds) { p ->
                // Throttle: only push when the visible state actually changes.
                val key = "${p.done}/${p.total}@${p.pct}:${p.currentUri}"
                if (key != lastKey) {
                    lastKey = key
                    setProgressAsync(progressData(p))
                }
            }
        ) {
            is UploadBatchProcessor.Outcome.Done -> Result.success(
                Data.Builder()
                    .putInt(KEY_UPLOADED, outcome.uploaded)
                    .putInt(KEY_FAILED, outcome.failed)
                    .build(),
            )
            is UploadBatchProcessor.Outcome.Retry -> Result.retry()
        }
    }

    private fun progressData(p: UploadBatchProcessor.Progress): Data =
        Data.Builder()
            .putInt(KEY_PROGRESS_DONE, p.done)
            .putInt(KEY_PROGRESS_TOTAL, p.total)
            .putInt(KEY_CURRENT_PCT, p.pct)
            .putString(KEY_CURRENT_URI, p.currentUri)
            .build()

    companion object {
        const val KEY_UPLOADED = "uploaded"
        const val KEY_FAILED = "failed"
        const val KEY_PROGRESS_DONE = "progress_done"
        const val KEY_PROGRESS_TOTAL = "progress_total"
        const val KEY_CURRENT_PCT = "progress_current_pct"
        const val KEY_CURRENT_URI = "progress_current_uri"

        /** String[] of photo ids for a targeted (multi-select) upload. */
        const val KEY_TARGET_IDS = "target_ids"
    }
}
