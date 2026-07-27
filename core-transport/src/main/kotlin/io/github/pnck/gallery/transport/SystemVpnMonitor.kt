package io.github.pnck.gallery.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stateless projection of "a system VpnService currently covers this app's default
 * network" — TRANSPORT_VPN on the default network's capabilities.
 *
 * Used by [TransportController] to make the userspace WG tunnel yield while another
 * VPN holds the network: our tunnel's UDP would otherwise be force-routed into the
 * system VPN (tunnel-in-tunnel), killing every request that rides it.
 *
 * If the VPN app excluded us (addDisallowedApplication / per-app split tunneling),
 * our default network stays on the underlying Wi-Fi/cell → false — correctly so,
 * because then our WG UDP bypasses the VPN and there is no fight to yield from.
 *
 * The callback is registered for the process lifetime (the monitor is a DI
 * singleton) and re-derives the value from scratch on every event — there is no
 * accumulated state to desynchronize.
 */
class SystemVpnMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _vpnActive = MutableStateFlow(currentVpnActive())
    val vpnActive: StateFlow<Boolean> = _vpnActive.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _vpnActive.value = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }

        override fun onLost(network: Network) {
            // The network we tracked is gone; re-derive from whatever is default now.
            _vpnActive.value = currentVpnActive()
        }
    }

    init {
        // Fires immediately with the current default network, then on every
        // switch/capability change — no polling, no missed transitions.
        cm.registerDefaultNetworkCallback(callback)
    }

    private fun currentVpnActive(): Boolean =
        cm.activeNetwork
            ?.let(cm::getNetworkCapabilities)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
}
