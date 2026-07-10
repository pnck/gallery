package io.github.pnck.gallery.transport

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
 * A single [WgCore] handle is held for the process (single tunnel, Transport §6.4);
 * [start]/[stop] are idempotent-ish and serialized by the core's own lock.
 */
class NativeNetworkTransport(
    private val config: TransportConfig,
    private val scope: CoroutineScope,
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
        }
    }

    /** Poll the WG handshake and drive Connecting → Connected, with a helpful
     *  Degraded reason if it stays down (keys / endpoint / peer misconfig). */
    private fun monitorHandshake(port: Int) {
        _state.value = TransportState.Connecting
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            val startMs = System.currentTimeMillis()
            while (isActive) {
                val h = withContext(Dispatchers.IO) { core.health() }
                val elapsed = System.currentTimeMillis() - startMs
                _state.value = when {
                    h.handshakeOk -> TransportState.Connected(port, h.lastHandshakeEpoch?.toLong() ?: epochSeconds())
                    elapsed > HANDSHAKE_WARN_MS -> TransportState.Degraded(
                        "No WireGuard handshake yet. Check: the server has YOUR public key as a peer, " +
                            "the endpoint host:port is reachable, and the peer public key is correct.",
                    )
                    else -> TransportState.Connecting
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
    }
}
