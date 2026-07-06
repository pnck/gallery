package io.github.pnck.gallery.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Downstream incremental sync (T-303, PRD §7.2, §4.3). Skeleton.
 *
 * Contract when implemented:
 *  - fetchChanges(deltaToken) per provider (Drive Changes API / Graph delta)
 *  - upserted with no local match → insert as CLOUD_ONLY
 *  - deletedCloudIds → reconcile per PRD §4.3 (MVP: delete row)
 *  - persist the new delta token into sync_keys
 *  - schedule: periodic (~6h) + foreground wake-up
 */
class SyncDownWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // T-303: lands with M4.
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "sync_down_changes"
    }
}
