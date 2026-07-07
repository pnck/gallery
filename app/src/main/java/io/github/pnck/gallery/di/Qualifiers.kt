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
