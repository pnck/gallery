package io.github.pnck.gallery.provider.auth

import android.util.Log
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.AuthManager
import io.github.pnck.gallery.provider.AuthNotAuthorizedException
import io.github.pnck.gallery.provider.DeviceAuthChallenge
import io.github.pnck.gallery.provider.OAuthConfig
import io.github.pnck.gallery.provider.ProviderType
import io.github.pnck.gallery.provider.safeApiCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "gallery-auth"

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
    /** Elevated scope for the "My Drive" browser (drive.file + drive.readonly). */
    private val readScope: String,
    private val deviceCodeEndpoint: String,
    private val tokenEndpoint: String,
    private val api: DeviceAuthApiService,
    private val tokenStore: TokenStore,
    private val now: () -> Long = System::currentTimeMillis,
) : AuthManager {

    private val refreshMutex = Mutex()

    /**
     * Kept in sync with the token store: set on persist, cleared on sign-out and on
     * server-side rejection (401 / invalid_grant). One EncryptedSharedPreferences
     * read at construction — the store itself is already a DI singleton.
     */
    private val _authorized = MutableStateFlow(tokenStore.read(providerType) != null)
    override val authorized: StateFlow<Boolean> = _authorized

    /** The scope of the in-flight authorization, persisted with the resulting token. */
    @Volatile
    private var pendingScope: String = scope

    override fun hasDriveRead(): Boolean =
        tokenStore.read(providerType)?.scope?.contains("drive.readonly") == true

    override suspend fun requestDeviceAuthorization(readAccess: Boolean): ApiResult<DeviceAuthChallenge> {
        if (clientId.isBlank()) {
            Log.w(TAG, "device auth: OAuth client id not configured (GALLERY_GOOGLE_CLIENT_ID)")
            return ApiResult.Error(-1, "OAuth client id not configured (GALLERY_GOOGLE_CLIENT_ID)", false)
        }
        val requested = if (readAccess) readScope else scope
        pendingScope = requested
        Log.i(TAG, "device auth: requesting code (readAccess=$readAccess, scope=$requested)")
        return safeApiCall({ api.requestDeviceCode(deviceCodeEndpoint, clientId, requested) }) { body ->
            DeviceAuthChallenge(
                deviceCode = body.deviceCode,
                userCode = body.userCode,
                verificationUrl = body.verificationUrl ?: body.verificationUri.orEmpty(),
                expiresInSec = body.expiresIn,
                intervalSec = body.interval,
            )
        }.also { result ->
            when (result) {
                is ApiResult.Success ->
                    Log.i(TAG, "device auth: challenge received (expires=${result.data.expiresInSec}s, interval=${result.data.intervalSec}s)")
                is ApiResult.Error ->
                    Log.w(TAG, "device auth: request FAILED code=${result.code} retryable=${result.retryable} — ${result.message}")
            }
        }
    }

    override suspend fun pollForToken(challenge: DeviceAuthChallenge): ApiResult<Unit> {
        val deadline = now() + challenge.expiresInSec * 1000L
        var intervalMs = challenge.intervalSec.coerceAtLeast(1) * 1000L
        Log.i(TAG, "device auth: polling for approval (deadline in ${challenge.expiresInSec}s)")
        var pendingLogged = false

        while (now() < deadline) {
            delay(intervalMs)
            val response = try {
                api.exchangeDeviceCode(tokenEndpoint, clientId, clientSecret, challenge.deviceCode)
            } catch (e: Exception) {
                Log.w(TAG, "device auth: poll network error — ${e.message}")
                return ApiResult.Error(-1, e.message ?: "Network error during polling", retryable = true)
            }

            val body = response.body()
            if (response.isSuccessful && body?.accessToken != null && body.refreshToken != null) {
                persist(body.accessToken, body.refreshToken, body.expiresIn, pendingScope)
                Log.i(TAG, "device auth: APPROVED — tokens stored (scope=$pendingScope)")
                return ApiResult.Success(Unit)
            }

            // Pending/slow-down come back as OAuth errors (often HTTP 428/400).
            when (val error = body?.error ?: response.errorBody()?.let { parseError(it.string()) }) {
                "authorization_pending" -> {
                    if (!pendingLogged) {
                        pendingLogged = true
                        Log.i(TAG, "device auth: awaiting user approval…")
                    }
                }
                "slow_down" -> intervalMs += 5_000
                "access_denied" -> {
                    Log.w(TAG, "device auth: denied by user")
                    return ApiResult.Error(-1, "Access denied by user", retryable = false)
                }
                "expired_token" -> {
                    Log.w(TAG, "device auth: device code expired")
                    return ApiResult.Error(-1, "Device code expired", retryable = false)
                }
                else -> {
                    Log.w(TAG, "device auth: poll FAILED code=${response.code()} error=$error")
                    return ApiResult.Error(
                        response.code(),
                        body?.errorDescription ?: "Token polling failed",
                        retryable = false,
                    )
                }
            }
        }
        Log.w(TAG, "device auth: deadline passed without approval")
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
                // Preserve the granted scope across refreshes.
                persist(body.accessToken, body.refreshToken ?: fresh.refreshToken, body.expiresIn, fresh.scope)
                Log.i(TAG, "token refresh: OK")
                body.accessToken
            } else {
                // invalid_grant = the refresh token is dead (revoked, expired, or the
                // client changed): the grant can never recover, so drop it locally and
                // let the UI flip to "disconnected". Any other failure (network, 5xx)
                // is transient — keep the tokens and try again next time.
                val error = body?.error ?: response.errorBody()?.let { parseError(it.string()) }
                if (error == "invalid_grant") {
                    Log.w(TAG, "token refresh: invalid_grant — grant is dead, invalidating locally")
                    invalidateAuth()
                } else {
                    Log.w(TAG, "token refresh: FAILED code=${response.code()} error=$error (transient, keeping tokens)")
                }
                throw AuthNotAuthorizedException(providerType)
            }
        }
    }

    override fun isAuthorized(): Boolean = tokenStore.read(providerType) != null

    override fun invalidateAuth() {
        Log.w(TAG, "grant invalidated (${providerType.name}) — user must re-authorize")
        tokenStore.clear(providerType)
        _authorized.value = false
    }

    override suspend fun signOut() = invalidateAuth()

    private fun persist(accessToken: String, refreshToken: String, expiresInSec: Int?, scope: String) {
        val expiry = now() + (expiresInSec ?: DEFAULT_EXPIRY_SEC) * 1000L
        tokenStore.write(providerType, StoredTokens(accessToken, expiry, refreshToken, scope))
        _authorized.value = true
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
            readScope = OAuthConfig.Google.SCOPE_DRIVE_READ,
            deviceCodeEndpoint = OAuthConfig.Google.DEVICE_CODE_ENDPOINT,
            tokenEndpoint = OAuthConfig.Google.TOKEN_ENDPOINT,
            api = api,
            tokenStore = tokenStore,
        )
    }
}
