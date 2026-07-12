package io.github.pnck.gallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.pnck.gallery.data.sync.DownstreamSyncProcessor
import io.github.pnck.gallery.data.sync.MediaReconciler
import io.github.pnck.gallery.data.sync.UploadBatchProcessor
import io.github.pnck.gallery.provider.AuthManager

/**
 * Background incremental keep-up (T-304). Scheduled ~every 30 min while the app is
 * closed: a light local scan plus, when signed in, a cloud delta pull and upload
 * sweep. In-app scanning stays aggressive via the timeline's ContentObserver.
 */
@HiltWorker
class PeriodicSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reconciler: MediaReconciler,
    private val downstream: DownstreamSyncProcessor,
    private val uploadProcessor: UploadBatchProcessor,
    private val authManager: AuthManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Local scan needs no account; permission loss just yields nothing.
        runCatching { reconciler.reconcile() }
        if (!authManager.isAuthorized()) return Result.success()

        val down = downstream.sync()
        val up = uploadProcessor.processPending()
        val needsRetry = down is DownstreamSyncProcessor.Outcome.Retry ||
            up is UploadBatchProcessor.Outcome.Retry
        return if (needsRetry) Result.retry() else Result.success()
    }
}
