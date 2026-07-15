package io.github.pnck.gallery.network.transport

/**
 * A parsed `wg-quick` `.conf` — the standard WireGuard config format that the official
 * WireGuard app imports/exports (its "export to zip" bundles these). This lets our
 * built-in client interoperate: export our tunnel to a `.conf` the official app reads,
 * and import a `.conf` the official app produced.
 *
 * Pure (JVM) so it's unit-tested without Android. We route all traffic through the
 * tunnel, so [allowedIps] defaults to a full route on export and is preserved on import
 * (round-trip) but not otherwise used by our core.
 */
data class WgQuickConfig(
    val privateKey: String,
    /** `[Interface] Address`, comma-joined (e.g. "10.0.0.2/32"). */
    val address: String,
    /** `[Interface] DNS`, comma-joined; may be blank. */
    val dns: String,
    val mtu: Int?,
    val peerPublicKey: String,
    val presharedKey: String?,
    /** `[Peer] Endpoint` as host:port. */
    val endpoint: String,
    /** `[Peer] AllowedIPs`, comma-joined. */
    val allowedIps: String,
    val persistentKeepalive: Int?,
) {
    fun serialize(): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        if (address.isNotBlank()) appendLine("Address = $address")
        if (dns.isNotBlank()) appendLine("DNS = $dns")
        mtu?.let { appendLine("MTU = $it") }
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = $peerPublicKey")
        presharedKey?.takeIf { it.isNotBlank() }?.let { appendLine("PresharedKey = $it") }
        if (endpoint.isNotBlank()) appendLine("Endpoint = $endpoint")
        appendLine("AllowedIPs = ${allowedIps.ifBlank { DEFAULT_ALLOWED_IPS }}")
        persistentKeepalive?.let { appendLine("PersistentKeepalive = $it") }
    }

    companion object {
        const val DEFAULT_ALLOWED_IPS = "0.0.0.0/0, ::/0"

        /**
         * Parse a `wg-quick` `.conf`. Keys are case-insensitive; comments (`#`) and blank
         * lines are ignored. Throws [IllegalArgumentException] on a malformed config.
         */
        fun parse(text: String): WgQuickConfig {
            var section = ""
            val iface = mutableMapOf<String, String>()
            val peer = mutableMapOf<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                when {
                    line.equals("[Interface]", ignoreCase = true) -> section = "interface"
                    line.equals("[Peer]", ignoreCase = true) -> section = "peer"
                    line.startsWith("[") -> section = "" // unknown section — skip its keys
                    else -> {
                        val key = line.substringBefore('=', "").trim().lowercase()
                        val value = line.substringAfter('=', "").trim()
                        if (key.isEmpty()) continue
                        when (section) {
                            "interface" -> iface[key] = value
                            "peer" -> peer[key] = value
                        }
                    }
                }
            }
            val privateKey = iface["privatekey"]
                ?: throw IllegalArgumentException("Missing [Interface] PrivateKey")
            val peerPublicKey = peer["publickey"]
                ?: throw IllegalArgumentException("Missing [Peer] PublicKey")
            return WgQuickConfig(
                privateKey = privateKey,
                address = iface["address"].orEmpty(),
                dns = iface["dns"].orEmpty(),
                mtu = iface["mtu"]?.trim()?.toIntOrNull(),
                peerPublicKey = peerPublicKey,
                presharedKey = peer["presharedkey"]?.takeIf { it.isNotBlank() },
                endpoint = peer["endpoint"].orEmpty(),
                allowedIps = peer["allowedips"].orEmpty().ifBlank { DEFAULT_ALLOWED_IPS },
                persistentKeepalive = peer["persistentkeepalive"]?.trim()?.toIntOrNull(),
            )
        }
    }
}
