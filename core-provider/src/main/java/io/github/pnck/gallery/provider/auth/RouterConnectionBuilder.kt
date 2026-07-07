package io.github.pnck.gallery.provider.auth

import android.net.Uri
import io.github.pnck.gallery.network.toJavaProxy
import io.github.pnck.gallery.network.transport.OutboundRouter
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.TimeUnit
import net.openid.appauth.connectivity.ConnectionBuilder

/**
 * AppAuth ConnectionBuilder that honors the transport insertion layer (PRD §5.2):
 * token exchange/refresh requests ride the same acceleration chain as everything
 * else. AppAuth's API is HttpURLConnection-based, so we can't reuse the OkHttp
 * instance itself — but routing through the same [OutboundRouter] preserves the
 * chain (and remote DNS via unresolved proxy addresses).
 *
 * The interactive authorization page opens in the system browser and cannot be
 * proxied by the app — known exception, PRD §5.2 / D9.
 */
class RouterConnectionBuilder(
    private val router: OutboundRouter,
) : ConnectionBuilder {

    override fun openConnection(uri: Uri): HttpURLConnection {
        require(uri.scheme.equals("https", ignoreCase = true)) {
            "Only https token endpoints are allowed, got: $uri"
        }
        val url = URL(uri.toString())
        val proxy = url.host?.let { router.proxyFor(it)?.toJavaProxy() } ?: Proxy.NO_PROXY
        return (url.openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
            readTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
            instanceFollowRedirects = false
        }
    }
}
