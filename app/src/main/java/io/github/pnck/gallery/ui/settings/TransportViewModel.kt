package io.github.pnck.gallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.network.transport.TransportState
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
    /** Tunnel MTU; blank/0 = core default (1280). */
    val mtu: String,
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
    private val connector: TransportConnector,
    private val configStore: TransportConfigStore,
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
            _error.value = null
            // The connector persists the form, marks the tunnel active (for
            // auto-reconnect at launch), builds the config and evicts pooled sockets.
            runCatching { connector.connect(form, publicKey) }
                .onFailure { _error.value = it.message ?: "failed to start transport" }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching { connector.disconnect() }
            _error.value = null
        }
    }

}
