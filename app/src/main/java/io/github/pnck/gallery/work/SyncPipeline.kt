package io.github.pnck.gallery.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The scan → upload chain (M2 happy path). Unique so repeated triggers
 * (permission grant, pull-to-sync, ContentObserver later) coalesce.
 */
object SyncPipeline {
    const val UNIQUE_NAME = "sync_pipeline"
    const val TARGETED_NAME = "sync_targeted"
    const val PERIODIC_NAME = "periodic_sync"
    const val RECONCILE_NAME = "sync_reconcile"

    /**
     * Background incremental keep-up while the app is closed (T-304). Idempotent —
     * KEEP means an already-scheduled job survives restarts. Register once at start.
     */
    fun schedulePeriodic(workManager: WorkManager) {
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Kick the scan → downstream → upload chain. [force] uses REPLACE so an explicit
     * user action ("Back up now") restarts the chain even when a previous one is stuck
     * retrying (e.g. it was blocked on a wedged tunnel); the default KEEP coalesces the
     * automatic triggers (ContentObserver, permission grant) so they don't pile up.
     */
    fun enqueue(workManager: WorkManager, force: Boolean = false) {
        val scan = OneTimeWorkRequestBuilder<ScanWorker>().build()
        val downstream = OneTimeWorkRequestBuilder<DownstreamSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        val policy = if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        // scan (local) → downstream (pull cloud) → upload (push local).
        workManager
            .beginUniqueWork(UNIQUE_NAME, policy, scan)
            .then(downstream)
            .then(uploadRequest())
            .enqueue()
    }

    /**
     * Multi-select "sync now" for specific already-scanned local photos (PRD §9.1).
     * Upload-only (no scan) and REPLACE so it isn't swallowed by an in-flight full
     * sweep; the worker filters to rows still PENDING_UPLOAD.
     */
    fun enqueueTargeted(workManager: WorkManager, photoIds: List<String>) {
        if (photoIds.isEmpty()) return
        val input = Data.Builder()
            .putStringArray(UploadWorker.KEY_TARGET_IDS, photoIds.toTypedArray())
            .build()
        workManager.enqueueUniqueWork(
            TARGETED_NAME,
            ExistingWorkPolicy.REPLACE,
            uploadRequest(input),
        )
    }

    /**
     * "Rebuild sync state": the reconcile-from-truth pass (full cloud + local diff,
     * prune drift). REPLACE so a fresh request supersedes a stalled one.
     */
    fun enqueueReconcile(workManager: WorkManager) {
        val request = OneTimeWorkRequestBuilder<ReconcileWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(RECONCILE_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun uploadRequest(input: Data = Data.EMPTY) =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
}
