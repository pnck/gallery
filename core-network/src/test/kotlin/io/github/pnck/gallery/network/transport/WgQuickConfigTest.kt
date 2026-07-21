package io.github.pnck.gallery.network.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `zip round-trip matches official app export format`() {
        val conf = WgQuickConfig.parse(sample)
        val zip = WgQuickConfig.toZipBytes("gallery-wg", conf)

        assertTrue(WgQuickConfig.isZip(zip))
        val (parsed, entryName) = WgQuickConfig.parseAny(zip)
        assertEquals("gallery-wg.conf", entryName)
        assertEquals(conf, parsed)
    }

    @Test
    fun `parseAny accepts a bare conf and reports no entry name`() {
        val (parsed, entryName) = WgQuickConfig.parseAny(sample.toByteArray())
        assertNull(entryName)
        assertEquals(WgQuickConfig.parse(sample), parsed)
    }

    @Test
    fun `parseAny on a zip without conf entries throws`() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("readme.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
        }
        try {
            WgQuickConfig.parseAny(out.toByteArray())
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `parseAny picks the first conf entry in a multi-tunnel zip`() {
        val conf = WgQuickConfig.parse(sample)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("tun-a.conf"))
            zip.write(conf.serialize().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("tun-b.conf"))
            zip.write(conf.copy(endpoint = "other.example.com:51820").serialize().toByteArray())
            zip.closeEntry()
        }
        val (parsed, entryName) = WgQuickConfig.parseAny(out.toByteArray())
        assertEquals("tun-a.conf", entryName)
        assertEquals("vpn.example.com:51820", parsed.endpoint)
    }
}
