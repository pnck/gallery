package io.github.pnck.gallery.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.pnck.gallery.BuildConfig
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.domain.SyncFilter
import io.github.pnck.gallery.domain.TimelineQuery
import io.github.pnck.gallery.work.SyncPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug-build automation hooks (owner: drive app flows from adb for end-to-end
 * testing). Registered DYNAMICALLY from GalleryApp only when
 * [BuildConfig.DIAGNOSTICS_ENABLED] — i.e. debug/alpha builds; release builds
 * never register it and the manifest has no entry, so the attack surface in
 * stable builds is nil.
 *
 * Usage (adb):
 *   am broadcast -a io.github.pnck.gallery.DEBUG_SYNC_NOW
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
        if (!BuildConfig.DIAGNOSTICS_ENABLED) return // belt: registration is already gated
        val deps = EntryPointAccessors.fromApplication(context, Deps::class.java)
        val async = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SYNC -> {
                        SyncPipeline.enqueue(deps.workManager(), force = true)
                        Log.i(TAG, "sync chain enqueued (force)")
                    }
                    ACTION_SAVE_FIRST_CLOUD_ONLY -> {
                        val row = deps.repo()
                            .getTimeline(TimelineQuery(filter = SyncFilter.CLOUD_ONLY))
                            .first()
                            .firstOrNull()
                        if (row == null) {
                            Log.w(TAG, "no CLOUD_ONLY row to save")
                        } else {
                            Log.i(TAG, "saving cloud-only row ${row.id} (dateTaken=${row.dateTaken})…")
                            val saved = deps.repo().saveToDevice(row.id)
                            Log.i(TAG, "save result: ${saved?.uri} folder=${saved?.folder}")
                        }
                    }
                    ACTION_STATE -> {
                        val counts = deps.repo().observeSyncCounts().first()
                        Log.i(TAG, "counts: $counts")
                    }
                    else -> Log.w(TAG, "unknown action ${intent.action}")
                }
            } finally {
                async.finish()
            }
        }
    }

    companion object {
        const val TAG = "gallery-debug"
        const val ACTION_SYNC = "io.github.pnck.gallery.DEBUG_SYNC_NOW"
        const val ACTION_SAVE_FIRST_CLOUD_ONLY = "io.github.pnck.gallery.DEBUG_SAVE_FIRST_CLOUD_ONLY"
        const val ACTION_STATE = "io.github.pnck.gallery.DEBUG_STATE"
    }
}
