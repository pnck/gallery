package io.github.pnck.gallery

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.work.AutostartProbeWorker
import io.github.pnck.gallery.work.SyncPipeline
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class GalleryApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // The Hilt-built loader (shared OkHttpClient + provider:// fetcher, T-401).
    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var settings: AppSettingsStore

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // @HiltWorker factories require on-demand WorkManager init
    // (the default androidx.startup initializer is removed in the manifest).
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Register the background incremental keep-up (T-304); KEEP is idempotent.
        SyncPipeline.schedulePeriodic(WorkManager.getInstance(this))
        trackForegroundForProbe()
    }

    /**
     * The autostart experiment is anchored to session boundaries (no clocks):
     * every transition to background fires a fresh zero-delay probe; the next
     * session reads the verdict from the probe timestamps (see TimelineScreen).
     * Visibility is a plain activity-count flag — no lifecycle-process
     * dependency needed for a counter this simple.
     */
    private fun trackForegroundForProbe() {
        var started = 0
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                AutostartProbeWorker.appVisible = started > 0
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                AutostartProbeWorker.appVisible = started > 0
                if (started == 0) {
                    appScope.launch {
                        AutostartProbeWorker.fire(WorkManager.getInstance(this@GalleryApp), settings)
                    }
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Make Coil's singleton (used by AsyncImage / ZoomableAsyncImage) our tunnel-aware loader. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
