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
    fun `wgThenSocks is not yet supported`() {
        assertThrows(UnsupportedOperationException::class.java) {
            TransportConfig.WgThenSocks(
                wg = io.github.pnck.gallery.network.transport.WgConfig(
                    privateKey = "k",
                    peerPublicKey = "p",
                    presharedKey = null,
                    endpoint = Endpoint("vpn", 51820),
                    allowedIps = listOf("0.0.0.0/0"),
                    dns = emptyList(),
                ),
                upstreamSocks = Endpoint("10.0.0.1", 1080),
            ).toCoreConfig()
        }
    }
}
