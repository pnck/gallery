package io.github.pnck.gallery.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import io.github.pnck.gallery.network.transport.WgQuickConfig
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import io.github.pnck.gallery.BuildConfig
import io.github.pnck.gallery.R
import io.github.pnck.gallery.transport.WireguardTools
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.pnck.gallery.network.transport.TransportHealth
import io.github.pnck.gallery.network.transport.TransportState
import kotlinx.coroutines.delay

/**
 * Debug screen for the transport layer (EPIC-5). WireGuard and the upstream SOCKS5
 * are independent switches, so any of Direct / SOCKS-only / WG-only / WG+SOCKS can
 * be brought up. WG offers on-device keypair generation and sensible defaults.
 *
 * The config is persisted (encrypted) across visits/restarts; a live monitor at
 * the top shows status, transfer counters and last-handshake time while connected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportScreen(
    onBack: () -> Unit,
    viewModel: TransportViewModel = hiltViewModel(),
) {
    val state by viewModel.transportState.collectAsState()
    val error by viewModel.error.collectAsState()
    val health by viewModel.health.collectAsState()
    val saved by viewModel.savedForm.collectAsState()
    val vpnActive by viewModel.systemVpnActive.collectAsState()

    var wgEnabled by rememberSaveable { mutableStateOf(true) }
    var socksEnabled by rememberSaveable { mutableStateOf(true) }
    var privateKey by rememberSaveable { mutableStateOf("") }
    // Always derived from the private key in use — so what you copy to the server
    // is guaranteed to correspond to the configured private key.
    val publicKey = remember(privateKey) {
        if (privateKey.isBlank()) "" else WireguardTools.derivePublicKey(privateKey)
    }
    var peerPublicKey by rememberSaveable { mutableStateOf("") }
    var presharedKey by rememberSaveable { mutableStateOf("") }
    var useSrv by rememberSaveable { mutableStateOf(false) }
    var endpoint by rememberSaveable { mutableStateOf("") }
    var srvName by rememberSaveable { mutableStateOf("") }
    var interfaceAddress by rememberSaveable { mutableStateOf("10.0.0.2/32") }
    var dns by rememberSaveable { mutableStateOf("") }
    var keepalive by rememberSaveable { mutableStateOf("25") }
    var mtu by rememberSaveable { mutableStateOf("") }
    var socksHost by rememberSaveable { mutableStateOf("") }
    var socksPort by rememberSaveable { mutableStateOf("1080") }
    var socksUser by rememberSaveable { mutableStateOf("") }
    var socksPass by rememberSaveable { mutableStateOf("") }

    // Prefill from the persisted config exactly once, so a late load never
    // overwrites what the user just typed.
    var prefilled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(saved) {
        val s = saved
        if (s != null && !prefilled) {
            prefilled = true
            val f = s.form
            wgEnabled = f.wgEnabled; socksEnabled = f.socksEnabled
            privateKey = f.privateKey; peerPublicKey = f.peerPublicKey
            presharedKey = f.presharedKey; useSrv = f.useSrv; endpoint = f.endpoint; srvName = f.srvName
            interfaceAddress = f.interfaceAddress; dns = f.dns; keepalive = f.keepaliveSecs; mtu = f.mtu
            socksHost = f.socksHost; socksPort = f.socksPort; socksUser = f.socksUser; socksPass = f.socksPass
        }
    }

    fun currentForm() = TransportForm(
        wgEnabled = wgEnabled, socksEnabled = socksEnabled,
        privateKey = privateKey, peerPublicKey = peerPublicKey, presharedKey = presharedKey,
        useSrv = useSrv, endpoint = endpoint, srvName = srvName,
        interfaceAddress = interfaceAddress, dns = dns, keepaliveSecs = keepalive, mtu = mtu,
        socksHost = socksHost, socksPort = socksPort, socksUser = socksUser, socksPass = socksPass,
    )

    // Interop with the official WireGuard app via standard wg-quick .conf files.
    val context = LocalContext.current
    var ioMessage by remember { mutableStateOf<String?>(null) }
    val exportedMsg = stringResource(R.string.transport_exported)
    val exportFailedFmt = stringResource(R.string.transport_export_failed)
    val importedZipFmt = stringResource(R.string.transport_imported_zip)
    val importedMsg = stringResource(R.string.transport_imported)
    val importFailedFmt = stringResource(R.string.transport_import_failed)
    fun currentConf(): WgQuickConfig = WgQuickConfig(
        privateKey = privateKey.trim(),
        address = interfaceAddress.trim(),
        dns = dns.trim(),
        mtu = mtu.trim().toIntOrNull(),
        peerPublicKey = peerPublicKey.trim(),
        presharedKey = presharedKey.trim().ifBlank { null },
        endpoint = endpoint.trim(),
        allowedIps = WgQuickConfig.DEFAULT_ALLOWED_IPS,
        persistentKeepalive = keepalive.trim().toIntOrNull(),
    )
    // */* keeps the title's extension verbatim — a typed MIME (e.g. octet-stream)
    // makes SAF append its own (.bin), which the official app won't recognize.
    // ONE export format: a bare .conf (we manage a single tunnel — the official
    // app imports it fine). Import accepts every official format (.conf + zip).
    val exportConfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(currentConf().serialize().toByteArray()) }
            }.onSuccess { ioMessage = exportedMsg }
                .onFailure { ioMessage = exportFailedFmt.format(it.message) }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("cannot read file")
                WgQuickConfig.parseAny(bytes)
            }.onSuccess { (c, entryName) ->
                wgEnabled = true; useSrv = false
                privateKey = c.privateKey; peerPublicKey = c.peerPublicKey
                presharedKey = c.presharedKey.orEmpty(); endpoint = c.endpoint
                interfaceAddress = c.address; dns = c.dns
                mtu = c.mtu?.toString().orEmpty(); keepalive = c.persistentKeepalive?.toString() ?: "25"
                ioMessage = if (entryName != null) {
                    importedZipFmt.format(entryName)
                } else {
                    importedMsg
                }
            }.onFailure { ioMessage = importFailedFmt.format(it.message) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_transport)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
            MonitorCard(state = state, health = health, error = error, vpnActive = vpnActive)

            toggleRow(stringResource(R.string.transport_wg_tunnel), wgEnabled) { wgEnabled = it }
            if (wgEnabled) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { viewModel.generateKeypair { kp -> privateKey = kp.privateKey } },
                    ) { Text(stringResource(R.string.transport_generate_keypair)) }
                    Text(
                        stringResource(R.string.transport_keypair_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                field(privateKey, { privateKey = it }, stringResource(R.string.transport_private_key))
                if (publicKey.isNotBlank()) {
                    OutlinedTextField(
                        value = publicKey,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.transport_public_key_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (privateKey.isNotBlank()) {
                    Text(
                        stringResource(R.string.transport_private_key_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                field(peerPublicKey, { peerPublicKey = it }, stringResource(R.string.transport_peer_public_key))
                field(presharedKey, { presharedKey = it }, stringResource(R.string.transport_preshared_key))

                // Endpoint: hand-typed host:port, or "subscribed" from a DNS SRV
                // record (re-resolved on every connect, so the server can move).
                toggleRow(stringResource(R.string.transport_use_srv), useSrv) { useSrv = it }
                if (useSrv) {
                    field(srvName, { srvName = it }, stringResource(R.string.transport_srv_name))
                    Text(
                        stringResource(R.string.transport_srv_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    field(endpoint, { endpoint = it }, stringResource(R.string.transport_endpoint))
                }

                field(interfaceAddress, { interfaceAddress = it }, stringResource(R.string.transport_interface_address))
                field(dns, { dns = it }, stringResource(R.string.transport_dns))
                Text(
                    stringResource(R.string.transport_dns_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                field(keepalive, { keepalive = it }, stringResource(R.string.transport_keepalive), number = true)
                field(mtu, { mtu = it }, stringResource(R.string.transport_mtu), number = true)
                Text(
                    stringResource(R.string.transport_mtu_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { exportConfLauncher.launch("gallery-wg.conf") },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.transport_export)) }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.transport_import)) }
                }
                Text(
                    stringResource(R.string.transport_io_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ioMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }

            toggleRow(stringResource(R.string.transport_socks), socksEnabled) { socksEnabled = it }
            if (socksEnabled) {
                Text(
                    stringResource(if (wgEnabled) R.string.transport_socks_via_tunnel else R.string.transport_socks_direct),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                field(socksHost, { socksHost = it }, stringResource(R.string.transport_socks_host))
                field(socksPort, { socksPort = it }, stringResource(R.string.transport_socks_port), number = true)
                field(socksUser, { socksUser = it }, stringResource(R.string.transport_socks_user))
                field(socksPass, { socksPass = it }, stringResource(R.string.transport_socks_pass), password = true)
            }

            val connected = state is TransportState.Connected
            // Connecting + Degraded both mean the tunnel is up and (re)trying.
            val trying = state is TransportState.Connecting || state is TransportState.Degraded
            val active = connected || trying
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { viewModel.connect(currentForm(), publicKey) },
                    // A system VPN owns the network — manual connect/disconnect is
                    // disabled until it goes away (routes yield automatically).
                    enabled = !active && !vpnActive,
                    modifier = Modifier.weight(1f),
                ) {
                    if (trying) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(if (connected) R.string.transport_state_connected else R.string.transport_connect))
                    }
                }
                OutlinedButton(
                    onClick = viewModel::disconnect,
                    enabled = (active || state is TransportState.Failed) && !vpnActive,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.transport_disconnect)) }
            }
            if (vpnActive) {
                Text(
                    stringResource(R.string.transport_vpn_active_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** Live status + transfer/handshake monitor, WireGuard-app style. */
@Composable
private fun MonitorCard(state: TransportState, health: TransportHealth?, error: String?, vpnActive: Boolean) {
    // A 1 Hz clock so "x ago" keeps ticking even when byte counters are static.
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(state) {
        while (state is TransportState.Connected) {
            nowSec = System.currentTimeMillis() / 1000
            delay(1_000)
        }
    }

    // Yielding is a projection over (tunnel state, system VPN presence): whenever a
    // system VPN holds the network and the tunnel is up in any form, every route is
    // masked to NO_PROXY — say so instead of a misleading plain status. (Handshake
    // noise is suppressed upstream while yielding, so Connected is truthful here.)
    val yielding = vpnActive && state !is TransportState.Disconnected
    val (label, labelColor) = when {
        yielding -> stringResource(R.string.transport_state_yielding) to MaterialTheme.colorScheme.tertiary
        else -> when (state) {
            is TransportState.Disconnected -> stringResource(R.string.transport_state_disconnected) to MaterialTheme.colorScheme.onSurfaceVariant
            is TransportState.Connecting -> stringResource(R.string.transport_state_connecting) to MaterialTheme.colorScheme.primary
            is TransportState.Connected -> stringResource(R.string.transport_state_connected) to Color(0xFF2E7D32)
            is TransportState.Degraded -> stringResource(R.string.transport_state_degraded) to MaterialTheme.colorScheme.tertiary
            is TransportState.Failed -> stringResource(R.string.transport_state_failed) to MaterialTheme.colorScheme.error
        }
    }

    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = labelColor)

            if (yielding) {
                Text(
                    stringResource(R.string.transport_yielding_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vpnActive && state is TransportState.Disconnected) {
                Text(
                    stringResource(R.string.transport_yielding_disabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Show the actionable reason for degraded/failed states.
            val reason = when (state) {
                is TransportState.Degraded -> state.reason
                is TransportState.Failed -> state.reason
                else -> null
            }
            reason?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (state is TransportState.Connected && !yielding) {
                val port = (state as TransportState.Connected).localSocksPort
                Text(
                    stringResource(R.string.transport_local_socks, port),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (health?.viaTunnel == true) {
                    Text(
                        stringResource(R.string.transport_transfer, formatBytes(health.rxBytes), formatBytes(health.txBytes)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.transport_handshake, relativeTimeText(health.lastHandshakeEpoch, nowSec)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (health.handshakeOk) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatBytes(bytes: Long?): String {
    val b = bytes ?: 0
    if (b < 1024) return "$b B"
    val kib = b / 1024.0
    if (kib < 1024) return "%.1f KiB".format(kib)
    val mib = kib / 1024.0
    if (mib < 1024) return "%.1f MiB".format(mib)
    return "%.2f GiB".format(mib / 1024.0)
}

@Composable
private fun relativeTimeText(epochSec: Long?, nowSec: Long): String {
    if (epochSec == null || epochSec <= 0) return stringResource(R.string.time_never)
    val d = (nowSec - epochSec).coerceAtLeast(0)
    return when {
        d < 2 -> stringResource(R.string.time_just_now)
        d < 60 -> stringResource(R.string.time_s_ago, d)
        d < 3600 -> stringResource(R.string.time_ms_ago, d / 60, d % 60)
        else -> stringResource(R.string.time_hm_ago, d / 3600, (d % 3600) / 60)
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
