package io.github.pnck.gallery.di

import android.content.ContentResolver
import android.content.Context
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.squareup.moshi.Moshi
import io.github.pnck.gallery.ui.coil.ProviderUriFetcher
import io.github.pnck.gallery.ui.mydrive.DriveReadAccess
import io.github.pnck.gallery.ui.mydrive.DriveReadUrlFetcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.pnck.gallery.BuildConfig
import io.github.pnck.gallery.data.db.GalleryDatabase
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.SyncKeyDao
import io.github.pnck.gallery.data.db.UploadSessionDao
import io.github.pnck.gallery.data.repo.PhotoRepositoryImpl
import io.github.pnck.gallery.data.scanner.LocalMediaScanner
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.data.sync.DownstreamSyncProcessor
import io.github.pnck.gallery.data.sync.MediaReconciler
import io.github.pnck.gallery.data.sync.ReconcileProcessor
import io.github.pnck.gallery.data.sync.RoomUploadSessionStore
import io.github.pnck.gallery.data.sync.UploadBatchProcessor
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.network.SharedHttpClient
import io.github.pnck.gallery.network.transport.OutboundRouter
import io.github.pnck.gallery.transport.SystemVpnMonitor
import io.github.pnck.gallery.transport.TransportController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import io.github.pnck.gallery.provider.AuthManager
import io.github.pnck.gallery.provider.GoogleDriveProvider
import io.github.pnck.gallery.provider.ICloudStorageProvider
import io.github.pnck.gallery.provider.api.DriveApiService
import io.github.pnck.gallery.provider.auth.DeviceAuthApiService
import io.github.pnck.gallery.provider.auth.DeviceFlowAuthManager
import io.github.pnck.gallery.provider.auth.EncryptedTokenStore
import io.github.pnck.gallery.provider.auth.GoogleAuthInterceptor
import io.github.pnck.gallery.provider.auth.TokenStore
import io.github.pnck.gallery.provider.upload.UploadSessionStore
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
    fun provideUploadSessionDao(db: GalleryDatabase): UploadSessionDao = db.uploadSessionDao()

    @Provides
    @Singleton
    fun provideUploadSessionStore(dao: UploadSessionDao): UploadSessionStore =
        RoomUploadSessionStore(dao)

    @Provides
    @Singleton
    fun providePhotoRepository(
        @ApplicationContext context: Context,
        photoDao: PhotoDao,
        provider: ICloudStorageProvider,
        resolver: ContentResolver,
    ): PhotoRepository = PhotoRepositoryImpl(context, photoDao, provider, resolver)

    // ── Network (insertion layer + shared client, PRD §8) ─────────────────

    /**
     * The transport controller owns the (optional) active NetworkTransport and a
     * stable [OutboundRouter]. Off by default → its router returns null → NO_PROXY,
     * i.e. byte-for-byte "never integrated" (invariant #8). A settings action can
     * later call TransportController.connect without rebuilding the HTTP client.
     */
    @Provides
    @Singleton
    fun provideTransportController(@ApplicationContext context: Context): TransportController =
        TransportController(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            // While a system VPN covers us, the router yields to it (NO_PROXY) so the
            // userspace WG tunnel never fights the VPN for the same peer.
            systemVpnActive = SystemVpnMonitor(context).vpnActive,
        ).apply {
            // Every route rebind (manual connect/disconnect AND supervisor
            // auto-reconnect) must drop pooled connections — a pooled socket keeps
            // pointing at the dead tunnel's old loopback port otherwise.
            onRouteRebind = { SharedHttpClient.evictAll() }
        }

    @Provides
    @Singleton
    fun provideOutboundRouter(controller: TransportController): OutboundRouter = controller.router

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    /**
     * Bare client for the token endpoint (device-flow calls). Rides the tunnel
     * (router) but carries NO GoogleAuthInterceptor: the token/device-code
     * requests must not receive a Bearer header, and routing them through the
     * interceptor would re-enter getValidAccessToken during refresh (deadlock).
     */
    @AuthClient
    @Provides
    @Singleton
    fun provideAuthHttpClient(router: OutboundRouter): OkHttpClient =
        SharedHttpClient.build(router)

    /**
     * The shared app client (PRD §8.1): Retrofit for Drive and (later) Coil ride
     * this instance. Bearer injection for Google hosts happens here, once (PRD §8.3).
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(router: OutboundRouter, authManager: AuthManager): OkHttpClient =
        SharedHttpClient.build(router) {
            addInterceptor(GoogleAuthInterceptor(authManager))
        }

    /**
     * The bulk-transfer client for upload chunks: same Bearer injection, but
     * HTTP/1.1 + its own pool so parallel uploads use parallel tunnel connections.
     */
    @UploadClient
    @Provides
    @Singleton
    fun provideUploadHttpClient(router: OutboundRouter, authManager: AuthManager): OkHttpClient =
        SharedHttpClient.buildUploadClient(router) {
            addInterceptor(GoogleAuthInterceptor(authManager))
        }

    /**
     * The app-wide Coil loader (PRD §8.1, T-401). Rides the ONE shared client so
     * thumbnails honour the tunnel and Bearer injection; the ProviderUriFetcher
     * resolves `{provider}://{cloudId}` CLOUD_ONLY thumbnails. Installed as the
     * singleton loader by GalleryApp.
     */
    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        client: OkHttpClient,
        @AuthClient bareClient: OkHttpClient,
        provider: ICloudStorageProvider,
        photoDao: PhotoDao,
        driveReadAccess: DriveReadAccess,
    ): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }))
                add(ProviderUriFetcher.Factory(provider, photoDao, client))
                // "My Drive" images ride the separate drive.readonly token on the bare client.
                add(DriveReadUrlFetcher.Factory(driveReadAccess, bareClient))
            }
            .build()

    @Provides
    @Singleton
    fun provideDeviceAuthApiService(@AuthClient client: OkHttpClient, moshi: Moshi): DeviceAuthApiService =
        Retrofit.Builder()
            // Base URL is unused — DeviceAuthApiService calls absolute @Url endpoints.
            .baseUrl("https://oauth2.googleapis.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DeviceAuthApiService::class.java)

    @Provides
    @Singleton
    fun provideDriveApiService(client: OkHttpClient, moshi: Moshi): DriveApiService =
        Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DriveApiService::class.java)

    /** Drive API on the bulk-transfer client — upload init/chunks/status only. */
    @UploadClient
    @Provides
    @Singleton
    fun provideDriveUploadApiService(@UploadClient client: OkHttpClient, moshi: Moshi): DriveApiService =
        Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DriveApiService::class.java)

    // ── Auth & provider (the virtual backend, PRD §4/§5, ADR-0001) ─────────

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore = EncryptedTokenStore(context)

    @Provides
    @Singleton
    fun provideGoogleAuthManager(
        api: DeviceAuthApiService,
        tokenStore: TokenStore,
    ): AuthManager =
        DeviceFlowAuthManager.google(
            clientId = BuildConfig.GOOGLE_OAUTH_CLIENT_ID,
            clientSecret = BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET,
            api = api,
            tokenStore = tokenStore,
        )

    @Provides
    @Singleton
    fun provideAppSettingsStore(@ApplicationContext context: Context): AppSettingsStore =
        AppSettingsStore(context)

    @Provides
    @Singleton
    fun provideCloudStorageProvider(
        api: DriveApiService,
        @UploadClient uploadApi: DriveApiService,
        resolver: ContentResolver,
        settings: AppSettingsStore,
    ): ICloudStorageProvider =
        GoogleDriveProvider(api, uploadApi, resolver, folderName = { settings.remoteFolderName.first() })

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
        scanner: LocalMediaScanner,
        photoDao: PhotoDao,
        syncKeyDao: SyncKeyDao,
        settings: AppSettingsStore,
    ): MediaReconciler = MediaReconciler(scanner, photoDao, syncKeyDao, settings)

    @Provides
    @Singleton
    fun provideDownstreamSyncProcessor(
        provider: ICloudStorageProvider,
        photoDao: PhotoDao,
        syncKeyDao: SyncKeyDao,
    ): DownstreamSyncProcessor = DownstreamSyncProcessor(provider, photoDao, syncKeyDao)

    @Provides
    @Singleton
    fun provideReconcileProcessor(
        provider: ICloudStorageProvider,
        photoDao: PhotoDao,
        scanner: LocalMediaScanner,
        settings: AppSettingsStore,
        resolver: ContentResolver,
        @ApplicationContext context: Context,
    ): ReconcileProcessor = ReconcileProcessor(provider, photoDao, scanner, settings, resolver, context)

    @Provides
    @Singleton
    fun provideUploadBatchProcessor(
        photoDao: PhotoDao,
        provider: ICloudStorageProvider,
        resolver: ContentResolver,
        sessions: UploadSessionStore,
    ): UploadBatchProcessor = UploadBatchProcessor(photoDao, provider, resolver, sessions)

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
