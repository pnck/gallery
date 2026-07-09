package io.github.pnck.gallery.transport

import io.github.pnck.gallery.network.transport.TransportConfig
import io.github.pnck.gallery.network.transport.WgConfig
import uniffi.gallery_transport.CoreConfig
import uniffi.gallery_transport.WgSettings

/**
 * Maps the Kotlin [TransportConfig] (Transport §3.1) onto the Rust core's
 * [CoreConfig] FFI enum. WireGuard and the upstream SOCKS5 are independent, so the
 * four combinations map to Direct / SocksUpstream / WgOnly / WgThenSocks.
 *
 * Only [TransportConfig.HttpOnly] surfaces as an explicit failure: the core speaks
 * SOCKS5 upstream only, and an HTTP CONNECT proxy chain is a non-goal.
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

    is TransportConfig.WgOnly -> CoreConfig.WgOnly(wg = wg.toWgSettings())

    is TransportConfig.WgThenSocks -> CoreConfig.WgThenSocks(
        wg = wg.toWgSettings(),
        upstreamHost = upstreamSocks.host,
        upstreamPort = upstreamSocks.port.toUShort(),
        upstreamUsername = upstreamAuth?.username,
        upstreamPassword = upstreamAuth?.password,
    )
}

private fun WgConfig.toWgSettings(): WgSettings = WgSettings(
    privateKey = privateKey,
    peerPublicKey = peerPublicKey,
    presharedKey = presharedKey,
    // The core resolves a domain endpoint once, directly, at start.
    endpoint = "${endpoint.host}:${endpoint.port}",
    interfaceAddresses = interfaceAddresses,
    keepaliveSecs = persistentKeepaliveSeconds.toUShort(),
)
