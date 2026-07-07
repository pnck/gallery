package io.github.pnck.gallery.provider

import io.github.pnck.gallery.network.ApiResult

/**
 * Per-provider auth contract — OAuth 2.0 Device Authorization Grant (ADR-0001,
 * supersedes PRD §5.2). Like `gh auth login`: the phone shows a code, the user
 * approves in any browser that can reach the provider, the phone polls for tokens.
 *
 * Both network calls go through the shared OkHttpClient, so they ride the
 * transport tunnel (insertion layer) once it is enabled — including the very
 * first login, which is the whole point (auth page unreachable without the tunnel).
 */
interface AuthManager {
    val providerType: ProviderType

    /** Step 1: obtain a device + user code to display (PRD §5, RFC 8628). */
    suspend fun requestDeviceAuthorization(): ApiResult<DeviceAuthChallenge>

    /**
     * Step 2: poll the token endpoint until the user approves (or the code
     * expires / is denied). On success, tokens are persisted.
     */
    suspend fun pollForToken(challenge: DeviceAuthChallenge): ApiResult<Unit>

    /**
     * Always returns a fresh access token; refreshes via refresh_token internally.
     * @throws AuthNotAuthorizedException when no account is connected.
     */
    suspend fun getValidAccessToken(): String

    fun isAuthorized(): Boolean

    suspend fun signOut()
}

/** What the UI shows the user to approve on a second screen (RFC 8628). */
data class DeviceAuthChallenge(
    val deviceCode: String,
    /** Short human-typed code, e.g. "WDJB-MJHT". */
    val userCode: String,
    /** Where the user enters the code, e.g. https://www.google.com/device. */
    val verificationUrl: String,
    val expiresInSec: Int,
    /** Minimum seconds between polls (server may ask to slow down). */
    val intervalSec: Int,
)

class AuthNotAuthorizedException(provider: ProviderType) :
    IllegalStateException("No authorized ${provider.name} account")

/** OAuth endpoints & scopes per provider (PRD §5.3, device-flow endpoints per ADR-0001). */
object OAuthConfig {
    object Google {
        const val DEVICE_CODE_ENDPOINT = "https://oauth2.googleapis.com/device/code"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        /** drive.file only touches files this app created — BYOS least privilege (PRD §5.3). */
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    }

    object Microsoft {
        const val DEVICE_CODE_ENDPOINT =
            "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode"
        const val TOKEN_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token"
        const val SCOPE = "Files.ReadWrite offline_access User.Read"
    }
}
