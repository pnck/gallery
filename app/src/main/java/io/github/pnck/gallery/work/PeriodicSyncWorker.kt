package io.github.pnck.gallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.data.sync.MediaReconciler
import io.github.pnck.gallery.data.sync.ReconcileProcessor
import io.github.pnck.gallery.data.sync.UploadBatchProcessor
import io.github.pnck.gallery.provider.AuthManager
import kotlinx.coroutines.flow.first

/**
 * Background keep-up (T-304). Scheduled ~every 30 min while the app is closed: a light
 * local scan, then — when signed in — a full reconcile-from-truth pass (which self-heals
 * any state drift, not just a delta pull) and an upload sweep. In-app scanning stays
 * aggressive via the timeline's ContentObserver; foreground "sync now" uses the faster
 * scan → delta → upload chain, while this background run is where drift is repaired.
 */
@HiltWorker
class PeriodicSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reconciler: MediaReconciler,
    private val reconcile: ReconcileProcessor,
    private val uploadProcessor: UploadBatchProcessor,
    private val authManager: AuthManager,
    private val settings: AppSettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Local scan needs no account; permission loss just yields nothing.
        runCatching { reconciler.reconcile() }
        if (!authManager.isAuthorized()) return Result.success()

        // Rebuild classification from cloud + local truth (self-heals phantoms/drift).
        val recon = reconcile.reconcile()
        // This IS the background context, so its outcome is the honest probe the
        // UI derives the degradation rows from: a blind local scan proves media
        // access is foreground-restricted (no permission API reports MIUI's
        // 仅前台允许); a successful pass clears it. Foreground chain runs never
        // touch this evidence (foreground scans work even when restricted).
        when (recon) {
            is ReconcileProcessor.Outcome.BlindScan -> settings.setBlindScanAt(System.currentTimeMillis())
            is ReconcileProcessor.Outcome.Done -> settings.clearBlindScan()
            else -> Unit
        }
        // Scan + reconcile still run while paused; only the upload sweep stops.
        val up = if (settings.backupPaused.first()) {
            UploadBatchProcessor.Outcome.Done(0, 0)
        } else {
            uploadProcessor.processPending()
        }
        val needsRetry = recon is ReconcileProcessor.Outcome.Retry ||
            up is UploadBatchProcessor.Outcome.Retry
        return if (needsRetry) Result.retry() else Result.success()
    }
}
