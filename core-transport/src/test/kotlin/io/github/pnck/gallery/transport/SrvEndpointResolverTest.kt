package io.github.pnck.gallery.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure SRV parsing + selection tests (RFC 2782 ordering); no network. */
class SrvEndpointResolverTest {

    // ── DoH JSON ────────────────────────────────────────────────────────────

    @Test
    fun `doh json picks lowest priority then highest weight, strips trailing dot`() {
        val json = """
            {"Status":0,"Answer":[
              {"type":33,"data":"20 5 51820 low-prio.example.com."},
              {"type":33,"data":"10 5 51821 high-prio.example.com."},
              {"type":33,"data":"10 50 51822 high-weight.example.com."},
              {"type":1,"data":"1.2.3.4"}
            ]}
        """.trimIndent()
        val ep = SrvEndpointResolver.pickBest(SrvEndpointResolver.parseDohJson(json))!!
        assertEquals("high-weight.example.com", ep.host)
        assertEquals(51822, ep.port)
    }

    @Test
    fun `doh json with no SRV answers yields nothing`() {
        assertNull(SrvEndpointResolver.pickBest(SrvEndpointResolver.parseDohJson("""{"Status":3}""")))
        assertNull(
            SrvEndpointResolver.pickBest(
                SrvEndpointResolver.parseDohJson("""{"Answer":[{"type":1,"data":"1.2.3.4"}]}"""),
            ),
        )
    }

    // ── Raw DNS wire message (system DNS path) ───────────────────────────────

    @Test
    fun `raw dns message parses SRV answers with a compressed answer name`() {
        val msg = buildMessage(
            question = "_wireguard._udp.example.com",
            answers = listOf(
                Srv(20, 5, 51820, "low.example.com"),
                Srv(10, 50, 51821, "hi.example.com"),
            ),
        )
        val ep = SrvEndpointResolver.pickBest(SrvEndpointResolver.parseDnsMessage(msg))!!
        assertEquals("hi.example.com", ep.host)
        assertEquals(51821, ep.port)
    }

    @Test
    fun `raw dns message with SERVFAIL rcode yields nothing`() {
        val msg = buildMessage("_wireguard._udp.example.com", emptyList(), rcode = 2)
        assertNull(SrvEndpointResolver.pickBest(SrvEndpointResolver.parseDnsMessage(msg)))
    }

    // ── Tiny DNS message encoder for the tests ───────────────────────────────

    private data class Srv(val priority: Int, val weight: Int, val port: Int, val target: String)

    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun encodeName(s: String): ByteArray {
        val out = ArrayList<Byte>()
        for (label in s.split('.')) {
            out.add(label.length.toByte())
            label.forEach { out.add(it.code.toByte()) }
        }
        out.add(0)
        return out.toByteArray()
    }

    private fun buildMessage(question: String, answers: List<Srv>, rcode: Int = 0): ByteArray {
        val header = byteArrayOf(
            0x12, 0x34, // ID
            0x81.toByte(), (0x80 or rcode).toByte(), // flags (QR=1) + RCODE
        ) + u16(1) + u16(answers.size) + u16(0) + u16(0)
        val q = encodeName(question) + u16(33) + u16(1) // QTYPE SRV, QCLASS IN
        var body = header + q
        for (a in answers) {
            val rdata = u16(a.priority) + u16(a.weight) + u16(a.port) + encodeName(a.target)
            body += byteArrayOf(0xC0.toByte(), 0x0C) + // NAME: pointer to the question at offset 12
                u16(33) + u16(1) + // TYPE SRV, CLASS IN
                byteArrayOf(0, 0, 1, 0x2C) + // TTL 300
                u16(rdata.size) + rdata
        }
        return body
    }
}
