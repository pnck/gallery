package io.github.pnck.gallery.network.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WgQuickConfigTest {

    private val sample = """
        [Interface]
        PrivateKey = aPrivateKey==
        Address = 10.0.0.2/32
        DNS = 1.1.1.1, 8.8.8.8
        MTU = 1280

        [Peer]
        PublicKey = aPeerKey==
        PresharedKey = aPsk==
        Endpoint = vpn.example.com:51820
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun `parses a standard wg-quick conf`() {
        val c = WgQuickConfig.parse(sample)
        assertEquals("aPrivateKey==", c.privateKey)
        assertEquals("10.0.0.2/32", c.address)
        assertEquals("1.1.1.1, 8.8.8.8", c.dns)
        assertEquals(1280, c.mtu)
        assertEquals("aPeerKey==", c.peerPublicKey)
        assertEquals("aPsk==", c.presharedKey)
        assertEquals("vpn.example.com:51820", c.endpoint)
        assertEquals(25, c.persistentKeepalive)
    }

    @Test
    fun `serialize then parse round-trips`() {
        val original = WgQuickConfig.parse(sample)
        val reparsed = WgQuickConfig.parse(original.serialize())
        assertEquals(original, reparsed)
    }

    @Test
    fun `ignores comments and is case-insensitive on keys`() {
        val c = WgQuickConfig.parse(
            """
            [Interface]
            privatekey = k==   # my key
            Address = 10.0.0.9/32
            [Peer]
            PUBLICKEY = p==
            Endpoint = h:1
            """.trimIndent(),
        )
        assertEquals("k==", c.privateKey)
        assertEquals("p==", c.peerPublicKey)
        assertEquals("10.0.0.9/32", c.address)
        // Optional fields absent → null / default.
        assertNull(c.mtu)
        assertNull(c.presharedKey)
        assertEquals(WgQuickConfig.DEFAULT_ALLOWED_IPS, c.allowedIps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing private key throws`() {
        WgQuickConfig.parse("[Interface]\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = p==")
    }
}
