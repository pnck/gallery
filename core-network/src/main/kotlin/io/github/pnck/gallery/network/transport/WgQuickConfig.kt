package io.github.pnck.gallery.network.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

        /** Detect a ZIP archive by its magic bytes. */
        fun isZip(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
                bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

        /**
         * The official app's "export tunnels to zip": one `.conf` per entry.
         * We have a single tunnel, so the zip holds one entry named after it.
         */
        fun toZipBytes(entryName: String, config: WgQuickConfig): ByteArray {
            val name = entryName.removeSuffix(".conf") + ".conf"
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(config.serialize().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            return out.toByteArray()
        }

        /**
         * Parse EITHER a bare `.conf` text OR an official-app zip (first `.conf`
         * entry wins — our client manages a single tunnel). Returns the config
         * and, for zips, the entry name it came from (null for bare conf).
         * Throws [IllegalArgumentException] on malformed input.
         */
        fun parseAny(bytes: ByteArray): Pair<WgQuickConfig, String?> {
            if (isZip(bytes)) {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && entry.name.endsWith(".conf", ignoreCase = true)) {
                            val text = zip.readBytes().toString(Charsets.UTF_8)
                            return parse(text) to entry.name
                        }
                    }
                }
                throw IllegalArgumentException("Zip contains no .conf entry")
            }
            return parse(bytes.toString(Charsets.UTF_8)) to null
        }
    }
}
