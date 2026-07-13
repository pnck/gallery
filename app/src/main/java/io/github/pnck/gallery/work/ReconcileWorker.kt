package io.github.pnck.gallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.pnck.gallery.data.sync.ReconcileProcessor
import io.github.pnck.gallery.provider.AuthManager

/**
 * Reconcile-from-truth pass (the reliability core): rebuilds the sync-state
 * classification from the full cloud listing + full local scan, self-healing any
 * drift (phantom rows, stale duplicates). Manual "Rebuild sync state" and a periodic
 * background run both go through here. Needs an account — cloud truth is required.
 */
@HiltWorker
class ReconcileWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reconcile: ReconcileProcessor,
    private val authManager: AuthManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!authManager.isAuthorized()) return Result.success() // nothing to reconcile against
        return when (reconcile.reconcile()) {
            is ReconcileProcessor.Outcome.Done -> Result.success()
            is ReconcileProcessor.Outcome.Retry -> Result.retry()
        }
    }
}
