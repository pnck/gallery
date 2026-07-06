package io.github.pnck.gallery.provider

import android.content.Context
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

    suspend fun startAuthorization(context: Context): ApiResult<Unit>

    /** Always returns a fresh token; refreshes internally when expired. */
    suspend fun getValidAccessToken(): String

    fun isAuthorized(): Boolean

    suspend fun signOut()
}

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
