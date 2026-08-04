package io.github.pnck.gallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
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
        return when (val outcome = reconcile.reconcile()) {
            // Surface the tallies in the output: the timeline reports them as a
            // snackbar, so "Rebuild" is never a silent no-op — the counts ARE the
            // diagnosis when the result surprises (e.g. synced=0 with a full cloud).
            is ReconcileProcessor.Outcome.Done -> Result.success(
                Data.Builder()
                    .putInt(KEY_SYNCED, outcome.synced)
                    .putInt(KEY_PENDING, outcome.pendingUpload)
                    .putInt(KEY_CLOUD_ONLY, outcome.cloudOnly)
                    .putInt(KEY_PRUNED, outcome.pruned)
                    .build(),
            )
            is ReconcileProcessor.Outcome.Retry -> Result.retry()
            // Preconditions unmet (no permission / first scan pending): succeed
            // quietly — retrying now would spin on the same preconditions.
            is ReconcileProcessor.Outcome.Skipped -> Result.success()
            // Blind local scan: succeed quietly too — only the PERIODIC worker
            // records this as degradation evidence (a foreground chain run says
            // nothing about background access).
            is ReconcileProcessor.Outcome.BlindScan -> Result.success()
        }
    }

    companion object {
        const val KEY_SYNCED = "synced"
        const val KEY_PENDING = "pending"
        const val KEY_CLOUD_ONLY = "cloudOnly"
        const val KEY_PRUNED = "pruned"
    }
}
