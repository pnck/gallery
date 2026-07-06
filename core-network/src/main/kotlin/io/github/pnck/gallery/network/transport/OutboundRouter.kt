package io.github.pnck.gallery.network.transport

/**
 * The insertion point of the transport layer (Transport Design §3.0, principle G5).
 *
 * The gallery kernel (providers, repository, Coil) never depends on the transport
 * module; they only see the shared HTTP client, which consults this router per
 * connection. When the router is [IDENTITY] (or the transport is disconnected),
 * the client behaves byte-for-byte as if the transport module never existed.
 */
fun interface OutboundRouter {
    /**
     * Returns the proxy this host should be dialed through,
     * or `null` for NO_PROXY (true direct connection).
     */
    fun proxyFor(host: String): ProxySpec?

    companion object {
        /** Transport off — always direct. */
        val IDENTITY: OutboundRouter = OutboundRouter { null }
    }
}

enum class ProxyKind { SOCKS5, HTTP_CONNECT }

data class ProxySpec(
    val kind: ProxyKind,
    val host: String,
    val port: Int,
)
