package io.github.pnck.gallery.network

import io.github.pnck.gallery.network.transport.OutboundRouter
import io.github.pnck.gallery.network.transport.ProxyKind
import io.github.pnck.gallery.network.transport.ProxySpec
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Insertion-layer equivalence tests (Transport Design §9, principle G5):
 * router off must be indistinguishable from "transport module never existed".
 */
class SharedHttpClientTest {

    private val uri = URI("https://www.googleapis.com/drive/v3/files")

    @Test
    fun `identity router selects NO_PROXY for every host`() {
        val selector = SharedHttpClient.RouterProxySelector(OutboundRouter.IDENTITY)
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(uri))
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://graph.microsoft.com/v1.0/me")))
    }

    @Test
    fun `connected router selects loopback SOCKS`() {
        val router = OutboundRouter { ProxySpec(ProxyKind.SOCKS5, "127.0.0.1", 10808) }
        val selected = SharedHttpClient.RouterProxySelector(router).select(uri).single()

        assertEquals(Proxy.Type.SOCKS, selected.type())
        val address = selected.address() as InetSocketAddress
        assertEquals("127.0.0.1", address.hostString)
        assertEquals(10808, address.port)
    }

    @Test
    fun `proxy address stays unresolved to preserve remote DNS`() {
        // PRD §8.4.5: the app side must never pre-resolve target hostnames.
        val router = OutboundRouter { ProxySpec(ProxyKind.SOCKS5, "socks.lan", 1080) }
        val selected = SharedHttpClient.RouterProxySelector(router).select(uri).single()
        val address = selected.address() as InetSocketAddress
        assertTrue(address.isUnresolved)
    }

    @Test
    fun `client is HTTP2 over TCP only`() {
        val client = SharedHttpClient.build()
        assertEquals(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1), client.protocols)
    }
}
