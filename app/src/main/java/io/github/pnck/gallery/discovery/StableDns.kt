package io.github.pnck.gallery.discovery

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import javax.inject.Inject
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A DNS resolver that resolves A records against 1.1.1.1.
 *
 * DNS follows the tunnel (the app's model): when a SOCKS [Proxy] is given (tunnel
 * up), the query goes **through the tunnel** as plain DNS-over-TCP to 1.1.1.1:53
 * — the tunnel already provides encryption, and this is what actual app traffic
 * does. Only the tunnel-off path resolves directly, and then over DoT (:853) then
 * plain TCP (:53), by IP, to survive blocked DoH.
 */
class StableDns @Inject constructor() {

    /** Resolve [host] to IP strings. Through [viaSocks] when set (tunnel), else direct. */
    fun resolve(host: String, viaSocks: Proxy? = null, timeoutMs: Int = 6_000): List<String> {
        if (isIpLiteral(host)) return listOf(host)
        return if (viaSocks != null) {
            // Through the tunnel: plain DNS-over-TCP (the tunnel encrypts).
            runCatching { resolveTcp(host, timeoutMs, viaSocks) }.getOrDefault(emptyList())
        } else {
            runCatching { resolveDot(host, timeoutMs) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: runCatching { resolveTcp(host, timeoutMs, null) }.getOrDefault(emptyList())
        }
    }

    /** DNS-over-TLS to 1.1.1.1:853 (direct only; cert has an IP SAN for 1.1.1.1). */
    private fun resolveDot(host: String, timeoutMs: Int): List<String> {
        val plain = Socket()
        plain.connect(InetSocketAddress(RESOLVER_IP, 853), timeoutMs)
        plain.soTimeout = timeoutMs
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(plain, RESOLVER_IP, 853, true) as SSLSocket
        ssl.sslParameters = ssl.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
            serverNames = listOf(javax.net.ssl.SNIHostName(RESOLVER_SNI))
        }
        return ssl.use { it.startHandshake(); queryOverStream(it, host) }
    }

    /** Plain DNS-over-TCP to 1.1.1.1:53, direct or through a SOCKS [proxy] (the tunnel). */
    private fun resolveTcp(host: String, timeoutMs: Int, proxy: Proxy?): List<String> {
        val socket = if (proxy != null) Socket(proxy) else Socket()
        val addr = if (proxy != null) {
            InetSocketAddress.createUnresolved(RESOLVER_IP, 53) // let the tunnel dial it
        } else {
            InetSocketAddress(RESOLVER_IP, 53)
        }
        socket.connect(addr, timeoutMs)
        socket.soTimeout = timeoutMs
        return socket.use { queryOverStream(it, host) }
    }

    /** Send a length-prefixed A query and parse the length-prefixed answer. */
    private fun queryOverStream(socket: Socket, host: String): List<String> {
        val query = buildAQuery(host)
        val os = socket.getOutputStream()
        os.write((query.size ushr 8) and 0xFF)
        os.write(query.size and 0xFF)
        os.write(query)
        os.flush()

        val ins = socket.getInputStream()
        val hi = ins.read(); val lo = ins.read()
        if (hi < 0 || lo < 0) return emptyList()
        val len = (hi shl 8) or lo
        val resp = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = ins.read(resp, read, len - read)
            if (n < 0) break
            read += n
        }
        return parseAddrs(resp)
    }

    private fun buildAQuery(host: String): ByteArray {
        val out = ByteArrayOutputStream()
        val id = host.hashCode() and 0xFFFF
        out.write(id ushr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00) // flags: recursion desired
        out.write(0x00); out.write(0x01) // QDCOUNT = 1
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        out.write(0x00); out.write(0x00)
        for (label in host.trim('.').split('.')) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(0x00); out.write(0x01)   // QTYPE = A
        out.write(0x00); out.write(0x01)   // QCLASS = IN
        return out.toByteArray()
    }

    private fun parseAddrs(msg: ByteArray): List<String> {
        if (msg.size < 12) return emptyList()
        if (msg[3].toInt() and 0x0F != 0) return emptyList() // RCODE != 0
        val qd = u16(msg, 4); val an = u16(msg, 6)
        var off = 12
        repeat(qd) { off = skipName(msg, off) + 4 }
        val out = ArrayList<String>()
        repeat(an) {
            off = skipName(msg, off)
            val type = u16(msg, off)
            val rdLen = u16(msg, off + 8)
            val rd = off + 10
            when {
                type == 1 && rdLen == 4 ->
                    out.add("${msg[rd].toInt() and 0xFF}.${msg[rd + 1].toInt() and 0xFF}." +
                        "${msg[rd + 2].toInt() and 0xFF}.${msg[rd + 3].toInt() and 0xFF}")
                type == 28 && rdLen == 16 ->
                    out.add(InetAddress.getByAddress(msg.copyOfRange(rd, rd + 16)).hostAddress ?: "")
            }
            off = rd + rdLen
        }
        return out.filter { it.isNotBlank() }
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun skipName(msg: ByteArray, start: Int): Int {
        var off = start
        while (true) {
            val len = msg[off].toInt() and 0xFF
            when {
                len == 0 -> return off + 1
                len and 0xC0 == 0xC0 -> return off + 2
                else -> off += 1 + len
            }
        }
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || (host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' })

    private companion object {
        const val RESOLVER_IP = "1.1.1.1"
        const val RESOLVER_SNI = "one.one.one.one"
    }
}
