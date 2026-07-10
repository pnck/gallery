package io.github.pnck.gallery.transport

import uniffi.gallery_transport.deriveWireguardPublicKey
import uniffi.gallery_transport.generateWireguardKeypair

/** A freshly generated WireGuard keypair (base64), for the config UI. */
data class WgKeypair(val privateKey: String, val publicKey: String)

/**
 * WireGuard helper utilities backed by the Rust core, so consumers never touch
 * the generated `uniffi.*` package directly (invariant #8 keeps that internal).
 */
object WireguardTools {
    /** Generate a keypair (like `wg genkey` / `wg pubkey`). No native tunnel needed. */
    fun generateKeypair(): WgKeypair =
        generateWireguardKeypair().let { WgKeypair(it.privateKey, it.publicKey) }

    /** Derive the public key for a private key (= `wg pubkey`), "" if invalid. */
    fun derivePublicKey(privateKey: String): String = deriveWireguardPublicKey(privateKey.trim())
}
