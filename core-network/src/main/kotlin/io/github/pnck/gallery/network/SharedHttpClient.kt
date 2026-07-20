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

    /**
     * ONE pool shared by every client built here (app client, bare auth client):
     * pooled connections all die together when the route flips, and [evictAll]
     * below covers all of them in one call. A per-client pool let token calls
     * keep dead tunnel connections across reconnects.
     */
    private val sharedPool = ConnectionPool(15, 5, TimeUnit.MINUTES)

    /** Separate pool for bulk transfers — see [buildUploadClient]. */
    private val uploadPool = ConnectionPool(6, 5, TimeUnit.MINUTES)

    /** Drop all pooled connections — call after the transport route rebinds. */
    fun evictAll() {
        sharedPool.evictAll()
        uploadPool.evictAll()
    }

    fun build(
        router: OutboundRouter = OutboundRouter.IDENTITY,
        configure: OkHttpClient.Builder.() -> Unit = {},
    ): OkHttpClient =
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(sharedPool)
            .proxySelector(RouterProxySelector(router))
            // Bounded timeouts so an unreachable host (e.g. first login before the
            // tunnel is up) fails in predictable time instead of hanging the UI.
            // Per-operation, not total — uploads aren't capped (no callTimeout).
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .apply(configure)
            .build()

    /**
     * The bulk-transfer client for resumable upload chunks (PRD §4.4, §8.1):
     *  - HTTP/1.1 ONLY: HTTP/2 would coalesce every parallel upload onto ONE TCP
     *    connection (one tunnel socket, one congestion window); H1.1 gives each
     *    parallel file its own connection — real multi-connection acceleration.
     *  - Its own pool, so bulk sockets can't starve interactive API/thumbnail
     *    requests of pool slots.
     *  - Longer read/write timeouts: an 8 MiB chunk over a slow tunnel legitimately
     *    takes tens of seconds; the server is silent while receiving a chunk.
     */
    fun buildUploadClient(
        router: OutboundRouter = OutboundRouter.IDENTITY,
        configure: OkHttpClient.Builder.() -> Unit = {},
    ): OkHttpClient =
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(uploadPool)
            .proxySelector(RouterProxySelector(router))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
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
