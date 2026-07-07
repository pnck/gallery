package io.github.pnck.gallery.provider.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.network.transport.OutboundRouter
import io.github.pnck.gallery.provider.AuthManager
import io.github.pnck.gallery.provider.AuthNotAuthorizedException
import io.github.pnck.gallery.provider.OAuthConfig
import io.github.pnck.gallery.provider.ProviderType
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

/**
 * AppAuth-based AuthManager (T-101, PRD §5) — no GMS, pure browser flow.
 *
 * - AuthState persisted as JSON in EncryptedSharedPreferences, one key per provider.
 * - Token endpoint traffic goes through [RouterConnectionBuilder] (insertion layer).
 * - [completionActivity] receives AppAuth's completion intent (the host app
 *   registers that activity and forwards the intent to [handleAuthorizationResponse]).
 */
class AppAuthManager(
    private val appContext: Context,
    override val providerType: ProviderType,
    private val clientId: String,
    private val redirectUri: Uri,
    private val completionActivity: Class<*>,
    router: OutboundRouter,
) : AuthManager {

    private val serviceConfig = when (providerType) {
        ProviderType.G_DRIVE -> AuthorizationServiceConfiguration(
            Uri.parse(OAuthConfig.Google.AUTH_ENDPOINT),
            Uri.parse(OAuthConfig.Google.TOKEN_ENDPOINT),
        )
        ProviderType.ONE_DRIVE -> AuthorizationServiceConfiguration(
            Uri.parse(OAuthConfig.Microsoft.AUTH_ENDPOINT),
            Uri.parse(OAuthConfig.Microsoft.TOKEN_ENDPOINT),
        )
    }

    private val scope = when (providerType) {
        ProviderType.G_DRIVE -> OAuthConfig.Google.SCOPE
        ProviderType.ONE_DRIVE -> OAuthConfig.Microsoft.SCOPE
    }

    private val authService: AuthorizationService by lazy {
        AuthorizationService(
            appContext,
            AppAuthConfiguration.Builder()
                .setConnectionBuilder(RouterConnectionBuilder(router))
                .build(),
        )
    }

    // EncryptedSharedPreferences is deprecated in security-crypto 1.1.0 with no
    // 1:1 replacement; the PRD (§5.2) mandates it for AuthState persistence.
    @Suppress("DEPRECATION")
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "auth_store",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val prefKey get() = "auth_state_${providerType.name}"

    @Volatile
    private var cachedState: AuthState? = null

    private fun readState(): AuthState? {
        cachedState?.let { return it }
        val json = prefs.getString(prefKey, null) ?: return null
        return runCatching { AuthState.jsonDeserialize(json) }
            .getOrNull()
            ?.also { cachedState = it }
    }

    private fun writeState(state: AuthState?) {
        cachedState = state
        prefs.edit().apply {
            if (state == null) remove(prefKey) else putString(prefKey, state.jsonSerializeString())
        }.apply()
    }

    override suspend fun startAuthorization(context: Context): ApiResult<Unit> {
        if (clientId.isBlank()) {
            return ApiResult.Error(
                code = -1,
                message = "OAuth client id not configured (set GALLERY_GOOGLE_CLIENT_ID)",
                retryable = false,
            )
        }
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            redirectUri,
        )
            .setScope(scope)
            .build() // PKCE is enabled by default (PRD §5.3)

        val completionIntent = PendingIntent.getActivity(
            appContext,
            providerType.ordinal,
            Intent(appContext, completionActivity),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return withContext(Dispatchers.Main) {
            runCatching {
                authService.performAuthorizationRequest(request, completionIntent, completionIntent)
                ApiResult.Success(Unit) as ApiResult<Unit>
            }.getOrElse {
                ApiResult.Error(-1, "Failed to launch authorization: ${it.message}", retryable = false)
            }
        }
    }

    override suspend fun handleAuthorizationResponse(intent: Intent): ApiResult<Unit> {
        val response = AuthorizationResponse.fromIntent(intent)
        val exception = AuthorizationException.fromIntent(intent)
        if (response == null) {
            return ApiResult.Error(
                code = exception?.code ?: -1,
                message = exception?.errorDescription ?: exception?.error ?: "Authorization cancelled",
                retryable = false,
            )
        }
        val state = AuthState(response, exception)
        return suspendCancellableCoroutine { cont ->
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenEx ->
                state.update(tokenResponse, tokenEx)
                if (tokenResponse != null) {
                    writeState(state)
                    cont.resume(ApiResult.Success(Unit))
                } else {
                    cont.resume(
                        ApiResult.Error(
                            code = tokenEx?.code ?: -1,
                            message = tokenEx?.errorDescription ?: "Token exchange failed",
                            retryable = false,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun getValidAccessToken(): String {
        val state = readState() ?: throw AuthNotAuthorizedException(providerType)
        return suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(authService) { accessToken, _, ex ->
                // Persist in case the refresh rotated tokens.
                writeState(state)
                when {
                    accessToken != null -> cont.resume(accessToken)
                    else -> cont.resumeWithException(ex ?: AuthNotAuthorizedException(providerType))
                }
            }
        }
    }

    override fun isAuthorized(): Boolean = readState()?.isAuthorized == true

    override suspend fun signOut() {
        writeState(null)
    }
}
