package io.github.pnck.gallery.transport

import android.os.SystemClock
import io.github.pnck.gallery.network.transport.NetworkTransport
import io.github.pnck.gallery.network.transport.ProxyKind
import io.github.pnck.gallery.network.transport.ProxySpec
import io.github.pnck.gallery.network.transport.TransportConfig
import io.github.pnck.gallery.network.transport.TransportHealth
import io.github.pnck.gallery.network.transport.TransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.gallery_transport.CoreState
import uniffi.gallery_transport.StateCallback
import uniffi.gallery_transport.TransportException
import uniffi.gallery_transport.WgCore
import uniffi.gallery_transport.TransportHealth as CoreHealth

/**
 * The [NetworkTransport] backed by the Rust Tier-1 core over UniFFI (EPIC-5, T-502).
 *
 * It IS an OutboundRouter: while [state] is [TransportState.Connected] it routes
 * every host to the loopback SOCKS5 port the Rust core bound; otherwise
 * [proxyFor] returns `null` and the shared client dials direct — so a stopped
 * transport is byte-for-byte "never integrated" (invariant #8, Transport §3.0).
 *
 * Each instance owns one [WgCore] (one Rust driver). "At most one tunnel for the
 * process" (Transport §6.4) is enforced by [TransportController], which serializes
 * connect/disconnect so a new instance is only started after the previous one is
 * fully stopped — never two drivers racing the same WG peer.
 */
