package io.github.pnck.gallery.di

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.work.WorkManager
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.pnck.gallery.BuildConfig
import io.github.pnck.gallery.auth.OAuthCallbackActivity
import io.github.pnck.gallery.data.db.GalleryDatabase
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.SyncKeyDao
import io.github.pnck.gallery.data.repo.PhotoRepositoryImpl
import io.github.pnck.gallery.data.scanner.LocalMediaScanner
import io.github.pnck.gallery.data.sync.MediaReconciler
import io.github.pnck.gallery.data.sync.UploadBatchProcessor
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.network.SharedHttpClient
import io.github.pnck.gallery.network.transport.OutboundRouter
import io.github.pnck.gallery.provider.AuthManager
import io.github.pnck.gallery.provider.GoogleDriveProvider
import io.github.pnck.gallery.provider.ICloudStorageProvider
import io.github.pnck.gallery.provider.ProviderType
import io.github.pnck.gallery.provider.api.DriveApiService
import io.github.pnck.gallery.provider.auth.AppAuthManager
import io.github.pnck.gallery.provider.auth.GoogleAuthInterceptor
import javax.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * DI assembly lives in :app only; core modules stay framework-free (PRD §2.2).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ── Persistence ────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GalleryDatabase =
        GalleryDatabase.create(context)

    @Provides
    fun providePhotoDao(db: GalleryDatabase): PhotoDao = db.photoDao()

    @Provides
    fun provideSyncKeyDao(db: GalleryDatabase): SyncKeyDao = db.syncKeyDao()

    @Provides
    @Singleton
    fun providePhotoRepository(photoDao: PhotoDao): PhotoRepository =
        PhotoRepositoryImpl(photoDao)

    // ── Network (insertion layer + shared client, PRD §8) ─────────────────

    /**
     * Identity router = true direct connection. EPIC-5 swaps this binding for
     * the NetworkTransport implementation — nothing else in the graph changes.
     */
    @Provides
    @Singleton
    fun provideOutboundRouter(): OutboundRouter = OutboundRouter.IDENTITY

    /**
     * The single shared OkHttpClient (PRD §8.1): Retrofit, Coil and AppAuth's
     * ConnectionBuilder all ride this instance. Bearer injection for Google
     * hosts happens here, once, for every consumer (PRD §8.3).
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(router: OutboundRouter, authManager: AuthManager): OkHttpClient =
        SharedHttpClient.build(router) {
            addInterceptor(GoogleAuthInterceptor(authManager))
        }

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    @Provides
    @Singleton
    fun provideDriveApiService(client: OkHttpClient, moshi: Moshi): DriveApiService =
        Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DriveApiService::class.java)

    // ── Auth & provider (the virtual backend, PRD §4/§5) ───────────────────

    @Provides
    @Singleton
    fun provideGoogleAuthManager(
        @ApplicationContext context: Context,
        router: OutboundRouter,
    ): AuthManager =
        AppAuthManager(
            appContext = context,
            providerType = ProviderType.G_DRIVE,
            clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
            redirectUri = Uri.parse(BuildConfig.OAUTH_REDIRECT_URI),
            completionActivity = OAuthCallbackActivity::class.java,
            router = router,
        )

    @Provides
    @Singleton
    fun provideCloudStorageProvider(
        api: DriveApiService,
        authManager: AuthManager,
        resolver: ContentResolver,
    ): ICloudStorageProvider = GoogleDriveProvider(api, authManager, resolver)

    // ── Sync machinery (PRD §6/§7) ─────────────────────────────────────────

    @Provides
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    fun provideLocalMediaScanner(resolver: ContentResolver): LocalMediaScanner =
        LocalMediaScanner(resolver)

    @Provides
    @Singleton
    fun provideMediaReconciler(
        @ApplicationContext context: Context,
        scanner: LocalMediaScanner,
        photoDao: PhotoDao,
    ): MediaReconciler = MediaReconciler(context, scanner, photoDao)

    @Provides
    @Singleton
    fun provideUploadBatchProcessor(
        photoDao: PhotoDao,
        provider: ICloudStorageProvider,
        resolver: ContentResolver,
    ): UploadBatchProcessor = UploadBatchProcessor(photoDao, provider, resolver)

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
