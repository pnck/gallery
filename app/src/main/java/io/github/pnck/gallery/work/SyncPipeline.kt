package io.github.pnck.gallery.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The scan → upload chain (M2 happy path). Unique so repeated triggers
 * (permission grant, pull-to-sync, ContentObserver later) coalesce.
 */
object SyncPipeline {
    const val UNIQUE_NAME = "sync_pipeline"

    fun enqueue(workManager: WorkManager) {
        val scan = OneTimeWorkRequestBuilder<ScanWorker>().build()
        val upload = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager
            .beginUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, scan)
            .then(upload)
            .enqueue()
    }
}
