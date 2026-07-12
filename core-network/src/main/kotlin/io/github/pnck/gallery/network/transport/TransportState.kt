package io.github.pnck.gallery.network.transport

/** Connection state exposed to the UI via TransportController (Transport Design §3.1). */
sealed interface TransportState {
    data object Disconnected : TransportState
    data object Connecting : TransportState
    data class Connected(val localSocksPort: Int, val lastHandshakeEpoch: Long) : TransportState

    /** Handshake stale / RTT abnormal, still usable. */
    data class Degraded(val reason: String) : TransportState
    data class Failed(val reason: String, val retryable: Boolean) : TransportState
}

/**
 * A point-in-time health snapshot, polled by the UI for live monitoring.
 * Byte counters + handshake epoch are populated only for WireGuard modes.
 */
data class TransportHealth(
    val handshakeOk: Boolean,
    val rttMs: Long?,
    val viaTunnel: Boolean,
    val lastHandshakeEpoch: Long? = null,
    val txBytes: Long? = null,
    val rxBytes: Long? = null,
    val localSocksPort: Int? = null,
)
