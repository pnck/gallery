package io.github.pnck.gallery.network.transport

/** Transport endpoint (host + port). */
data class Endpoint(val host: String, val port: Int)

/** Optional proxy credentials. Stored in SecureStore only — never logged (PRD §8.4.6). */
data class Cred(val username: String, val password: String)

/**
 * wg-quick semantics for the userspace WireGuard core (PRD §8.4.3).
 * Private key material must only ever transit process memory (Transport Design §7).
 */
data class WgConfig(
    val privateKey: String,
    val peerPublicKey: String,
    val presharedKey: String?,
    val endpoint: Endpoint,
    /** The tunnel-interior interface address(es), e.g. "10.0.0.2/32" (wg-quick `Address`). */
    val interfaceAddresses: List<String>,
    val dns: List<String>,
    val persistentKeepaliveSeconds: Int = 25,
)

/** Selectable transport modes (PRD §8.4.3, Transport Design §3.1). */
sealed interface TransportConfig {
    data object Direct : TransportConfig

    data class SocksOnly(val endpoint: Endpoint, val auth: Cred?) : TransportConfig

    /** WireGuard tunnel only — dial out the WG peer, no upstream SOCKS chain. */
    data class WgOnly(val wg: WgConfig) : TransportConfig

    /** Primary scenario: WireGuard into the home/office LAN, then the LAN SOCKS accelerator. */
    data class WgThenSocks(
        val wg: WgConfig,
        val upstreamSocks: Endpoint,
        /** Optional auth for the in-tunnel upstream SOCKS5 (RFC 1929). */
        val upstreamAuth: Cred? = null,
    ) : TransportConfig
}
