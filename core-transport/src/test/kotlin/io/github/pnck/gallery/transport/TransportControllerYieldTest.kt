package io.github.pnck.gallery.transport

import io.github.pnck.gallery.network.transport.ProxyKind
import io.github.pnck.gallery.network.transport.ProxySpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The system-VPN yield is a pure derivation (routeFor) plus one side effect
 * (pool eviction on VPN flips). Both are pinned here without any FFI/Android —
 * SystemVpnMonitor itself is a thin ConnectivityManager wrapper verified on-device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransportControllerYieldTest {

    private val route = ProxySpec(ProxyKind.SOCKS5, "127.0.0.1", 12345)

    @Test
    fun `routeFor passes the tunnel route through when no VPN is up`() {
        assertEquals(route, TransportController.routeFor(systemVpnActive = false, tunnelRoute = route))
    }

    @Test
    fun `routeFor masks the tunnel route while a system VPN is up`() {
        assertNull(TransportController.routeFor(systemVpnActive = true, tunnelRoute = route))
    }

    @Test
    fun `routeFor stays null when the tunnel itself has no route`() {
        assertNull(TransportController.routeFor(systemVpnActive = false, tunnelRoute = null))
        assertNull(TransportController.routeFor(systemVpnActive = true, tunnelRoute = null))
    }

    @Test
    fun `vpn flips trigger a route rebind so pooled sockets re-dial`() {
        val vpn = MutableStateFlow(false)
        val scope = TestScope(UnconfinedTestDispatcher())
        val controller = TransportController(scope, systemVpnActive = vpn)
        var rebinds = 0
        controller.onRouteRebind = { rebinds++ }

        // StateFlow conflates to the current value at collection start; that first
        // (startup) emission is not a flip and must not evict.
        val initial = rebinds
        vpn.value = true
        assertEquals(initial + 1, rebinds)
        vpn.value = true // no change → no rebind
        assertEquals(initial + 1, rebinds)
        vpn.value = false
        assertEquals(initial + 2, rebinds)
    }

    @Test
    fun `router yields to the VPN even before any transport exists`() {
        val vpn = MutableStateFlow(true)
        val controller = TransportController(TestScope(UnconfinedTestDispatcher()), systemVpnActive = vpn)
        assertNull(controller.router.proxyFor("www.googleapis.com"))
        assertNull(controller.proxyFor("www.googleapis.com"))
    }
}
