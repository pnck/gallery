package io.github.pnck.gallery.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.pnck.gallery.data.db.GalleryDatabase
import io.github.pnck.gallery.data.db.PhotoDao
import io.github.pnck.gallery.data.db.SyncKeyDao
import io.github.pnck.gallery.data.repo.PhotoRepositoryImpl
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.network.SharedHttpClient
import io.github.pnck.gallery.network.transport.OutboundRouter
import javax.inject.Singleton
import okhttp3.OkHttpClient

/**
 * DI assembly lives in :app only; core modules stay framework-free (PRD §2.2).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

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

    /**
     * Insertion layer (Transport Design §3.0): the identity router means true
     * direct connection. EPIC-5 replaces this binding with the NetworkTransport
     * implementation — nothing else in the graph changes.
     */
    @Provides
    @Singleton
    fun provideOutboundRouter(): OutboundRouter = OutboundRouter.IDENTITY

    /**
     * The single shared OkHttpClient (PRD §8.1): Retrofit, Coil and AppAuth's
     * ConnectionBuilder must all reuse this instance.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(router: OutboundRouter): OkHttpClient =
        SharedHttpClient.build(router)
}
