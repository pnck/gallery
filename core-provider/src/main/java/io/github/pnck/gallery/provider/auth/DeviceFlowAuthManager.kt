package io.github.pnck.gallery.provider.auth

import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.AuthManager
import io.github.pnck.gallery.provider.AuthNotAuthorizedException
import io.github.pnck.gallery.provider.DeviceAuthChallenge
import io.github.pnck.gallery.provider.OAuthConfig
import io.github.pnck.gallery.provider.ProviderType
import io.github.pnck.gallery.provider.safeApiCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Google sign-in via the OAuth Device Authorization Grant (ADR-0001).
 *
 * All traffic here is the shared OkHttpClient, so it rides the tunnel once the
 * transport is enabled — including this first login. Token exchange and refresh
 * are hand-rolled against the token endpoint (no AppAuth).
 */
class DeviceFlowAuthManager(
    override val providerType: ProviderType,
    private val clientId: String,
    private val clientSecret: String,
    private val scope: String,
    private val deviceCodeEndpoint: String,
    private val tokenEndpoint: String,
    private val api: DeviceAuthApiService,
    private val tokenStore: TokenStore,
    private val now: () -> Long = System::currentTimeMillis,
) : AuthManager {

    private val refreshMutex = Mutex()

    override suspend fun requestDeviceAuthorization(): ApiResult<DeviceAuthChallenge> {
        if (clientId.isBlank()) {
            return ApiResult.Error(-1, "OAuth client id not configured (GALLERY_GOOGLE_CLIENT_ID)", false)
        }
        return safeApiCall({ api.requestDeviceCode(deviceCodeEndpoint, clientId, scope) }) { body ->
            DeviceAuthChallenge(
                deviceCode = body.deviceCode,
                userCode = body.userCode,
                verificationUrl = body.verificationUrl ?: body.verificationUri.orEmpty(),
                expiresInSec = body.expiresIn,
                intervalSec = body.interval,
            )
        }
    }

    override suspend fun pollForToken(challenge: DeviceAuthChallenge): ApiResult<Unit> {
        val deadline = now() + challenge.expiresInSec * 1000L
        var intervalMs = challenge.intervalSec.coerceAtLeast(1) * 1000L

        while (now() < deadline) {
            delay(intervalMs)
            val response = try {
                api.exchangeDeviceCode(tokenEndpoint, clientId, clientSecret, challenge.deviceCode)
            } catch (e: Exception) {
                return ApiResult.Error(-1, e.message ?: "Network error during polling", retryable = true)
            }

            val body = response.body()
            if (response.isSuccessful && body?.accessToken != null && body.refreshToken != null) {
                persist(body.accessToken, body.refreshToken, body.expiresIn)
                return ApiResult.Success(Unit)
            }

            // Pending/slow-down come back as OAuth errors (often HTTP 428/400).
            when (body?.error ?: response.errorBody()?.let { parseError(it.string()) }) {
                "authorization_pending" -> Unit // keep polling
                "slow_down" -> intervalMs += 5_000
                "access_denied" -> return ApiResult.Error(-1, "Access denied by user", retryable = false)
                "expired_token" -> return ApiResult.Error(-1, "Device code expired", retryable = false)
                else -> return ApiResult.Error(
                    response.code(),
                    body?.errorDescription ?: "Token polling failed",
                    retryable = false,
                )
            }
        }
        return ApiResult.Error(-1, "Device code expired before approval", retryable = false)
    }

    override suspend fun getValidAccessToken(): String {
        val stored = tokenStore.read(providerType) ?: throw AuthNotAuthorizedException(providerType)
        if (now() < stored.accessExpiryEpochMs - EXPIRY_SKEW_MS) return stored.accessToken

        return refreshMutex.withLock {
            // Re-check: another coroutine may have refreshed while we waited.
            val fresh = tokenStore.read(providerType) ?: throw AuthNotAuthorizedException(providerType)
            if (now() < fresh.accessExpiryEpochMs - EXPIRY_SKEW_MS) return@withLock fresh.accessToken

            val response = api.refreshToken(tokenEndpoint, clientId, clientSecret, fresh.refreshToken)
            val body = response.body()
            if (response.isSuccessful && body?.accessToken != null) {
                // refresh_token may rotate; fall back to the existing one if absent.
                persist(body.accessToken, body.refreshToken ?: fresh.refreshToken, body.expiresIn)
                body.accessToken
            } else {
                throw AuthNotAuthorizedException(providerType)
            }
        }
    }

    override fun isAuthorized(): Boolean = tokenStore.read(providerType) != null

    override suspend fun signOut() = tokenStore.clear(providerType)

    private fun persist(accessToken: String, refreshToken: String, expiresInSec: Int?) {
        val expiry = now() + (expiresInSec ?: DEFAULT_EXPIRY_SEC) * 1000L
        tokenStore.write(providerType, StoredTokens(accessToken, expiry, refreshToken))
    }

    private fun parseError(raw: String): String? =
        Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.getOrNull(1)

    companion object {
        private const val EXPIRY_SKEW_MS = 60_000L
        private const val DEFAULT_EXPIRY_SEC = 3600

        fun google(
            clientId: String,
            clientSecret: String,
            api: DeviceAuthApiService,
            tokenStore: TokenStore,
        ): DeviceFlowAuthManager = DeviceFlowAuthManager(
            providerType = ProviderType.G_DRIVE,
            clientId = clientId,
            clientSecret = clientSecret,
            scope = OAuthConfig.Google.SCOPE,
            deviceCodeEndpoint = OAuthConfig.Google.DEVICE_CODE_ENDPOINT,
            tokenEndpoint = OAuthConfig.Google.TOKEN_ENDPOINT,
            api = api,
            tokenStore = tokenStore,
        )
    }
}
