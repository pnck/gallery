package io.github.pnck.gallery.provider

import android.content.Context
import android.content.Intent
import io.github.pnck.gallery.network.ApiResult

/**
 * Per-provider auth contract (PRD §5.1). Implemented with AppAuth (no GMS):
 * - AuthState persisted as JSON in EncryptedSharedPreferences (one key per provider)
 * - getValidAccessToken() wraps performActionWithFreshTokens (transparent refresh)
 * - token endpoint traffic goes through the shared OkHttpClient via a custom
 *   ConnectionBuilder so it rides the acceleration chain (PRD §5.2)
 */
interface AuthManager {
    val providerType: ProviderType

    /**
     * Fires the browser-based authorization flow. The redirect lands back in the
     * app via AppAuth's RedirectUriReceiverActivity, which forwards the completion
     * intent to a host activity; that activity must call [handleAuthorizationResponse].
     */
    suspend fun startAuthorization(context: Context): ApiResult<Unit>

    /** Completes the code→token exchange from the completion intent. */
    suspend fun handleAuthorizationResponse(intent: Intent): ApiResult<Unit>

    /**
     * Always returns a fresh token; refreshes internally when expired.
     * @throws AuthNotAuthorizedException when no account is connected.
     */
    suspend fun getValidAccessToken(): String

    fun isAuthorized(): Boolean

    suspend fun signOut()
}

class AuthNotAuthorizedException(provider: ProviderType) :
    IllegalStateException("No authorized ${provider.name} account")

/** OAuth endpoints & scopes per provider (PRD §5.3). */
object OAuthConfig {
    object Google {
        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        /** drive.file only touches files this app created — BYOS least privilege (PRD §5.3). */
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    }

    object Microsoft {
        const val AUTH_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize"
        const val TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
        const val SCOPE = "Files.ReadWrite offline_access User.Read"
    }
}
