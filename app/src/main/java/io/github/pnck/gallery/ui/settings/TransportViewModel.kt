package io.github.pnck.gallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.network.transport.Cred
import io.github.pnck.gallery.network.transport.Endpoint
import io.github.pnck.gallery.network.transport.TransportConfig
import io.github.pnck.gallery.network.transport.TransportState
import io.github.pnck.gallery.network.transport.WgConfig
import io.github.pnck.gallery.transport.TransportController
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Debug entry point for the transport layer (EPIC-5). Lets a tester bring up the
 * WgThenSocks tunnel by hand and observe [TransportState] — there is no automatic
 * enablement yet, so this is the only way to exercise the tunnel on-device.
 */
@HiltViewModel
class TransportViewModel @Inject constructor(
    private val controller: TransportController,
    private val okHttpClient: OkHttpClient,
) : ViewModel() {

    val transportState: StateFlow<TransportState> =
        controller.state.stateIn(viewModelScope, SharingStarted.Eagerly, controller.state.value)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Build a WgThenSocks config from the form and connect. `endpoint` is
     * "host:port"; `interfaceAddress` is the tunnel-interior CIDR (e.g. 10.0.0.2/32).
     */
    fun connectWgThenSocks(
        privateKey: String,
        peerPublicKey: String,
        presharedKey: String,
        endpoint: String,
        interfaceAddress: String,
        keepaliveSecs: String,
        upstreamSocksHost: String,
        upstreamSocksPort: String,
        upstreamUser: String,
        upstreamPass: String,
    ) {
        val config = runCatching {
            val (epHost, epPort) = splitHostPort(endpoint, "WireGuard endpoint")
            val socksPort = upstreamSocksPort.trim().toIntOrNull()
                ?: error("upstream SOCKS port must be a number")
            TransportConfig.WgThenSocks(
                wg = WgConfig(
                    privateKey = privateKey.trim(),
                    peerPublicKey = peerPublicKey.trim(),
                    presharedKey = presharedKey.trim().ifBlank { null },
                    endpoint = Endpoint(epHost, epPort),
                    interfaceAddresses = listOf(interfaceAddress.trim()),
                    // allowedIps/dns are informational for the Rust core (the netstack
                    // routes everything out the single tunnel device); kept for parity.
                    allowedIps = listOf("0.0.0.0/0"),
                    dns = emptyList(),
                    persistentKeepaliveSeconds = keepaliveSecs.trim().toIntOrNull() ?: 25,
                ),
                upstreamSocks = Endpoint(upstreamSocksHost.trim(), socksPort),
                upstreamAuth = upstreamCred(upstreamUser, upstreamPass),
            )
        }.getOrElse {
            _error.value = it.message ?: "invalid transport config"
            return
        }

        viewModelScope.launch {
            _error.value = null
            runCatching { controller.connect(config) }
                .onSuccess {
                    // Drop pooled sockets so live connections re-dial via the tunnel
                    // (SharedHttpClient KDoc): the router flipped under a built client.
                    okHttpClient.connectionPool.evictAll()
                }
                .onFailure { _error.value = it.message ?: "failed to start tunnel" }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching { controller.disconnect() }
            okHttpClient.connectionPool.evictAll()
            _error.value = null
        }
    }

    private fun upstreamCred(user: String, pass: String): Cred? =
        if (user.isBlank()) null else Cred(user.trim(), pass)

    private fun splitHostPort(value: String, label: String): Pair<String, Int> {
        val idx = value.trim().lastIndexOf(':')
        require(idx > 0) { "$label must be host:port" }
        val host = value.trim().substring(0, idx)
        val port = value.trim().substring(idx + 1).toIntOrNull()
            ?: error("$label port must be a number")
        return host to port
    }
}
