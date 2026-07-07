package io.github.pnck.gallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.pnck.gallery.data.sync.MediaReconciler

/**
 * MediaStore → Room reconciliation step of the sync pipeline (T-202/T-301).
 * Thin WorkManager shell; the logic lives in :core-data's MediaReconciler.
 */
@HiltWorker
class ScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val reconciler: MediaReconciler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val discovered = reconciler.reconcile()
        Result.success(workDataOf(KEY_DISCOVERED to discovered))
    } catch (e: SecurityException) {
        // Media permission revoked mid-run — nothing to retry until re-granted.
        Result.failure()
    }

    companion object {
        const val KEY_DISCOVERED = "discovered"
    }
}
