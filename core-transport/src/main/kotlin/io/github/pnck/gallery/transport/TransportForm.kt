package io.github.pnck.gallery.transport

/** All transport form fields, so the ViewModel owns validation + config building. */
data class TransportForm(
    val wgEnabled: Boolean,
    val socksEnabled: Boolean,
    val privateKey: String,
    val peerPublicKey: String,
    val presharedKey: String,
    /** When true, [srvName] is resolved via SRV/DoH; otherwise [endpoint] is used verbatim. */
    val useSrv: Boolean,
    val endpoint: String,
    val srvName: String,
    val interfaceAddress: String,
    val dns: String,
    val keepaliveSecs: String,
    /** Tunnel MTU; blank/0 = core default (1280). */
    val mtu: String,
    val socksHost: String,
    val socksPort: String,
    val socksUser: String,
    val socksPass: String,
)
