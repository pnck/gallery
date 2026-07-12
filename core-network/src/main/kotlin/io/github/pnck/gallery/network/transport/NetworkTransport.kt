package io.github.pnck.gallery.network.transport

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic transport contract (Transport Design §3.2).
 *
 * A transport IS an [OutboundRouter]: when connected it routes hosts to the local
 * loopback SOCKS5 entry exposed by the userspace WireGuard core; when disconnected
 * it returns `null` and the shared client falls back to NO_PROXY.
 *
 * Implementations planned (EPIC-5):
 *  - T-501 SocksOnlyTransport (plain upstream SOCKS5 proxy)
 *  - T-502 WgThenSocksTransport (Rust boringtun+smoltcp core via UniFFI/Gobley)
 */
interface NetworkTransport : OutboundRouter {
    val state: StateFlow<TransportState>

    suspend fun start()

    suspend fun stop()

    /** Handshake check + RTT probe against a representative target (PRD §8.4.7). */
    suspend fun probe(target: String = "www.googleapis.com"): TransportHealth

    /** Ground-truth dump of the config the transport is actually running with. */
    suspend fun diagnosticInfo(): String
}
