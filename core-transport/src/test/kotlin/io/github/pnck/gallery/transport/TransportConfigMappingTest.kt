package io.github.pnck.gallery.transport

import io.github.pnck.gallery.network.transport.Cred
import io.github.pnck.gallery.network.transport.Endpoint
import io.github.pnck.gallery.network.transport.TransportConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.gallery_transport.CoreConfig

/**
 * Pure mapping tests — they never call an FFI method, so no .so is loaded.
 * Constructing the [CoreConfig] value types does not touch JNA.
 */
class TransportConfigMappingTest {

    @Test
    fun `direct maps to core Direct`() {
        assertTrue(TransportConfig.Direct.toCoreConfig() is CoreConfig.Direct)
    }

    @Test
    fun `socksOnly maps host port and credentials`() {
        val cfg = TransportConfig.SocksOnly(
            endpoint = Endpoint("10.0.0.1", 1080),
            auth = Cred("user", "pass"),
        )
        val core = cfg.toCoreConfig() as CoreConfig.SocksUpstream
        assertEquals("10.0.0.1", core.host)
        assertEquals(1080.toUShort(), core.port)
        assertEquals("user", core.username)
        assertEquals("pass", core.password)
    }

    @Test
    fun `socksOnly without auth yields null credentials`() {
        val core = TransportConfig.SocksOnly(Endpoint("h", 1), auth = null)
            .toCoreConfig() as CoreConfig.SocksUpstream
        assertEquals(null, core.username)
        assertEquals(null, core.password)
    }

    private fun wgConfig() = io.github.pnck.gallery.network.transport.WgConfig(
        privateKey = "priv",
        peerPublicKey = "peer",
        presharedKey = "psk",
        endpoint = Endpoint("vpn.example.com", 51820),
        interfaceAddresses = listOf("10.0.0.2/32"),
        dns = emptyList(),
        persistentKeepaliveSeconds = 25,
    )

    @Test
    fun `wgOnly maps wg settings`() {
        val core = TransportConfig.WgOnly(wgConfig()).toCoreConfig() as CoreConfig.WgOnly
        assertEquals("priv", core.wg.privateKey)
        assertEquals("vpn.example.com:51820", core.wg.endpoint)
        assertEquals(listOf("10.0.0.2/32"), core.wg.interfaceAddresses)
        assertEquals(25.toUShort(), core.wg.keepaliveSecs)
    }

    @Test
    fun `wgThenSocks maps wg settings and upstream endpoint`() {
        val core = TransportConfig.WgThenSocks(
            wg = wgConfig(),
            upstreamSocks = Endpoint("10.0.0.5", 1080),
        ).toCoreConfig() as CoreConfig.WgThenSocks

        assertEquals("priv", core.wg.privateKey)
        assertEquals("peer", core.wg.peerPublicKey)
        assertEquals("psk", core.wg.presharedKey)
        assertEquals("vpn.example.com:51820", core.wg.endpoint)
        assertEquals("10.0.0.5", core.upstreamHost)
        assertEquals(1080.toUShort(), core.upstreamPort)
    }

    @Test
    fun `httpOnly is not supported`() {
        assertThrows(UnsupportedOperationException::class.java) {
            TransportConfig.HttpOnly(Endpoint("h", 8080), auth = null).toCoreConfig()
        }
    }
}
