package io.github.pnck.gallery.discovery

import io.github.pnck.gallery.di.AuthClient
import io.github.pnck.gallery.transport.TransportController
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject

/**
 * On-device network diagnostics for the debug build. Runs a staged reachability
 * probe against a target — DNS → TCP → HTTP(S).
 *
 * DNS policy (matches the app's): SRV/WG-endpoint discovery uses the system
 * resolver, but everything ELSE avoids the phone's local DNS — it resolves over
 * **DoH**, and when the tunnel is up the connection itself uses **remote DNS** at
 * the tunnel exit (SOCKS5, hostname passed unresolved). So the primary result is
 * the "via tunnel" one; a "phone network" line is shown only for comparison and
 * is expected to fail for tunnel-only targets.
 *
 * ICMP ping/mtr are intentionally absent (need root; can't cross a SOCKS/TCP
 * tunnel). TCP-connect RTT is the equivalent.
 */
class NetworkDiagnostics @Inject constructor(
    private val controller: TransportController,
    @AuthClient private val dohClient: OkHttpClient,
) {
    private val timeoutMs = 8_000

    suspend fun run(rawTarget: String, emit: (String) -> Unit) = withContext(Dispatchers.IO) {
        val url = normalize(rawTarget)
        if (url == null) {
            emit("✗ Could not parse target '$rawTarget' (try https://host or host:port)")
            return@withContext
        }
        val host = url.host
        val port = url.port
        val socks = controller.proxyFor(host) // non-null 127.0.0.1:P when tunnel is up
        val tunnelUp = socks != null

        emit("=== Diagnostics for $url ===")
        emit("host=$host port=$port  tunnel=${if (tunnelUp) "127.0.0.1:${socks!!.port}" else "OFF"}")
        emit("")

        // 1) DNS over DoH (no local resolver). Reference only — the tunnel resolves
        //    remotely at the exit, so these IPs aren't used for the tunnel path.
        emit("[1] DNS via DoH (reference; tunnel uses remote DNS at exit)")
        val ips = runCatching { timed { dohResolve(host) } }
        val dohIps = ips.getOrNull()?.first.orEmpty()
        ips.onSuccess { (addrs, ms) -> emit("    ✓ ${addrs.joinToString().ifEmpty { "(none)" }}  (${ms} ms)") }
            .onFailure { emit("    ✗ ${it.message}") }
        emit("")

        // 2) TCP connect. Tunnel first (the path that matters); phone network second.
        emit("[2] TCP connect $host:$port")
        if (tunnelUp) {
            emit("    tunnel (remote DNS): ${tcpViaTunnel(host, port, socks!!.port)}")
        } else {
            emit("    tunnel: OFF")
        }
        emit("    phone network (bypasses tunnel, DoH DNS): ${tcpDirect(dohIps, port)}")
        emit("")

        // 3) HTTP(S). Same ordering.
        emit("[3] HTTP GET $url")
        if (tunnelUp) {
            emit("    -- tunnel (remote DNS) --")
            httpProbe(url, tunnelPort = socks!!.port, dohIps = emptyList(), emit = emit)
        } else {
            emit("    -- tunnel: OFF --")
        }
        emit("    -- phone network (bypasses tunnel, DoH DNS) --")
        httpProbe(url, tunnelPort = null, dohIps = dohIps, emit = emit)
        emit("")
        emit("=== done ===")
    }

    // ── DoH A/AAAA resolution (no local DNS) ─────────────────────────────────

    private fun dohResolve(host: String): List<String> {
        // An IP literal needs no resolution.
        runCatching { InetAddress.getByName(host) }.getOrNull()?.let {
            if (host.any { c -> c.isDigit() } && (host.contains(':') || host.count { c -> c == '.' } == 3)) {
                return listOf(host)
            }
        }
        for (provider in DOH_PROVIDERS) {
            val ips = runCatching { dohQuery(provider, host) }.getOrDefault(emptyList())
            if (ips.isNotEmpty()) return ips
        }
        return emptyList()
    }

    private fun dohQuery(dohUrl: String, host: String): List<String> {
        fun q(type: String): List<String> {
            val url = dohUrl.toHttpUrl().newBuilder()
                .addQueryParameter("name", host).addQueryParameter("type", type).build()
            val req = Request.Builder().url(url).header("accept", "application/dns-json").build()
            val json = dohClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return emptyList(); r.body.string()
            }
            val answers = JSONObject(json).optJSONArray("Answer") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until answers.length()) {
                val a = answers.optJSONObject(i) ?: continue
                val t = a.optInt("type")
                if (t == 1 || t == 28) a.optString("data").takeIf { it.isNotBlank() }?.let(out::add)
            }
            return out
        }
        return (q("A") + q("AAAA")).distinct()
    }

    // ── Probes ───────────────────────────────────────────────────────────────

    private fun tcpViaTunnel(host: String, port: Int, socksPort: Int): String = runCatching {
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))).use {
            val ms = timed { it.connect(InetSocketAddress.createUnresolved(host, port), timeoutMs) }.second
            "✓ connected (${ms} ms)"
        }
    }.getOrElse { "✗ ${it.javaClass.simpleName}: ${it.message}" }

    private fun tcpDirect(dohIps: List<String>, port: Int): String {
        val ip = dohIps.firstOrNull() ?: return "skipped (no DoH address)"
        return runCatching {
            Socket().use {
                val ms = timed { it.connect(InetSocketAddress(ip, port), timeoutMs) }.second
                "✓ connected to $ip (${ms} ms)"
            }
        }.getOrElse { "✗ ${it.javaClass.simpleName}: ${it.message}" }
    }

    private fun httpProbe(url: HttpUrl, tunnelPort: Int?, dohIps: List<String>, emit: (String) -> Unit) {
        val builder = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (tunnelPort != null) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", tunnelPort)))
        } else {
            builder.proxy(Proxy.NO_PROXY)
            // Resolve via DoH, not the phone's local resolver.
            if (dohIps.isNotEmpty()) {
                builder.dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        dohIps.map { InetAddress.getByName(it) }
                })
            }
        }
        runCatching {
            val req = Request.Builder().url(url).header("user-agent", "gallery-diag/1").get().build()
            val started = System.nanoTime()
            builder.build().newCall(req).execute().use { resp ->
                val ms = (System.nanoTime() - started) / 1_000_000
                emit("    ✓ HTTP ${resp.code} ${resp.message}  ${resp.protocol}  (${ms} ms)")
                resp.handshake?.let { emit("    TLS ${it.tlsVersion} ${it.cipherSuite}") }
                listOf("server", "content-type", "alt-svc").forEach { h ->
                    resp.header(h)?.let { emit("    $h: $it") }
                }
            }
        }.onFailure { emit("    ✗ ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val v = block()
        return v to (System.nanoTime() - start) / 1_000_000
    }

    private fun normalize(raw: String): HttpUrl? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        t.toHttpUrlOrNull()?.let { return it }
        return "https://$t".toHttpUrlOrNull()
    }

    private companion object {
        val DOH_PROVIDERS = listOf("https://dns.google/resolve", "https://cloudflare-dns.com/dns-query")
    }
}
