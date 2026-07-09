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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

/**
 * On-device network diagnostics for the debug build. Runs a staged reachability
 * probe against a target — DNS → TCP connect → HTTP(S) — **both directly and
 * through the tunnel**, so you can see exactly which layer fails and whether the
 * tunnel is the problem (e.g. WgOnly reaches nothing = server isn't forwarding/
 * NATing; direct works but tunnel doesn't = routing/exit issue).
 *
 * ICMP `ping`/`mtr` are intentionally absent: raw ICMP needs root, and ICMP can't
 * traverse a SOCKS/TCP tunnel anyway. The TCP-connect RTT below is the usable
 * equivalent ("TCP ping"), and the staged probe is the equivalent of `curl -v`.
 */
class NetworkDiagnostics @Inject constructor(
    private val controller: TransportController,
) {
    private val timeoutMs = 8_000

    /** Run the suite, streaming human-readable lines via [emit]. */
    suspend fun run(rawTarget: String, emit: (String) -> Unit) = withContext(Dispatchers.IO) {
        val url = normalize(rawTarget)
        if (url == null) {
            emit("✗ Could not parse target '$rawTarget' (try https://host or host:port)")
            return@withContext
        }
        val host = url.host
        val port = url.port
        val socks = controller.proxyFor(host) // non-null host:port when tunnel is up

        emit("=== Diagnostics for ${url} ===")
        emit("host=$host port=$port  tunnel=${if (socks != null) "127.0.0.1:${socks.port}" else "off"}")
        emit("")

        // 1) DNS (local resolver — what the direct path uses).
        emit("[1] DNS (local resolve)")
        val ips = runCatching {
            timed { InetAddress.getAllByName(host).map { it.hostAddress } }
        }
        ips.onSuccess { (addrs, ms) -> emit("    ✓ ${addrs.joinToString()}  (${ms} ms)") }
            .onFailure { emit("    ✗ ${it.message}") }
        emit("")

        // 2) TCP connect — direct, then via tunnel (SOCKS5, remote DNS).
        emit("[2] TCP connect $host:$port")
        emit("    direct:  ${tcpConnect(host, port, null)}")
        emit("    tunnel:  ${if (socks != null) tcpConnect(host, port, socks.port) else "skipped (tunnel off)"}")
        emit("")

        // 3) HTTP(S) — direct, then via tunnel (curl -v style summary).
        emit("[3] HTTP GET $url")
        emit("    -- direct --")
        httpProbe(url, null, emit)
        emit("    -- via tunnel --")
        if (socks != null) httpProbe(url, socks.port, emit) else emit("    skipped (tunnel off)")
        emit("")
        emit("=== done ===")
    }

    /** TCP handshake latency, or the error. Via SOCKS uses the proxy's remote DNS. */
    private fun tcpConnect(host: String, port: Int, socksPort: Int?): String = runCatching {
        val socket = if (socksPort != null) {
            Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        } else {
            Socket()
        }
        socket.use {
            val addr = if (socksPort != null) {
                InetSocketAddress.createUnresolved(host, port) // resolve at the SOCKS far end
            } else {
                InetSocketAddress(host, port)
            }
            val ms = timed { it.connect(addr, timeoutMs) }.second
            "✓ connected (${ms} ms)"
        }
    }.getOrElse { "✗ ${it.javaClass.simpleName}: ${it.message}" }

    private fun httpProbe(url: HttpUrl, socksPort: Int?, emit: (String) -> Unit) {
        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .apply {
                proxy(
                    if (socksPort != null) {
                        Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
                    } else {
                        Proxy.NO_PROXY
                    },
                )
            }
            .build()
        runCatching {
            val req = Request.Builder().url(url).header("user-agent", "gallery-diag/1").get().build()
            val started = System.nanoTime()
            client.newCall(req).execute().use { resp ->
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
        // Bare host or host:port → default to https.
        return "https://$t".toHttpUrlOrNull()
    }
}
