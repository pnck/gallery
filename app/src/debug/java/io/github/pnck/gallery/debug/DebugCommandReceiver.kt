package io.github.pnck.gallery.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.domain.SyncFilter
import io.github.pnck.gallery.domain.TimelineQuery
import io.github.pnck.gallery.work.SyncPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug-build automation hooks (owner: drive full app flows from adb for
 * end-to-end round-trip tests). Lives in the DEBUG SOURCE SET — release builds
 * never compile this class (compile-time removal, not just a runtime gate),
 * and GalleryApp registers it via reflection.
 *
 * Round-trip driver (new photo → backup → cloud-only → restore):
 *   am broadcast -a io.github.pnck.gallery.DEBUG_SYNC_NOW
 *   am broadcast -a io.github.pnck.gallery.DEBUG_QUEUE_LATEST
 *   am broadcast -a io.github.pnck.gallery.DEBUG_FREE_FIRST_SYNCED
 *   am broadcast -a io.github.pnck.gallery.DEBUG_SAVE_FIRST_CLOUD_ONLY
 *   am broadcast -a io.github.pnck.gallery.DEBUG_STATE
 * Results are logged under tag gallery-debug.
 */
class DebugCommandReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun repo(): PhotoRepository
        fun workManager(): WorkManager
    }

    override fun onReceive(context: Context, intent: Intent) {
        val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)
        val repo = deps.repo()
        val async = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SYNC -> {
                        SyncPipeline.enqueue(deps.workManager(), force = true)
                        Log.i(TAG, "sync chain enqueued (force)")
                    }
                    ACTION_QUEUE_LATEST -> {
                        val row = repo.getTimeline(TimelineQuery(filter = SyncFilter.NOT_BACKED_UP))
                            .first().firstOrNull()
                        if (row == null) {
                            Log.w(TAG, "no NOT_BACKED_UP row to queue")
                        } else {
                            repo.includeForBackup(listOf(row.id))
                            SyncPipeline.enqueueTargeted(deps.workManager(), listOf(row.id))
                            Log.i(TAG, "queued+syncing ${row.id} (${row.renderUri})")
                        }
                    }
                    ACTION_FREE_FIRST_SYNCED -> {
                        val row = repo.getTimeline(TimelineQuery(filter = SyncFilter.BACKED_UP))
                            .first().firstOrNull { it.localUri != null }
                        if (row == null) {
                            Log.w(TAG, "no SYNCED row with a local copy to free")
                        } else {
                            repo.releaseLocalCopies(listOf(row.localUri!!))
                            Log.i(TAG, "released local copy of ${row.id} → CLOUD_ONLY (dateTaken=${row.dateTaken})")
                        }
                    }
                    ACTION_SAVE_FIRST_CLOUD_ONLY -> {
                        val row = repo.getTimeline(TimelineQuery(filter = SyncFilter.CLOUD_ONLY))
                            .first().firstOrNull()
                        if (row == null) {
                            Log.w(TAG, "no CLOUD_ONLY row to save")
                        } else {
                            Log.i(TAG, "saving cloud-only row ${row.id} (dateTaken=${row.dateTaken})…")
                            val saved = repo.saveToDevice(row.id)
                            Log.i(TAG, "save result: ${saved?.uri} folder=${saved?.folder}")
                        }
                    }
                    ACTION_STATE -> Log.i(TAG, "counts: ${repo.observeSyncCounts().first()}")
                    else -> Log.w(TAG, "unknown action ${intent.action}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "hook ${intent.action} failed", e)
            } finally {
                async.finish()
            }
        }
    }

    companion object {
        const val TAG = "gallery-debug"
        const val ACTION_SYNC = "io.github.pnck.gallery.DEBUG_SYNC_NOW"
        const val ACTION_QUEUE_LATEST = "io.github.pnck.gallery.DEBUG_QUEUE_LATEST"
        const val ACTION_FREE_FIRST_SYNCED = "io.github.pnck.gallery.DEBUG_FREE_FIRST_SYNCED"
        const val ACTION_SAVE_FIRST_CLOUD_ONLY = "io.github.pnck.gallery.DEBUG_SAVE_FIRST_CLOUD_ONLY"
        const val ACTION_STATE = "io.github.pnck.gallery.DEBUG_STATE"
    }
}
