package io.github.pnck.gallery.transport

import io.github.pnck.gallery.network.transport.TransportConfig
import uniffi.gallery_transport.CoreConfig

/**
 * Maps the Kotlin [TransportConfig] (Transport §3.1) onto the Rust core's
 * [CoreConfig] FFI enum.
 *
 * Phase 1 of the Rust core (this build) implements only Direct dialing and an
 * upstream-SOCKS5 chain. The remaining modes surface as explicit failures rather
 * than silently degrading:
 *  - [TransportConfig.HttpOnly] — the core speaks SOCKS5 upstream only; an HTTP
 *    CONNECT proxy chain is not implemented (and is a non-goal for the accelerator).
 *  - [TransportConfig.WgThenSocks] — needs the userspace WireGuard tunnel (T-502
 *    phase 2). Until boringtun+smoltcp land, requesting it is a hard error so the
 *    UI never believes it is tunneling when it is not.
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

    is TransportConfig.WgThenSocks ->
        throw UnsupportedOperationException(
            "WgThenSocks requires the userspace WireGuard tunnel (T-502 phase 2), not yet built.",
        )
}
