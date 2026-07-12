package io.github.pnck.gallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.pnck.gallery.data.sync.DownstreamSyncProcessor
import io.github.pnck.gallery.provider.AuthManager

/**
 * Cloud → Room downstream sync step of the pipeline (T-303/T-402). Pulls the
 * cloud listing / delta so photos from other devices appear as CLOUD_ONLY.
 * No authorized account → nothing to pull.
 */
@HiltWorker
class DownstreamSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: DownstreamSyncProcessor,
    private val authManager: AuthManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!authManager.isAuthorized()) return Result.success()
        return when (processor.sync()) {
            is DownstreamSyncProcessor.Outcome.Done -> Result.success()
            is DownstreamSyncProcessor.Outcome.Retry -> Result.retry()
        }
    }
}
