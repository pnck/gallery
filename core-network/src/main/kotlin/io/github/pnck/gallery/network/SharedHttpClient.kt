package io.github.pnck.gallery.network

import io.github.pnck.gallery.network.transport.OutboundRouter
import io.github.pnck.gallery.network.transport.ProxyKind
import io.github.pnck.gallery.network.transport.ProxySpec
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol

/**
 * Factory for the single shared [OkHttpClient] (PRD §8.1).
 *
 * - Retrofit (Drive + device-flow token calls) and Coil all reuse this one
 *   instance, sharing the pool and — when the transport is enabled — one tunnel.
 * - HTTP/2 over TCP is forced; HTTP/3(QUIC/UDP) is excluded because the SOCKS
 *   tunnel only carries TCP (PRD §8.4.6).
 * - The transport is injected as a dynamic [ProxySelector] reading the
 *   [OutboundRouter] per connection: flipping the router on/off never rebuilds
 *   the client (Transport Design §3.0). Call [OkHttpClient.connectionPool]
 *   `.evictAll()` after a flip so pooled connections re-dial via the new route.
 */
object SharedHttpClient {

    fun build(
        router: OutboundRouter = OutboundRouter.IDENTITY,
        configure: OkHttpClient.Builder.() -> Unit = {},
    ): OkHttpClient =
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))
            .proxySelector(RouterProxySelector(router))
            // Bounded timeouts so an unreachable host (e.g. first login before the
            // tunnel is up) fails in predictable time instead of hanging the UI.
            // Per-operation, not total — uploads aren't capped (no callTimeout).
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .apply(configure)
            .build()

    internal class RouterProxySelector(private val router: OutboundRouter) : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host ?: return listOf(Proxy.NO_PROXY)
            val spec = router.proxyFor(host) ?: return listOf(Proxy.NO_PROXY)
            return listOf(spec.toJavaProxy())
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // Tunnel failures surface via the transport layer's state, not by
            // silently retrying direct here (which would leak intent).
        }
    }
}

fun ProxySpec.toJavaProxy(): Proxy {
    val type = when (kind) {
        ProxyKind.SOCKS5 -> Proxy.Type.SOCKS
        ProxyKind.HTTP_CONNECT -> Proxy.Type.HTTP
    }
    // createUnresolved keeps the hostname intact so DNS resolves at the far end of
    // the chain (remote DNS — the actual source of the acceleration, PRD §8.4.5).
    return Proxy(type, InetSocketAddress.createUnresolved(host, port))
}
