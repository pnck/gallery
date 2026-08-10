package io.github.pnck.gallery.transport

import android.annotation.SuppressLint
import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import io.github.pnck.gallery.network.transport.Endpoint
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Discovers a WireGuard endpoint from a DNS **SRV** record — the "subscribe"
 * alternative to a hand-typed host:port. The server can move (dynamic IP, fail-
 * over) and clients pick up the change on the next connect, because the record is
 * resolved fresh each time.
 *
 * Plain **system DNS** is used (Android's [DnsResolver], API 29+): the tunnel
 * endpoint is not sensitive and needs no tamper-proof/authoritative lookup —
 * WireGuard's Noise handshake authenticates the peer by its public key, so a
 * spoofed address simply fails the handshake, it can't impersonate the peer.
 * System DNS also respects the active network's resolver (can see internal names)
 * and needs no third party. DoH is kept only as a fallback for API < 29.
 *
 * The lookup is deliberate pre-tunnel traffic and, in the DoH fallback, uses the
 * [AuthClient] bare client so it never carries a Bearer or depends on sign-in.
 */
class SrvEndpointResolver(
    private val client: OkHttpClient,
) {
    /**
     * Resolve [srvName] (e.g. "_wireguard._udp.example.com") to an [Endpoint].
     */
    suspend fun resolve(srvName: String): Endpoint {
        val name = srvName.trim()
        require(name.isNotBlank()) { "SRV name is required" }

        val records = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // System DNS first; fall back to DoH only if it errored or returned nothing.
            runCatching { querySystemDns(name) }.getOrDefault(emptyList())
                .ifEmpty { queryDoh(name) }
        } else {
            queryDoh(name)
        }
        return pickBest(records) ?: error("no SRV record for $name")
    }

    // ── System DNS (plain, respects the network's resolver) ─────────────────

    // rawQuery's nsType is annotated @QueryType (only A/AAAA in the SDK), but the
    // raw query genuinely accepts any RR type — SRV (33) works at runtime.
    @SuppressLint("WrongConstant")
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun querySystemDns(name: String): List<SrvRecord> =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            DnsResolver.getInstance().rawQuery(
                null, // default active network
                name,
                DnsResolver.CLASS_IN,
                TYPE_SRV,
                DnsResolver.FLAG_EMPTY,
                Runnable::run,
                signal,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        cont.resume(
                            if (rcode != 0) emptyList()
                            else runCatching { parseDnsMessage(answer) }.getOrDefault(emptyList()),
                        )
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        cont.resumeWithException(error)
                    }
                },
            )
        }

    // ── DoH fallback (API < 29, or if system DNS failed) ────────────────────

    private suspend fun queryDoh(name: String): List<SrvRecord> = withContext(Dispatchers.IO) {
        var lastError: String? = null
        for (provider in DOH_PROVIDERS) {
            val json = try {
                dohGet(provider, name)
            } catch (e: Exception) {
                lastError = e.message
                continue
            }
            val records = parseDohJson(json)
            if (records.isNotEmpty()) return@withContext records
        }
        lastError?.let { error(it) }
        emptyList()
    }

    private fun dohGet(dohUrl: String, name: String): String {
        val url = dohUrl.toHttpUrl().newBuilder()
            .addQueryParameter("name", name)
            .addQueryParameter("type", "SRV")
            .build()
        val request = Request.Builder().url(url).header("accept", "application/dns-json").build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("DoH lookup failed: HTTP ${resp.code}")
            resp.body.string()
        }
    }

    companion object {
        private const val TYPE_SRV = 33
        private val DOH_PROVIDERS = listOf(
            "https://dns.google/resolve",
            "https://cloudflare-dns.com/dns-query",
        )

        /** One parsed SRV record. RFC 2782 ordering: lowest priority, then highest weight. */
        internal data class SrvRecord(val priority: Int, val weight: Int, val port: Int, val target: String)

        internal fun pickBest(records: List<SrvRecord>): Endpoint? =
            records.sortedWith(compareBy({ it.priority }, { -it.weight })).firstOrNull()
                ?.let { Endpoint(it.target, it.port) }

        // ── DoH JSON (RFC 8484 JSON) ────────────────────────────────────────

        internal fun parseDohJson(json: String): List<SrvRecord> {
            val answers = JSONObject(json).optJSONArray("Answer") ?: return emptyList()
            val out = ArrayList<SrvRecord>(answers.length())
            for (i in 0 until answers.length()) {
                val ans = answers.optJSONObject(i) ?: continue
                if (ans.optInt("type") != TYPE_SRV) continue
                parseSrvText(ans.optString("data"))?.let { out.add(it) }
            }
            return out
        }

        /** DoH JSON SRV rdata is the text form "priority weight port target." */
        private fun parseSrvText(data: String?): SrvRecord? {
            if (data.isNullOrBlank()) return null
            val p = data.trim().split(Regex("\\s+"))
            if (p.size < 4) return null
            val priority = p[0].toIntOrNull() ?: return null
            val weight = p[1].toIntOrNull() ?: return null
            val port = p[2].toIntOrNull() ?: return null
            val target = p[3].trimEnd('.')
            return if (target.isBlank()) null else SrvRecord(priority, weight, port, target)
        }

        // ── Raw DNS wire message (RFC 1035) ─────────────────────────────────

        /** Parse SRV records out of a raw DNS response message. Best-effort; a
         *  malformed message yields whatever was parsed before the problem. */
        internal fun parseDnsMessage(msg: ByteArray): List<SrvRecord> {
            if (msg.size < 12) return emptyList()
            if (msg[3].toInt() and 0x0F != 0) return emptyList() // RCODE != 0
            val qd = u16(msg, 4)
            val an = u16(msg, 6)
            var off = 12
            repeat(qd) { off = skipName(msg, off) + 4 } // QNAME + QTYPE + QCLASS
            val out = ArrayList<SrvRecord>(an)
            repeat(an) {
                off = skipName(msg, off)
                val type = u16(msg, off)
                val rdLength = u16(msg, off + 8)
                val rdStart = off + 10
                if (type == TYPE_SRV && rdLength >= 6) {
                    val priority = u16(msg, rdStart)
                    val weight = u16(msg, rdStart + 2)
                    val port = u16(msg, rdStart + 4)
                    val target = readName(msg, rdStart + 6).first.trimEnd('.')
                    if (target.isNotBlank()) out.add(SrvRecord(priority, weight, port, target))
                }
                off = rdStart + rdLength
            }
            return out
        }

        private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

        /** Advance past a (possibly compressed) name, returning the offset after it. */
        private fun skipName(msg: ByteArray, start: Int): Int {
            var off = start
            while (true) {
                val len = msg[off].toInt() and 0xFF
                when {
                    len == 0 -> return off + 1
                    len and 0xC0 == 0xC0 -> return off + 2 // compression pointer ends the name here
                    else -> off += 1 + len
                }
            }
        }

        /** Read a (possibly compressed) name, following pointers. Returns (name, offsetAfter). */
        private fun readName(msg: ByteArray, start: Int): Pair<String, Int> {
            val sb = StringBuilder()
            var off = start
            var offsetAfter = -1
            var guard = 0
            while (guard++ < 128) {
                val len = msg[off].toInt() and 0xFF
                when {
                    len == 0 -> {
                        if (offsetAfter < 0) offsetAfter = off + 1
                        break
                    }
                    len and 0xC0 == 0xC0 -> {
                        if (offsetAfter < 0) offsetAfter = off + 2
                        off = ((len and 0x3F) shl 8) or (msg[off + 1].toInt() and 0xFF)
                    }
                    else -> {
                        for (i in 1..len) sb.append((msg[off + i].toInt() and 0xFF).toChar())
                        sb.append('.')
                        off += 1 + len
                    }
                }
            }
            return sb.toString() to (if (offsetAfter >= 0) offsetAfter else off)
        }
    }
}
