package io.github.pnck.gallery.discovery

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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/**
 * On-device network diagnostics for the debug build. Runs a staged reachability
 * probe against a target — DNS → TCP → HTTP(S).
 *
 * Reference DNS resolution uses [StableDns] (DoT/TCP to 1.1.1.1) so it works even
 * where DoH (dns.google / cloudflare-dns.com over HTTPS) is blocked/SNI-filtered.
 *
 * The tunnel TCP probe is split into by-name (remote DNS at the exit) and by-IP
 * (bypasses remote DNS), so a DNS failure is distinguishable from an unreachable
 * target — the decisive test for "does the tunnel actually reach the internet".
 *
 * ICMP ping/mtr are intentionally absent (need root; can't cross a SOCKS/TCP
 * tunnel). TCP-connect RTT is the equivalent.
 */
class NetworkDiagnostics @Inject constructor(
    private val controller: TransportController,
    private val stableDns: StableDns,
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
        val socksProxy = socks?.let { Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", it.port)) }

        emit("=== Diagnostics for $url ===")
        emit("host=$host port=$port  tunnel=${socks?.let { "127.0.0.1:${it.port}" } ?: "OFF"}")
        emit("")

        // 1) DNS — through the tunnel when it's up (that's how app traffic resolves),
        //    direct only when off. Uses 1.1.1.1 as a known resolver via the tunnel;
        //    the by-name probe below separately exercises YOUR configured DNS.
        emit(if (tunnelUp) "[1] DNS via 1.1.1.1 THROUGH the tunnel" else "[1] DNS direct (DoT/TCP 1.1.1.1)")
        val ips = runCatching { timed { stableDns.resolve(host, viaSocks = socksProxy) } }
        val refIps = ips.getOrNull()?.first.orEmpty()
        ips.onSuccess { (addrs, ms) -> emit("    ${if (addrs.isEmpty()) "✗ no answer" else "✓ ${addrs.joinToString()}"}  (${ms} ms)") }
            .onFailure { emit("    ✗ ${it.message}") }
        val refIp = refIps.firstOrNull()
        emit("")

        // 2) TCP connect — everything through the tunnel when up. by-name uses YOUR
        //    configured DNS (WgOnly → WG DNS; Wg+SOCKS → upstream remote DNS); by-IP
        //    uses the [1] IP so a DNS failure is distinguishable from unreachability.
        emit("[2] TCP connect $host:$port")
        if (socks != null) {
            emit("    tunnel by-name (your configured DNS): ${tcpViaTunnel(host, port, socks.port)}")
            emit("    tunnel by-IP  (no DNS)              : ${if (refIp != null) tcpViaTunnel(refIp, port, socks.port) else "skipped (no IP from [1])"}")
        } else {
            emit("    tunnel: OFF")
            emit("    phone by-IP: ${if (refIp != null) tcpDirect(refIp, port) else "skipped (no IP from [1])"}")
        }
        emit("")

        // 3) HTTP(S) through the tunnel (by-name) when up.
        emit("[3] HTTP GET $url")
        if (socks != null) {
            httpProbe(url, tunnelPort = socks.port, pinIps = emptyList(), emit = emit)
        } else {
            httpProbe(url, tunnelPort = null, pinIps = refIps, emit = emit)
        }
        emit("")
        emit("=== done ===")
    }

    // ── Probes ───────────────────────────────────────────────────────────────

    private fun tcpViaTunnel(host: String, port: Int, socksPort: Int): String = runCatching {
        Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))).use {
            val ms = timed { it.connect(InetSocketAddress.createUnresolved(host, port), timeoutMs) }.second
            "✓ connected (${ms} ms)"
        }
    }.getOrElse { "✗ ${it.javaClass.simpleName}: ${it.message}" }

    private fun tcpDirect(ip: String, port: Int): String = runCatching {
        Socket().use {
            val ms = timed { it.connect(InetSocketAddress(ip, port), timeoutMs) }.second
            "✓ connected to $ip (${ms} ms)"
        }
    }.getOrElse { "✗ ${it.javaClass.simpleName}: ${it.message}" }

    private fun httpProbe(url: HttpUrl, tunnelPort: Int?, pinIps: List<String>, emit: (String) -> Unit) {
        val builder = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (tunnelPort != null) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", tunnelPort)))
        } else {
            builder.proxy(Proxy.NO_PROXY)
            // Pin the stable-DNS IPs so we don't depend on the phone's local resolver.
            if (pinIps.isNotEmpty()) {
                builder.dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        pinIps.map { InetAddress.getByName(it) }
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
}
