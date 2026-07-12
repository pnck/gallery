package io.github.pnck.gallery

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import io.github.pnck.gallery.work.SyncPipeline
import javax.inject.Inject

@HiltAndroidApp
class GalleryApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // The Hilt-built loader (shared OkHttpClient + provider:// fetcher, T-401).
    @Inject
    lateinit var imageLoader: ImageLoader

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
    }

    /** Make Coil's singleton (used by AsyncImage / ZoomableAsyncImage) our tunnel-aware loader. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader
}
