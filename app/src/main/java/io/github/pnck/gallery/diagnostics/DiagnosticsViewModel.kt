package io.github.pnck.gallery.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.pnck.gallery.BuildConfig
import io.github.pnck.gallery.data.settings.AppSettingsStore
import io.github.pnck.gallery.discovery.NetworkDiagnostics
import io.github.pnck.gallery.network.transport.TransportHealth
import io.github.pnck.gallery.network.transport.TransportState
import io.github.pnck.gallery.transport.TransportController
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.gallery_transport.setTransportLogLevel

/**
 * The diagnostics hub (debug/alpha only — BuildConfig.DIAGNOSTICS_ENABLED):
 * runtime log-level control for the transport core, the staged reachability
 * probe, the transport config/health dump, and a one-tap export bundle.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val settings: AppSettingsStore,
    private val controller: TransportController,
    private val diagnostics: NetworkDiagnostics,
) : ViewModel() {

    val transportState: StateFlow<TransportState> =
        controller.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransportState.Disconnected)

    /** Live tunnel counters (tx/rx bytes, handshake), refreshed ~1 Hz while subscribed. */
    private val _health = MutableStateFlow<TransportHealth?>(null)
    val health: StateFlow<TransportHealth?> = _health.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                _health.value = runCatching { controller.health() }.getOrNull()
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    val logLevel: StateFlow<String> = settings.transportLogLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "warn")

    val buildInfo: String = "version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    fun setLogLevel(level: String) {
        viewModelScope.launch {
            settings.setTransportLogLevel(level)
            withContext(Dispatchers.IO) { setTransportLogLevel(level) }
        }
    }

    fun runDiag(target: String) {
        if (_running.value) return
        _running.value = true
        _output.value = ""
        viewModelScope.launch {
            try {
                diagnostics.run(target) { line -> _output.value += line + "\n" }
            } finally {
                _running.value = false
            }
        }
    }

    fun dumpTransport() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { controller.diagnosticInfo() }
                ?: "(transport not connected — connect first)"
            _output.value = info + "\n"
        }
    }

    /** Everything useful in a bug report, one shareable text file. */
    suspend fun exportBundle(): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("BYOS Gallery diagnostics bundle")
            appendLine("build: $buildInfo")
            appendLine("transport state: ${transportState.value}")
            appendLine("transport log level: ${logLevel.value}")
            appendLine()
            appendLine("== transport info ==")
            appendLine(controller.diagnosticInfo() ?: "(not connected)")
            if (_output.value.isNotBlank()) {
                appendLine()
                appendLine("== last diag run ==")
                append(_output.value)
            }
        }
    }

    companion object {
        val LEVELS = listOf("off", "error", "warn", "info", "debug", "trace")
    }
}
