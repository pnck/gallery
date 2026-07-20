package io.github.pnck.gallery.provider.auth

import io.github.pnck.gallery.network.ApiResult
import io.github.pnck.gallery.provider.AuthNotAuthorizedException
import io.github.pnck.gallery.provider.ProviderType
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

private class FakeTokenStore : TokenStore {
    private val map = mutableMapOf<ProviderType, StoredTokens>()
    override fun read(provider: ProviderType) = map[provider]
    override fun write(provider: ProviderType, tokens: StoredTokens) { map[provider] = tokens }
    override fun clear(provider: ProviderType) { map.remove(provider) }
}

/** Scripted DeviceAuthApiService: each poll/refresh call pops the next queued response. */
private class FakeDeviceAuthApi(
    var deviceCode: Response<DeviceCodeResponse>? = null,
    val exchangeQueue: ArrayDeque<Response<TokenResponse>> = ArrayDeque(),
    var refresh: Response<TokenResponse>? = null,
) : DeviceAuthApiService {
    override suspend fun requestDeviceCode(endpoint: String, clientId: String, scope: String) =
        deviceCode!!
    override suspend fun exchangeDeviceCode(
        endpoint: String,
        clientId: String,
        clientSecret: String,
        deviceCode: String,
        grantType: String,
    ) = exchangeQueue.removeFirst()
    override suspend fun refreshToken(
        endpoint: String,
        clientId: String,
        clientSecret: String,
        refreshToken: String,
        grantType: String,
    ) = refresh!!
}

// Device-flow pending/denied come back as OAuth errors in the HTTP error body.
private fun tokenError(error: String, httpCode: Int = 400): Response<TokenResponse> =
    Response.error(httpCode, "{\"error\":\"$error\"}".toResponseBody("application/json".toMediaType()))

class DeviceFlowAuthManagerTest {

    private var clock = 1_000_000L

    private fun manager(api: DeviceAuthApiService, store: TokenStore) = DeviceFlowAuthManager(
        providerType = ProviderType.G_DRIVE,
        clientId = "cid",
        clientSecret = "csecret",
        scope = "scope",
        readScope = "scope readscope",
        deviceCodeEndpoint = "https://example/device",
        tokenEndpoint = "https://example/token",
        api = api,
        tokenStore = store,
        now = { clock },
    )

    @Test
    fun `polls through pending then succeeds and stores tokens`() = runTest {
        val api = FakeDeviceAuthApi(
            exchangeQueue = ArrayDeque(
                listOf(
                    tokenError("authorization_pending"),
                    tokenError("authorization_pending"),
                    Response.success(TokenResponse(accessToken = "AT", refreshToken = "RT", expiresIn = 3600)),
                ),
            ),
        )
        val store = FakeTokenStore()
        val mgr = manager(api, store)

        val result = mgr.pollForToken(
            io.github.pnck.gallery.provider.DeviceAuthChallenge("dc", "WXYZ", "https://example/device", 900, 0),
        )

        assertTrue(result is ApiResult.Success)
        assertTrue(mgr.isAuthorized())
        assertEquals("AT", store.read(ProviderType.G_DRIVE)?.accessToken)
        assertEquals("RT", store.read(ProviderType.G_DRIVE)?.refreshToken)
    }

    @Test
    fun `access denied stops polling with error`() = runTest {
        val api = FakeDeviceAuthApi(exchangeQueue = ArrayDeque(listOf(tokenError("access_denied"))))
        val mgr = manager(api, FakeTokenStore())

        val result = mgr.pollForToken(
            io.github.pnck.gallery.provider.DeviceAuthChallenge("dc", "WXYZ", "u", 900, 0),
        )
        assertTrue(result is ApiResult.Error)
    }

    @Test
    fun `getValidAccessToken returns cached token when not expired`() = runTest {
        val store = FakeTokenStore().apply {
            write(ProviderType.G_DRIVE, StoredTokens("cached", clock + 3_600_000, "RT"))
        }
        val mgr = manager(FakeDeviceAuthApi(), store)
        assertEquals("cached", mgr.getValidAccessToken())
    }

    @Test
    fun `getValidAccessToken refreshes when expired`() = runTest {
        val store = FakeTokenStore().apply {
            write(ProviderType.G_DRIVE, StoredTokens("stale", clock - 1000, "RT"))
        }
        val api = FakeDeviceAuthApi(
            refresh = Response.success(TokenResponse(accessToken = "fresh", expiresIn = 3600)),
        )
        val mgr = manager(api, store)

        assertEquals("fresh", mgr.getValidAccessToken())
        // refresh_token is preserved when the response omits a new one
        assertEquals("RT", store.read(ProviderType.G_DRIVE)?.refreshToken)
    }

    @Test(expected = AuthNotAuthorizedException::class)
    fun `getValidAccessToken throws when no account`() = runTest {
        manager(FakeDeviceAuthApi(), FakeTokenStore()).getValidAccessToken()
    }

    @Test
    fun `signOut clears tokens`() = runTest {
        val store = FakeTokenStore().apply {
            write(ProviderType.G_DRIVE, StoredTokens("AT", clock + 1000, "RT"))
        }
        val mgr = manager(FakeDeviceAuthApi(), store)
        assertTrue(mgr.isAuthorized())
        mgr.signOut()
        assertFalse(mgr.isAuthorized())
    }

    @Test
    fun `invalid_grant on refresh invalidates the grant locally`() = runTest {
        val store = FakeTokenStore().apply {
            write(ProviderType.G_DRIVE, StoredTokens("stale", clock - 1000, "RT"))
        }
        val api = FakeDeviceAuthApi(refresh = tokenError("invalid_grant"))
        val mgr = manager(api, store)
        assertTrue(mgr.authorized.value)

        try {
            mgr.getValidAccessToken()
            error("expected AuthNotAuthorizedException")
        } catch (_: AuthNotAuthorizedException) {
        }

        // A dead grant must not keep reporting "connected".
        assertFalse(mgr.isAuthorized())
        assertFalse(mgr.authorized.value)
        assertEquals(null, store.read(ProviderType.G_DRIVE))
    }

    @Test
    fun `transient refresh failure keeps the grant`() = runTest {
        val store = FakeTokenStore().apply {
            write(ProviderType.G_DRIVE, StoredTokens("stale", clock - 1000, "RT"))
        }
        val api = FakeDeviceAuthApi(refresh = tokenError("temporarily_unavailable", httpCode = 503))
        val mgr = manager(api, store)

        try {
            mgr.getValidAccessToken()
            error("expected AuthNotAuthorizedException")
        } catch (_: AuthNotAuthorizedException) {
        }

        // Network/5xx is retryable: tokens stay, next attempt may succeed.
        assertTrue(mgr.isAuthorized())
        assertTrue(mgr.authorized.value)
    }

    @Test
    fun `authorized flow tracks persist and invalidate`() = runTest {
        val store = FakeTokenStore()
        val mgr = manager(FakeDeviceAuthApi(), store)
        assertFalse(mgr.authorized.value)

        val api = FakeDeviceAuthApi(
            exchangeQueue = ArrayDeque(
                listOf(Response.success(TokenResponse(accessToken = "AT", refreshToken = "RT", expiresIn = 3600))),
            ),
        )
        val mgr2 = manager(api, store)
        mgr2.pollForToken(
            io.github.pnck.gallery.provider.DeviceAuthChallenge("dc", "WXYZ", "u", 900, 0),
        )
        assertTrue(mgr2.authorized.value)

        mgr2.invalidateAuth()
        assertFalse(mgr2.authorized.value)
        assertFalse(mgr2.isAuthorized())
    }
}
