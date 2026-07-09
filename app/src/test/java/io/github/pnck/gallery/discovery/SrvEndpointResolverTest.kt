package io.github.pnck.gallery.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure SRV selection tests (RFC 2782 ordering); no network. */
class SrvEndpointResolverTest {

    @Test
    fun `picks lowest priority then highest weight and strips trailing dot`() {
        val json = """
            {"Status":0,"Answer":[
              {"type":33,"data":"20 5 51820 low-prio.example.com."},
              {"type":33,"data":"10 5 51821 high-prio.example.com."},
              {"type":33,"data":"10 50 51822 high-weight.example.com."}
            ]}
        """.trimIndent()
        val ep = SrvEndpointResolver.bestEndpoint(json)!!
        assertEquals("high-weight.example.com", ep.host)
        assertEquals(51822, ep.port)
    }

    @Test
    fun `ignores non-SRV answers`() {
        val json = """
            {"Answer":[
              {"type":1,"data":"1.2.3.4"},
              {"type":33,"data":"10 5 443 wg.example.com."}
            ]}
        """.trimIndent()
        val ep = SrvEndpointResolver.bestEndpoint(json)!!
        assertEquals("wg.example.com", ep.host)
        assertEquals(443, ep.port)
    }

    @Test
    fun `returns null when there are no SRV answers`() {
        assertNull(SrvEndpointResolver.bestEndpoint("""{"Status":3}"""))
        assertNull(SrvEndpointResolver.bestEndpoint("""{"Answer":[{"type":1,"data":"1.2.3.4"}]}"""))
    }
}
