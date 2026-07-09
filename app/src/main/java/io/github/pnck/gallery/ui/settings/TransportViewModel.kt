package io.github.pnck.gallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.network.transport.Cred
import io.github.pnck.gallery.network.transport.Endpoint
import io.github.pnck.gallery.network.transport.TransportConfig
import io.github.pnck.gallery.network.transport.TransportState
import io.github.pnck.gallery.network.transport.WgConfig
import io.github.pnck.gallery.discovery.NetworkDiagnostics
import io.github.pnck.gallery.discovery.SrvEndpointResolver
import io.github.pnck.gallery.transport.TransportController
import io.github.pnck.gallery.network.transport.TransportHealth
import io.github.pnck.gallery.transport.WgKeypair
import io.github.pnck.gallery.transport.WireguardTools
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

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
    val socksHost: String,
    val socksPort: String,
    val socksUser: String,
    val socksPass: String,
)

/**
 * Debug entry point for the transport layer (EPIC-5). WireGuard and the upstream
 * SOCKS5 are independent toggles → Direct / SocksOnly / WgOnly / WgThenSocks.
 * There is no automatic enablement yet, so this is the only way to exercise the
 * tunnel on-device.
 */
@HiltViewModel
class TransportViewModel @Inject constructor(
    private val controller: TransportController,
    private val okHttpClient: OkHttpClient,
    private val srvResolver: SrvEndpointResolver,
    private val configStore: TransportConfigStore,
    private val diagnostics: NetworkDiagnostics,
) : ViewModel() {

    val transportState: StateFlow<TransportState> =
        controller.state.stateIn(viewModelScope, SharingStarted.Eagerly, controller.state.value)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Live health snapshot, refreshed ~1 Hz while connected (transfer bytes, handshake). */
    private val _health = MutableStateFlow<TransportHealth?>(null)
    val health: StateFlow<TransportHealth?> = _health.asStateFlow()

    /** The persisted config to prefill the form with; null until loaded (off-main). */
    private val _savedForm = MutableStateFlow<SavedTransport?>(null)
    val savedForm: StateFlow<SavedTransport?> = _savedForm.asStateFlow()

    /** Streaming diagnostics output (debug tool). */
    private val _diagOutput = MutableStateFlow("")
    val diagOutput: StateFlow<String> = _diagOutput.asStateFlow()
    private val _diagRunning = MutableStateFlow(false)
    val diagRunning: StateFlow<Boolean> = _diagRunning.asStateFlow()
    private var diagJob: Job? = null

    private var pollJob: Job? = null

    init {
        // Load the saved config off the main thread (EncryptedSharedPreferences).
        viewModelScope.launch {
            _savedForm.value = withContext(Dispatchers.IO) { configStore.load() }
        }
        // Poll health only while connected; clear it otherwise.
        viewModelScope.launch {
            transportState.collect { st ->
                if (st is TransportState.Connected) startPolling() else stopPolling()
            }
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                _health.value = runCatching { controller.health() }.getOrNull()
                delay(1_000)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        _health.value = null
    }

    /** Generate a WireGuard keypair off the main thread and hand it back to the form. */
    fun generateKeypair(onGenerated: (WgKeypair) -> Unit) {
        viewModelScope.launch {
            val kp = withContext(Dispatchers.Default) { WireguardTools.generateKeypair() }
            onGenerated(kp)
        }
    }

    /** Build the config from the toggles + fields and connect. The form is
     *  persisted (encrypted) so it survives leaving the screen / restarts. SRV
     *  endpoint discovery (if enabled) is a network call, done in the coroutine. */
    fun connect(form: TransportForm, publicKey: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { configStore.save(form, publicKey) }
            _error.value = null
            val config = runCatching { form.toConfig() }.getOrElse {
                _error.value = it.message ?: "invalid transport config"
                return@launch
            }
            runCatching { controller.connect(config) }
                .onSuccess {
                    // Drop pooled sockets so live connections re-dial via the new
                    // route (SharedHttpClient KDoc): the router flipped underneath.
                    okHttpClient.connectionPool.evictAll()
                }
                .onFailure { _error.value = it.message ?: "failed to start transport" }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching { controller.disconnect() }
            okHttpClient.connectionPool.evictAll()
            _error.value = null
        }
    }

    /** Run the network diagnostic suite against [target], streaming to [diagOutput]. */
    fun runDiagnostics(target: String) {
        if (diagJob?.isActive == true) return
        _diagOutput.value = ""
        diagJob = viewModelScope.launch {
            _diagRunning.value = true
            try {
                diagnostics.run(target) { line -> _diagOutput.value += line + "\n" }
            } catch (e: Exception) {
                _diagOutput.value += "✗ diagnostics error: ${e.message}\n"
            } finally {
                _diagRunning.value = false
            }
        }
    }

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
        // Endpoint comes either from a hand-typed host:port or a fresh SRV lookup.
        val resolved: Endpoint = if (useSrv) srvResolver.resolve(srvName) else {
            val (host, port) = splitHostPort(endpoint, "WireGuard endpoint")
            Endpoint(host, port)
        }
        return WgConfig(
            privateKey = privateKey.trim(),
            peerPublicKey = peerPublicKey.trim(),
            presharedKey = presharedKey.trim().ifBlank { null },
            endpoint = resolved,
            interfaceAddresses = listOf(interfaceAddress.trim()),
            allowedIps = listOf("0.0.0.0/0"),
            // WG-configured DNS (used for in-tunnel resolution in WgOnly mode).
            dns = dns.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotBlank() },
            persistentKeepaliveSeconds = keepaliveSecs.trim().toIntOrNull() ?: 25,
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
