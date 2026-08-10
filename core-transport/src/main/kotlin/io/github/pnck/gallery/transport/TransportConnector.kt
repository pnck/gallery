package io.github.pnck.gallery.transport


import io.github.pnck.gallery.network.transport.Cred
import io.github.pnck.gallery.network.transport.Endpoint
import io.github.pnck.gallery.network.transport.TransportConfig
import io.github.pnck.gallery.network.transport.WgConfig
import io.github.pnck.gallery.transport.TransportController
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Owns the tunnel lifecycle so it can be driven from both the Transport screen
 * (explicit connect/disconnect) and app launch (silent auto-reconnect). WireGuard
 * has no long-lived connection to keep alive — reconnecting is cheap — so if the
 * user last left it ON we bring it back up on start (EPIC-5).
 */
class TransportConnector(
    private val controller: TransportController,
    private val configStore: TransportConfigStore,
    private val srvResolver: SrvEndpointResolver,
    private val okHttpClient: OkHttpClient,
) {
    private val reconnectAttempted = AtomicBoolean(false)

    /** Explicit user connect: persist the form, mark active, bring the tunnel up. */
    suspend fun connect(form: TransportForm, publicKey: String) {
        withContext(Dispatchers.IO) {
            configStore.save(form, publicKey)
            configStore.setActive(true)
        }
        val config = form.toConfig()
        controller.connect(config)
        okHttpClient.connectionPool.evictAll()
    }

    suspend fun disconnect() {
        controller.disconnect()
        withContext(Dispatchers.IO) { configStore.setActive(false) }
        okHttpClient.connectionPool.evictAll()
    }

    /**
     * App-launch reconnect: once per process, if the user left the tunnel on and a
     * config is saved, silently reconnect. Failures are swallowed (the user can
     * retry from the Transport screen).
     */
    suspend fun reconnectIfActive() {
        if (!reconnectAttempted.compareAndSet(false, true)) return
        val saved = withContext(Dispatchers.IO) {
            if (configStore.isActive()) configStore.load() else null
        } ?: return
        runCatching {
            controller.connect(saved.form.toConfig())
            okHttpClient.connectionPool.evictAll()
        }
    }

    // ── form → TransportConfig (SRV lookup is a network call) ───────────────

    private suspend fun TransportForm.toConfig(): TransportConfig {
        val wg = if (wgEnabled) buildWg() else null
        val socks = if (socksEnabled) buildSocksEndpoint() else null
        val socksAuth = if (socksEnabled) socksCred() else null
        return when {
            wg != null && socks != null -> TransportConfig.WgThenSocks(wg, socks, socksAuth)
            wg != null -> TransportConfig.WgOnly(wg)
            socks != null -> TransportConfig.SocksOnly(socks, socksAuth)
            else -> error("Enable WireGuard and/or the upstream SOCKS5 first")
        }
    }

    private suspend fun TransportForm.buildWg(): WgConfig {
        require(privateKey.isNotBlank()) { "WireGuard private key is required" }
        require(peerPublicKey.isNotBlank()) { "Peer public key is required" }
        require(interfaceAddress.isNotBlank()) { "Interface address is required (e.g. 10.0.0.2/32)" }
        val resolved: Endpoint = if (useSrv) {
            srvResolver.resolve(srvName)
        } else {
            val (host, port) = splitHostPort(endpoint, "WireGuard endpoint")
            Endpoint(host, port)
        }
        return WgConfig(
            privateKey = privateKey.trim(),
            peerPublicKey = peerPublicKey.trim(),
            presharedKey = presharedKey.trim().ifBlank { null },
            endpoint = resolved,
            interfaceAddresses = listOf(interfaceAddress.trim()),
            dns = dns.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotBlank() },
            persistentKeepaliveSeconds = keepaliveSecs.trim().toIntOrNull() ?: 25,
            mtu = mtu.trim().toIntOrNull() ?: 0,
        )
    }

    private fun TransportForm.buildSocksEndpoint(): Endpoint {
        require(socksHost.isNotBlank()) { "Upstream SOCKS host is required" }
        val port = socksPort.trim().toIntOrNull() ?: error("Upstream SOCKS port must be a number")
        return Endpoint(socksHost.trim(), port)
    }

    private fun TransportForm.socksCred(): Cred? =
        if (socksUser.isBlank()) null else Cred(socksUser.trim(), socksPass)

    private fun splitHostPort(value: String, label: String): Pair<String, Int> {
        val v = value.trim()
        val idx = v.lastIndexOf(':')
        require(idx > 0 && idx < v.length - 1) { "$label must be host:port" }
        val port = v.substring(idx + 1).toIntOrNull() ?: error("$label port must be a number")
        return v.substring(0, idx) to port
    }
}
