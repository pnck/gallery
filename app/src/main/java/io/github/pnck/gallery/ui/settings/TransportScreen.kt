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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.pnck.gallery.network.transport.TransportState

/**
 * Debug screen for the transport layer (EPIC-5). WireGuard and the upstream SOCKS5
 * are independent switches, so any of Direct / SOCKS-only / WG-only / WG+SOCKS can
 * be brought up. WG offers on-device keypair generation and sensible defaults.
 *
 * Intentionally a raw form — the only on-device way to exercise the tunnel until
 * automatic enablement and secure (EncryptedSharedPreferences) config storage land.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportScreen(
    onBack: () -> Unit,
    viewModel: TransportViewModel = hiltViewModel(),
) {
    val state by viewModel.transportState.collectAsState()
    val error by viewModel.error.collectAsState()

    var wgEnabled by rememberSaveable { mutableStateOf(true) }
    var socksEnabled by rememberSaveable { mutableStateOf(true) }

    var privateKey by rememberSaveable { mutableStateOf("") }
    var publicKey by rememberSaveable { mutableStateOf("") } // derived, shown to copy to the peer
    var peerPublicKey by rememberSaveable { mutableStateOf("") }
    var presharedKey by rememberSaveable { mutableStateOf("") }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var interfaceAddress by rememberSaveable { mutableStateOf("10.0.0.2/32") }
    var keepalive by rememberSaveable { mutableStateOf("25") }
    var socksHost by rememberSaveable { mutableStateOf("") }
    var socksPort by rememberSaveable { mutableStateOf("1080") }
    var socksUser by rememberSaveable { mutableStateOf("") }
    var socksPass by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network acceleration") },
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

            toggleRow("WireGuard tunnel", wgEnabled) { wgEnabled = it }
            if (wgEnabled) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.generateKeypair { kp ->
                                privateKey = kp.privateKey
                                publicKey = kp.publicKey
                            }
                        },
                    ) { Text("Generate keypair") }
                    Text(
                        "creates a new client key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                field(privateKey, { privateKey = it }, "Private key (base64)")
                if (publicKey.isNotBlank()) {
                    OutlinedTextField(
                        value = publicKey,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Your public key — add as a peer on the server") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                field(peerPublicKey, { peerPublicKey = it }, "Peer (server) public key (base64)")
                field(presharedKey, { presharedKey = it }, "Preshared key (optional)")
                field(endpoint, { endpoint = it }, "WG endpoint (host:port, e.g. vpn.example.com:51820)")
                field(interfaceAddress, { interfaceAddress = it }, "Interface address (e.g. 10.0.0.2/32)")
                field(keepalive, { keepalive = it }, "Keepalive seconds", number = true)
            }

            toggleRow("Upstream SOCKS5", socksEnabled) { socksEnabled = it }
            if (socksEnabled) {
                Text(
                    if (wgEnabled) "Reached through the tunnel (in-tunnel IP)" else "Reached directly",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                field(socksHost, { socksHost = it }, "SOCKS host")
                field(socksPort, { socksPort = it }, "SOCKS port", number = true)
                field(socksUser, { socksUser = it }, "SOCKS username (optional)")
                field(socksPass, { socksPass = it }, "SOCKS password (optional)", password = true)
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        viewModel.connect(
                            TransportForm(
                                wgEnabled = wgEnabled,
                                socksEnabled = socksEnabled,
                                privateKey = privateKey,
                                peerPublicKey = peerPublicKey,
                                presharedKey = presharedKey,
                                endpoint = endpoint,
                                interfaceAddress = interfaceAddress,
                                keepaliveSecs = keepalive,
                                socksHost = socksHost,
                                socksPort = socksPort,
                                socksUser = socksUser,
                                socksPass = socksPass,
                            ),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Connect") }
                OutlinedButton(
                    onClick = viewModel::disconnect,
                    modifier = Modifier.weight(1f),
                ) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun toggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatusCard(state: TransportState, error: String?) {
    val (label, detail) = when (state) {
        is TransportState.Disconnected -> "Disconnected" to "Direct — acceleration off"
        is TransportState.Connecting -> "Connecting…" to "Bringing up the transport"
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
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
    )
}
