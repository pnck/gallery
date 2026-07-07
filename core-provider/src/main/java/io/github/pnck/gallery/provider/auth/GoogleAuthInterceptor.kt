package io.github.pnck.gallery.provider.auth

import io.github.pnck.gallery.provider.AuthManager
import io.github.pnck.gallery.provider.AuthNotAuthorizedException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Bearer injection on the shared OkHttpClient (PRD §8.3, provider split):
 *
 * - Google hosts (googleapis.com / googleusercontent.com — API + thumbnails)
 *   get `Authorization: Bearer`, refreshed transparently by the AuthManager.
 * - Everything else passes through untouched. OneDrive thumbnail URLs are
 *   pre-authorized and MUST NOT receive an auth header (adding one breaks them).
 *
 * Unauthorized state: the request proceeds without a header and the API answers
 * 401, which providers surface as a non-retryable ApiResult.Error.
 */
class GoogleAuthInterceptor(
    private val authManager: AuthManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        val isGoogleHost = host.endsWith("googleapis.com") || host.endsWith("googleusercontent.com")
        if (!isGoogleHost || request.header("Authorization") != null) {
            return chain.proceed(request)
        }
        val token = try {
            // Interceptors run on OkHttp's dispatcher threads; blocking here is
            // the sanctioned bridge for token refresh (performActionWithFreshTokens).
            runBlocking { authManager.getValidAccessToken() }
        } catch (_: AuthNotAuthorizedException) {
            null
        } catch (_: Exception) {
            null
        }
        return if (token == null) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
        }
    }
}
