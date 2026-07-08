package io.github.pnck.gallery.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.pnck.gallery.network.transport.TransportState

/**
 * Debug screen for the transport layer (EPIC-5): fill the WgThenSocks parameters
 * by hand, connect, and watch [TransportState]. This is intentionally a raw form —
 * it is the only on-device way to exercise the tunnel until automatic enablement
 * and secure config storage land.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportScreen(
    onBack: () -> Unit,
    viewModel: TransportViewModel = hiltViewModel(),
) {
    val state by viewModel.transportState.collectAsState()
    val error by viewModel.error.collectAsState()

    var privateKey by rememberSaveable { mutableStateOf("") }
    var peerPublicKey by rememberSaveable { mutableStateOf("") }
    var presharedKey by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var interfaceAddress by rememberSaveable { mutableStateOf("") }
    var keepalive by rememberSaveable { mutableStateOf("25") }
    var socksHost by rememberSaveable { mutableStateOf("") }
    var socksPort by rememberSaveable { mutableStateOf("1080") }
    var socksUser by rememberSaveable { mutableStateOf("") }
    var socksPass by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WireGuard + SOCKS") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusCard(state = state, error = error)

            field(privateKey, { privateKey = it }, "Private key (base64)")
            field(peerPublicKey, { peerPublicKey = it }, "Peer public key (base64)")
            field(presharedKey, { presharedKey = it }, "Preshared key (optional)")
            field(endpoint, { endpoint = it }, "WG endpoint (host:port)")
            field(interfaceAddress, { interfaceAddress = it }, "Interface address (e.g. 10.0.0.2/32)")
            field(keepalive, { keepalive = it }, "Keepalive seconds", number = true)

            Text(
                "In-tunnel upstream SOCKS5",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            field(socksHost, { socksHost = it }, "Upstream SOCKS host (in-tunnel IP)")
            field(socksPort, { socksPort = it }, "Upstream SOCKS port", number = true)
            field(socksUser, { socksUser = it }, "SOCKS username (optional)")
            field(socksPass, { socksPass = it }, "SOCKS password (optional)", password = true)

            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        viewModel.connectWgThenSocks(
                            privateKey = privateKey,
                            peerPublicKey = peerPublicKey,
                            presharedKey = presharedKey,
                            endpoint = endpoint,
                            interfaceAddress = interfaceAddress,
                            keepaliveSecs = keepalive,
                            upstreamSocksHost = socksHost,
                            upstreamSocksPort = socksPort,
                            upstreamUser = socksUser,
                            upstreamPass = socksPass,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) { Text("Connect") }
                OutlinedButton(
                    onClick = viewModel::disconnect,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun StatusCard(state: TransportState, error: String?) {
    val (label, detail) = when (state) {
        is TransportState.Disconnected -> "Disconnected" to "Direct — tunnel off"
        is TransportState.Connecting -> "Connecting…" to "Bringing up the WireGuard tunnel"
        is TransportState.Connected ->
            "Connected" to "Local SOCKS5 on 127.0.0.1:${state.localSocksPort} · last handshake @${state.lastHandshakeEpoch}"
        is TransportState.Degraded -> "Degraded" to state.reason
        is TransportState.Failed -> "Failed" to state.reason
        is TransportState.BypassedDirect -> "Bypassed" to "Falling back to direct"
    }
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    number: Boolean = false,
    password: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
        ),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}
