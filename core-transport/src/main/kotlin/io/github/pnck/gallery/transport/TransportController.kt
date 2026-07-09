package io.github.pnck.gallery.transport

import io.github.pnck.gallery.network.transport.NetworkTransport
import io.github.pnck.gallery.network.transport.TransportHealth
import io.github.pnck.gallery.network.transport.OutboundRouter
import io.github.pnck.gallery.network.transport.ProxySpec
import io.github.pnck.gallery.network.transport.TransportConfig
import io.github.pnck.gallery.network.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of the (at most one) active [NetworkTransport], and the
 * single stable [OutboundRouter] handed to [io.github.pnck.gallery.network.SharedHttpClient].
 *
 * The router object never changes identity, so the shared OkHttpClient is built
 * once and never rebuilt (Transport §3.0). Flipping the transport on/off only
 * changes what [router] delegates to:
 *  - no active transport  → [OutboundRouter] returns `null` → NO_PROXY (true direct).
 *  - active + Connected    → routes hosts to the core's loopback SOCKS5.
 *
 * This preserves invariant #8: with the transport off the graph behaves exactly
 * as if :core-transport were never on the classpath.
 *
 * After a connect/disconnect, callers should evict the OkHttp connection pool so
 * pooled sockets re-dial via the new route (see SharedHttpClient KDoc).
 */
class TransportController(
    private val scope: CoroutineScope,
) {
    @Volatile
    private var active: NetworkTransport? = null

    private var stateJob: Job? = null

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    val state: StateFlow<TransportState> = _state.asStateFlow()

    /** Stable insertion-point router. Safe to build the OkHttpClient from this once. */
    val router: OutboundRouter = OutboundRouter { host -> active?.proxyFor(host) }

    /** Start (or restart) the transport for [config]. Direct mode is a no-op router. */
    suspend fun connect(config: TransportConfig) {
        disconnect()
        val transport = NativeNetworkTransport(config, scope)
        stateJob = scope.launch {
            transport.state.collect { _state.value = it }
        }
        active = transport
        transport.start()
    }

    suspend fun disconnect() {
        active?.let { transport ->
            active = null
            transport.stop()
        }
        stateJob?.cancel()
        stateJob = null
        _state.value = TransportState.Disconnected
    }

    /** Current loopback route for [host], if the transport is up. */
    fun proxyFor(host: String): ProxySpec? = active?.proxyFor(host)

    /** Poll a live health snapshot (transfer bytes, handshake) — null when off. */
    suspend fun health(): TransportHealth? = active?.probe()
}
