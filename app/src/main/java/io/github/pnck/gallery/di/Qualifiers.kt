package io.github.pnck.gallery.di

import javax.inject.Qualifier

/**
 * The bare OkHttpClient for device-flow token calls — rides the tunnel but has no
 * GoogleAuthInterceptor (token requests must not get a Bearer, and would re-enter
 * refresh; see AppModule).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AuthClient

/**
 * The bulk-transfer OkHttpClient for resumable upload chunks — HTTP/1.1 on its
 * own connection pool so parallel uploads get real parallel tunnel connections
 * (H2 would coalesce them onto one) and bulk traffic can't starve interactive
 * requests (SharedHttpClient.buildUploadClient).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UploadClient
