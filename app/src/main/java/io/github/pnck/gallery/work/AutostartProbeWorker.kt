package io.github.pnck.gallery.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.pnck.gallery.data.settings.AppSettingsStore

/**
 * Session-driven probe for "can the system run our background work at all?" —
 * the only way to detect MIUI's AutoStartManager killing jobs (no query API).
 *
 * No clocks, no invented windows. The experiment is anchored to the SESSION
 * boundary: a zero-delay probe job goes out every time the app moves to
 * background; by the next session it has either been delivered (capability
 * proven, timestamped) or it hasn't (the system held it through an entire
 * background interval — blocked). Real background work (PeriodicSyncWorker)
 * stamps the same completion — every genuine delivery is itself proof, the
 * probe job is only the fallback for idle periods.
 */
@HiltWorker
class AutostartProbeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: AppSettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A probe that lands while the app is VISIBLE proves nothing (any job
        // runs then); only background delivery is evidence.
        if (!appVisible) {
            settings.noteAutostartProbeCompleted()
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "autostart_probe"

        /** Not an accusation window — the floor below JobScheduler's physically
         *  plausible delivery time, so rapid foreground/background bouncing
         *  can't false-positive. */
        const val MIN_DELIVERY_SLACK_MS = 15 * 60_000L

        /** Set by GalleryApp's activity callbacks. */
        @Volatile
        var appVisible: Boolean = false

        /**
         * Fire a fresh zero-delay probe (REPLACE: each background interval is a
         * new experiment — a system that killed the last job gets retried, and
         * a healthy system delivers within seconds). The settings timestamp
         * only advances when the previous experiment concluded
         * (AppSettingsStore.noteAutostartProbeScheduled), so a blocked system's
         * outstanding probe is never quietly renewed.
         */
        suspend fun fire(workManager: WorkManager, settings: AppSettingsStore) {
            val fresh = settings.noteAutostartProbeScheduled()
            if (fresh) {
                workManager.enqueueUniqueWork(
                    UNIQUE_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<AutostartProbeWorker>().build(),
                )
            }
        }
    }
}
