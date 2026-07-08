package io.github.pnck.gallery.transport

import io.github.pnck.gallery.network.transport.TransportConfig
import uniffi.gallery_transport.CoreConfig

/**
 * Maps the Kotlin [TransportConfig] (Transport §3.1) onto the Rust core's
 * [CoreConfig] FFI enum.
 *
 * The Rust core implements Direct dialing, an upstream-SOCKS5 chain, and the full
 * WgThenSocks path (boringtun+smoltcp, T-502). Only [TransportConfig.HttpOnly]
 * surfaces as an explicit failure: the core speaks SOCKS5 upstream only, and an
 * HTTP CONNECT proxy chain is a non-goal for the accelerator.
 */
internal fun TransportConfig.toCoreConfig(): CoreConfig = when (this) {
    is TransportConfig.Direct -> CoreConfig.Direct

    is TransportConfig.SocksOnly -> CoreConfig.SocksUpstream(
        host = endpoint.host,
        port = endpoint.port.toUShort(),
        username = auth?.username,
        password = auth?.password,
    )

    is TransportConfig.HttpOnly ->
        throw UnsupportedOperationException(
            "HttpOnly transport is not supported by the Rust core (SOCKS5 upstream only).",
        )

    is TransportConfig.WgThenSocks -> CoreConfig.WgThenSocks(
        privateKey = wg.privateKey,
        peerPublicKey = wg.peerPublicKey,
        presharedKey = wg.presharedKey,
        // The core resolves a domain endpoint once, directly, at start.
        endpoint = "${wg.endpoint.host}:${wg.endpoint.port}",
        interfaceAddresses = wg.interfaceAddresses,
        keepaliveSecs = wg.persistentKeepaliveSeconds.toUShort(),
        upstreamHost = upstreamSocks.host,
        upstreamPort = upstreamSocks.port.toUShort(),
        upstreamUsername = upstreamAuth?.username,
        upstreamPassword = upstreamAuth?.password,
    )
}
