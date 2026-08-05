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
import java.util.concurrent.TimeUnit

/**
 * Active probe for "can the system run our background work at all?" — the ONLY
 * way to detect MIUI's AutoStartManager killing jobs, which has no query API.
 *
 * Why a probe and not a clock: staleness ("no sync in N hours") conflates
 * blocking with phone-off / nothing-to-do and either false-alarms or lags.
 * A probe is an EXPERIMENT: schedule a trivial no-constraint job; if the system
 * doesn't deliver it within the window, background execution is provably dead
 * right now. Media access already follows the same evidence-first rule (the
 * blind-scan guard); this is the autostart equivalent.
 *
 * The probe reschedules itself so exactly one is always outstanding. Runs while
 * the app is visible prove nothing (any job runs then) — they reschedule
 * without stamping.
 */
@HiltWorker
class AutostartProbeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: AppSettingsStore,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!appVisible) {
            settings.noteAutostartProbeCompleted()
        }
        schedule(WorkManager.getInstance(applicationContext), settings)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "autostart_probe"

        /** Time the system gets to deliver a constraint-free job… */
        const val PROBE_DELAY_MS = 2 * 3600_000L

        /** …plus doze/lateness slack before we call it blocked. */
        const val GRACE_MS = 3600_000L

        /** Set by GalleryApp's activity callbacks — a probe that fires while the
         *  app is VISIBLE proves nothing about background delivery. */
        @Volatile
        var appVisible: Boolean = false

        /**
         * Keep exactly one probe outstanding. KEEP never pushes an outstanding
         * probe's deadline back, and the settings timestamp is only advanced
         * when a NEW experiment is actually enqueued (see AppSettingsStore).
         */
        suspend fun schedule(workManager: WorkManager, settings: AppSettingsStore) {
            val fresh = settings.noteAutostartProbeScheduled()
            if (fresh) {
                val request = OneTimeWorkRequestBuilder<AutostartProbeWorker>()
                    .setInitialDelay(PROBE_DELAY_MS, TimeUnit.MILLISECONDS)
                    .build()
                workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            }
        }
    }
}
