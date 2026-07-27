package io.github.pnck.gallery.transport

import android.util.Log
import io.github.pnck.gallery.network.transport.NetworkTransport
import io.github.pnck.gallery.network.transport.TransportHealth
import io.github.pnck.gallery.network.transport.OutboundRouter
import io.github.pnck.gallery.network.transport.ProxySpec
import io.github.pnck.gallery.network.transport.TransportConfig
import io.github.pnck.gallery.network.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide owner of the (at most one) active [NetworkTransport], and the
 * single stable [OutboundRouter] handed to [io.github.pnck.gallery.network.SharedHttpClient].
 *
 * The router object never changes identity, so the shared OkHttpClient is built
 * once and never rebuilt (Transport §3.0). Flipping the transport on/off only
 * changes what [router] delegates to:
 *  - no active transport  → [OutboundRouter] returns `null` → NO_PROXY (true direct).
 *  - active + Connected    → routes hosts to the core's loopback SOCKS5.
 *  - system VPN active     → yields: also `null` → NO_PROXY (the tunnel keeps
 *    running but receives no traffic, so its WG UDP never fights the VPN).
 *
 * This preserves invariant #8: with the transport off the graph behaves exactly
 * as if :core-transport were never on the classpath.
 *
 * After a connect/disconnect, callers should evict the OkHttp connection pool so
 * pooled sockets re-dial via the new route (see SharedHttpClient KDoc).
 */
