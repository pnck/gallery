package io.github.pnck.gallery.discovery

import io.github.pnck.gallery.di.AuthClient
import io.github.pnck.gallery.network.transport.Endpoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Discovers a WireGuard endpoint from a DNS **SRV** record — the "subscribe"
 * alternative to a hand-typed host:port. The server can move (dynamic IP, fail-
 * over) and clients pick up the change on the next connect, because the SRV
 * record is resolved fresh each time.
 *
 * SRV lookups happen over **DoH** (DNS over HTTPS, RFC 8484 JSON), so we don't
 * depend on Android's opaque system-DNS discovery and it works uniformly from
 * minSdk 26. The query is deliberate pre-tunnel traffic (the endpoint is public
 * and must be reachable before the tunnel exists), and it uses the [AuthClient]
 * bare client so it never carries a Bearer header or depends on sign-in state.
 */
class SrvEndpointResolver @Inject constructor(
    @AuthClient private val client: OkHttpClient,
) {
    /** One parsed SRV record. RFC 2782 ordering: lowest priority wins, then highest weight. */
    private data class SrvRecord(val priority: Int, val weight: Int, val port: Int, val target: String)

    /**
     * Resolve [srvName] (e.g. "_wireguard._udp.example.com") to an [Endpoint].
     * Tries each DoH provider in turn until one returns a usable SRV record.
     */
    suspend fun resolve(srvName: String): Endpoint = withContext(Dispatchers.IO) {
        val name = srvName.trim()
        require(name.isNotBlank()) { "SRV name is required" }

        var lastError: String? = null
        for (provider in DOH_PROVIDERS) {
            val json = try {
                query(provider, name)
            } catch (e: Exception) {
                lastError = e.message
                continue
            }
            bestEndpoint(json)?.let { return@withContext it }
            lastError = "no SRV record for $name"
        }
        error(lastError ?: "SRV lookup failed for $name")
    }

    private fun query(dohUrl: String, name: String): String {
        val url = dohUrl.toHttpUrl().newBuilder()
            .addQueryParameter("name", name)
            .addQueryParameter("type", "SRV")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("accept", "application/dns-json")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("DoH lookup failed: HTTP ${resp.code}")
            resp.body.string()
        }
    }

    companion object {
        private const val TYPE_SRV = 33
        // JSON DoH endpoints (same response shape). Tried in order for resilience.
        private val DOH_PROVIDERS = listOf(
            "https://dns.google/resolve",
            "https://cloudflare-dns.com/dns-query",
        )

        /**
         * Parse a DoH JSON answer set and pick the best SRV target (RFC 2782:
         * lowest priority, then highest weight). Pure + testable — no network.
         */
        internal fun bestEndpoint(json: String): Endpoint? {
            val answers = JSONObject(json).optJSONArray("Answer") ?: return null
            val records = ArrayList<SrvRecord>(answers.length())
            for (i in 0 until answers.length()) {
                val ans = answers.optJSONObject(i) ?: continue
                if (ans.optInt("type") != TYPE_SRV) continue // 33 = SRV
                parseSrvData(ans.optString("data"))?.let { records.add(it) }
            }
            val best = records.sortedWith(compareBy({ it.priority }, { -it.weight })).firstOrNull()
            return best?.let { Endpoint(it.target, it.port) }
        }

        /** SRV rdata is "priority weight port target." (trailing dot on the target). */
        private fun parseSrvData(data: String?): SrvRecord? {
            if (data.isNullOrBlank()) return null
            val parts = data.trim().split(Regex("\\s+"))
            if (parts.size < 4) return null
            val priority = parts[0].toIntOrNull() ?: return null
            val weight = parts[1].toIntOrNull() ?: return null
            val port = parts[2].toIntOrNull() ?: return null
            val target = parts[3].trimEnd('.')
            if (target.isBlank()) return null
            return SrvRecord(priority, weight, port, target)
        }
    }
}
