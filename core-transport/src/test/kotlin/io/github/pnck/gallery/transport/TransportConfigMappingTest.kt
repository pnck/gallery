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

    @Test
    fun `wgThenSocks maps wg params and upstream endpoint`() {
        val core = TransportConfig.WgThenSocks(
            wg = io.github.pnck.gallery.network.transport.WgConfig(
                privateKey = "priv",
                peerPublicKey = "peer",
                presharedKey = "psk",
                endpoint = Endpoint("vpn.example.com", 51820),
                interfaceAddresses = listOf("10.0.0.2/32"),
                allowedIps = listOf("0.0.0.0/0"),
                dns = emptyList(),
                persistentKeepaliveSeconds = 25,
            ),
            upstreamSocks = Endpoint("10.0.0.5", 1080),
        ).toCoreConfig() as CoreConfig.WgThenSocks

        assertEquals("priv", core.privateKey)
        assertEquals("peer", core.peerPublicKey)
        assertEquals("psk", core.presharedKey)
        assertEquals("vpn.example.com:51820", core.endpoint)
        assertEquals(listOf("10.0.0.2/32"), core.interfaceAddresses)
        assertEquals(25.toUShort(), core.keepaliveSecs)
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