class TransportController(
    private val scope: CoroutineScope,
    /**
     * Live projection of "a system VPN covers this app" (see [SystemVpnMonitor]).
     * While true, the router yields — returns null → NO_PROXY — so our userspace
     * WG tunnel never fights the system VPN for the same peer. Null in tests.
     */
    val systemVpnActive: StateFlow<Boolean>? = null,
) {
    @Volatile
    private var active: NetworkTransport? = null

    private var stateJob: Job? = null
    private var supervisorJob: Job? = null

    /** The config the user WANTS connected; null means "stay disconnected". The
     *  supervisor only auto-reconnects while this is non-null. */
    @Volatile
    private var desiredConfig: TransportConfig? = null

    /** Serializes connect/disconnect so two concurrent connects can't each spawn a
     *  driver (leaking one → two WG tunnels fighting the same peer). */
    private val lifecycleLock = Mutex()

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    val state: StateFlow<TransportState> = _state.asStateFlow()

    /** Stable insertion-point router. Safe to build the OkHttpClient from this once. */
    val router: OutboundRouter = OutboundRouter { host ->
        routeFor(systemVpnActive?.value == true, active?.proxyFor(host))
    }

    init {
        // A VPN flip rebinds every route (tunnel ↔ direct), so pooled OkHttp
        // connections must be evicted exactly like on connect/disconnect — otherwise
        // sockets pooled under the old route keep dialing it. The tunnel itself is
        // NOT torn down: nothing is routed to it while yielding, so its WG socket
        // idles, and traffic flows through it again the instant the VPN goes away
        // (no reconnect race — resume is a pure derivation).
        systemVpnActive?.let { vpn ->
            scope.launch {
                // drop(1): the startup emission is not a flip — the router already
                // derives the right route from the current value, and the pool is
                // empty at boot, so there is nothing to rebind.
                vpn.drop(1).distinctUntilChanged().collect { activeVpn ->
                    Log.w(
                        TAG,
                        if (activeVpn) {
                            "system VPN up — transport yields, traffic goes direct"
                        } else {
                            "system VPN gone — transport route restored"
                        },
                    )
                    onRouteRebind?.invoke()
                    if (!activeVpn) {
                        // The network path just changed fundamentally: a failure
                        // parked while yielding (e.g. driverDead) OR a stuck
                        // Degraded (handshake lost on the VPN-captured socket —
                        // boringtun re-keys through the same dead socket, so only
                        // a fresh driver heals it) becomes actionable now.
                        val st = _state.value
                        val cfg = desiredConfig
                        val stuck = st is TransportState.Degraded ||
                            (st is TransportState.Failed && st.retryable)
                        if (stuck && cfg != null) {
                            runCatching { connect(cfg) }
                        }
                    }
                }
            }
        }
    }

    /**
     * Invoked on EVERY route rebind — connect (a fresh tunnel gets a fresh loopback
     * port) and disconnect — including the supervisor's automatic reconnects. The
     * app wires this to evict the OkHttp connection pool: without it, pooled
     * connections keep pointing at the dead old port and requests vanish into a
     * dead tunnel for up to the 5-min keepalive ("restart the app fixes it").
     */
    @Volatile
    var onRouteRebind: (() -> Unit)? = null

    /** Start (or restart) the transport for [config]. Direct mode is a no-op router. */
    suspend fun connect(config: TransportConfig) = lifecycleLock.withLock {
        desiredConfig = config
        teardownLocked()
        val transport = NativeNetworkTransport(config, scope, systemVpnActive)
        stateJob = scope.launch {
            transport.state.collect { _state.value = it }
        }
        active = transport
        ensureSupervisor()
        transport.start()
        onRouteRebind?.invoke()
    }

    suspend fun disconnect() = lifecycleLock.withLock {
        desiredConfig = null
        supervisorJob?.cancel()
        supervisorJob = null
        teardownLocked()
        onRouteRebind?.invoke()
    }

    /**
     * Watch the published state and, when a WG tunnel reports Failed(retryable) (e.g.
     * a handshake it can't recover), reconnect with a fresh driver after a short
     * backoff. Runs for the controller's lifetime; only reconnects while the user
     * still wants a connection ([desiredConfig] non-null). Separate from [stateJob]
     * so a reconnect's teardown doesn't cancel the supervisor itself.
     */
    private fun ensureSupervisor() {
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch {
            _state.collect { st ->
                if (st is TransportState.Failed && st.retryable) {
                    val cfg = desiredConfig ?: return@collect
                    // While a system VPN holds the network the failure is expected —
                    // reconnecting now would just fight the VPN and hot-loop. The
                    // VPN-off transition re-triggers the reconnect (init collector).
                    if (systemVpnActive?.value == true) return@collect
                    delay(RECONNECT_BACKOFF_MS)
                    if (desiredConfig != null && systemVpnActive?.value != true) {
                        runCatching { connect(cfg) }
                    }
                }
            }
        }
    }

    /** Must be called with [lifecycleLock] held. */
    private suspend fun teardownLocked() {
        active?.let { transport ->
            active = null
            transport.stop()
        }
        stateJob?.cancel()
        stateJob = null
        _state.value = TransportState.Disconnected
    }

    /** Current effective route for [host] — null while off OR while yielding to a
     *  system VPN (same decision the [router] applies). */
    fun proxyFor(host: String): ProxySpec? = routeFor(systemVpnActive?.value == true, active?.proxyFor(host))

    /** Poll a live health snapshot (transfer bytes, handshake) — null when off. */
    suspend fun health(): TransportHealth? = active?.probe()

    /** Ground-truth dump of the running config, or null when off. */
    suspend fun diagnosticInfo(): String? = active?.diagnosticInfo()

    internal companion object {
        const val TAG = "TransportController"

        /** Delay before an automatic reconnect, so a flapping peer can't hot-loop us. */
        const val RECONNECT_BACKOFF_MS = 5_000L

        /**
         * The pure yield decision: an active system VPN always wins — the tunnel
         * route is masked (null → NO_PROXY) for as long as the VPN holds the
         * network. Derived from inputs, nothing is synchronized or restored.
         */
        internal fun routeFor(systemVpnActive: Boolean, tunnelRoute: ProxySpec?): ProxySpec? =
            if (systemVpnActive) null else tunnelRoute
    }
}