class NativeNetworkTransport(
    private val config: TransportConfig,
    private val scope: CoroutineScope,
    /**
     * Live "a system VPN covers us" projection (see [SystemVpnMonitor]). While
     * true, handshake loss is EXPECTED — our WG UDP rides the VPN and may not
     * reach the peer — so the monitor neither degrades nor escalates to Failed;
     * boringtun re-keys on its own once traffic resumes after the VPN drops.
     */
    private val vpnActive: StateFlow<Boolean>? = null,
) : NetworkTransport {

    private val core: WgCore by lazy { WgCore() }

    private val isWg = config is TransportConfig.WgThenSocks || config is TransportConfig.WgOnly

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private var handshakeJob: Job? = null

    /** Loopback SOCKS5 port; 0 when the core is down. Read on every proxyFor(). */
    @Volatile
    private var localSocksPort: Int = 0

    override fun proxyFor(host: String): ProxySpec? {
        val port = localSocksPort
        if (port == 0) return null
        // 127.0.0.1 is the Rust core's inbound; it applies remote DNS upstream so
        // we still hand it the hostname verbatim (SharedHttpClient uses an
        // unresolved address — Transport §4.2).
        return ProxySpec(ProxyKind.SOCKS5, LOOPBACK, port)
    }

    override suspend fun start(): Unit = withContext(Dispatchers.IO) {
        _state.value = TransportState.Connecting
        // Reflect core-side failures (e.g. WG handshake loss in phase 2) into state.
        core.setStateCallback(object : StateCallback {
            override fun onState(state: CoreState) {
                if (state == CoreState.FAILED) {
                    localSocksPort = 0
                    _state.value = TransportState.Failed("core reported FAILED", retryable = true)
                }
            }
        })
        try {
            val port = core.start(config.toCoreConfig()).toInt()
            localSocksPort = port
            if (isWg) {
                // core.start() only means the listener + WG driver are up — NOT that
                // the tunnel handshaked. Stay Connecting and watch the real handshake.
                monitorHandshake(port)
            } else {
                // Direct/SocksOnly have no handshake; the listener being up is "up".
                _state.value = TransportState.Connected(port, epochSeconds())
            }
        } catch (e: TransportException) {
            localSocksPort = 0
            _state.value = TransportState.Failed(e.message ?: "transport start failed", retryable = true)
            throw e
        } catch (t: Throwable) {
            // UniFFI runtime errors, config-mapping IllegalArgumentException, etc.:
            // without this the state would wedge at Connecting with traffic silently
            // going direct, and the supervisor (which only acts on Failed) never fires.
            localSocksPort = 0
            _state.value = TransportState.Failed(t.message ?: "transport start crashed", retryable = true)
            throw t
        }
    }

    /** Poll the WG handshake and drive Connecting → Connected. If a working tunnel
     *  later loses its handshake and doesn't recover within a grace window, surface
     *  Failed(retryable) so the controller reconnects with a fresh driver (self-heal
     *  for the "handshake lost then permanent stall" case). */
    private fun monitorHandshake(port: Int) {
        _state.value = TransportState.Connecting
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            // Monotonic clock: wall-clock jumps (NTP/user) must not fabricate a
            // 20 s "handshake lost" gap and trigger a spurious reconnect.
            val startMs = SystemClock.elapsedRealtime()
            var everConnected = false
            var lastOkMs = 0L
            while (isActive) {
                val h = withContext(Dispatchers.IO) { core.health() }
                val now = SystemClock.elapsedRealtime()
                when {
                    // The Rust driver thread EXITED (panic caught, or unexpected
                    // return): the tunnel can never recover on its own — fail fast
                    // even if no handshake ever completed.
                    h.driverDead -> {
                        _state.value = TransportState.Failed(
                            "transport driver died — reconnecting with a fresh driver.",
                            retryable = true,
                        )
                        return@launch
                    }
                    h.handshakeOk -> {
                        everConnected = true
                        lastOkMs = now
                        _state.value = TransportState.Connected(port, h.lastHandshakeEpoch?.toLong() ?: epochSeconds())
                    }
                    // Lost a previously-good handshake. While yielding to a system
                    // VPN this is EXPECTED (our UDP rides the VPN); keep reporting
                    // Connected and let boringtun re-key on its own when traffic
                    // resumes — degrading/reconnecting would fight the VPN for
                    // nothing and hot-loop the supervisor. Otherwise allow a grace
                    // period to self-recover, then Failed(retryable) → reconnect.
                    everConnected -> {
                        if (vpnActive?.value == true) {
                            _state.value = TransportState.Connected(
                                port,
                                h.lastHandshakeEpoch?.toLong() ?: epochSeconds(),
                            )
                        } else if (now - lastOkMs >= RECONNECT_AFTER_MS) {
                            _state.value = TransportState.Failed(
                                "WireGuard handshake lost and didn't recover — reconnecting.",
                                retryable = true,
                            )
                            return@launch
                        } else {
                            _state.value = TransportState.Degraded("WireGuard handshake lost — recovering…")
                        }
                    }
                    // Initial connect, never handshaked yet. While a system VPN is
                    // up, absence of a handshake is expected (yielding) — stay
                    // Connecting instead of crying Degraded with a misleading
                    // "check your peer config" hint.
                    now - startMs > HANDSHAKE_WARN_MS -> {
                        // Never escalate while yielding, and never sit in Degraded
                        // forever: a handshake that can't complete within
                        // HANDSHAKE_FAIL_MS means the path is dead (classic case:
                        // the UDP socket was born under a system VPN that has
                        // since closed) — only a fresh driver+socket heals it.
                        if (vpnActive?.value == true) {
                            _state.value = TransportState.Connecting
                        } else if (now - startMs > HANDSHAKE_FAIL_MS) {
                            _state.value = TransportState.Failed(
                                "WireGuard handshake never completed — reconnecting with a fresh driver.",
                                retryable = true,
                            )
                            return@launch
                        } else {
                            _state.value = TransportState.Degraded(
                                "No WireGuard handshake yet. Check: the server has YOUR public key as a peer, " +
                                    "the endpoint host:port is reachable, and the peer public key is correct.",
                            )
                        }
                    }
                    else -> _state.value = TransportState.Connecting
                }
                delay(1_000)
            }
        }
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        handshakeJob?.cancel()
        handshakeJob = null
        core.stop()
        localSocksPort = 0
        _state.value = TransportState.Disconnected
    }

    override suspend fun probe(target: String): TransportHealth = withContext(Dispatchers.IO) {
        val h: CoreHealth = core.health()
        TransportHealth(
            handshakeOk = h.handshakeOk,
            rttMs = h.rttMs,
            // Only the WG modes ride an actual tunnel; Direct/SocksOnly do not.
            viaTunnel = config is TransportConfig.WgThenSocks || config is TransportConfig.WgOnly,
            lastHandshakeEpoch = h.lastHandshakeEpoch,
            txBytes = h.txBytes?.toLong(),
            rxBytes = h.rxBytes?.toLong(),
            localSocksPort = h.localSocksPort?.toInt(),
        )
    }

    override suspend fun diagnosticInfo(): String = withContext(Dispatchers.IO) {
        runCatching { core.transportInfo() }.getOrElse { "transport_info error: ${it.message}" }
    }

    private fun epochSeconds(): Long = System.currentTimeMillis() / 1000L

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val HANDSHAKE_WARN_MS = 12_000L

        /** Never-handshaked connects escalate to Failed(retryable) after this —
         *  a fresh driver+socket is the only cure for a dead network path
         *  (e.g. the socket was created under a since-closed system VPN). */
        const val HANDSHAKE_FAIL_MS = 60_000L

        /** Grace window for a lost handshake to self-recover before we force a
         *  full reconnect. Deliberately long: WG is UDP, session loss is routine
         *  (roam, server restart, idle expiry), and boringtun re-initiates and
         *  re-keys ON ITS OWN — full re-init is the last resort, not the first
         *  response. Instant escalation happens only on driverDead. */
        const val RECONNECT_AFTER_MS = 120_000L
    }
}
