package io.github.pnck.gallery.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.pnck.gallery.R

/**
 * Settings (PRD §9.1): device-flow account connection (ADR-0001), plus
 * placeholders for free-up-space (T-302) and the transport layer (EPIC-5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onTransportClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAuthState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (val phase = state.signIn) {
        is SignInPhase.Idle -> Unit

        is SignInPhase.Requesting -> AlertDialog(
            onDismissRequest = viewModel::cancelSignIn,
            title = { Text(stringResource(R.string.auth_requesting_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.auth_requesting_body))
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::cancelSignIn) { Text(stringResource(R.string.cancel)) }
            },
        )

        is SignInPhase.AwaitingApproval -> AlertDialog(
            onDismissRequest = viewModel::cancelSignIn,
            title = { Text(stringResource(R.string.auth_device_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.auth_device_body,
                        phase.challenge.userCode,
                        phase.challenge.verificationUrl,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Best-effort: open the verification page on this device if it can
                    // reach it; otherwise the user types the code on a second screen.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, phase.challenge.verificationUrl.toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text(stringResource(R.string.auth_device_open)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelSignIn) { Text(stringResource(R.string.cancel)) }
            },
        )

        is SignInPhase.Failed -> AlertDialog(
            onDismissRequest = viewModel::cancelSignIn,
            title = { Text(stringResource(R.string.auth_error_title)) },
            text = {
                Column {
                    Text(phase.message, color = MaterialTheme.colorScheme.error)
                    if (phase.network) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.auth_network_hint))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::signInGoogle) { Text(stringResource(R.string.auth_retry)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelSignIn) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val busy = state.signIn is SignInPhase.Requesting || state.signIn is SignInPhase.AwaitingApproval
            ListItem(
                modifier = Modifier.clickable(enabled = !busy) {
                    if (state.googleAuthorized) viewModel.signOutGoogle() else viewModel.signInGoogle()
                },
                headlineContent = { Text(stringResource(R.string.settings_google_account)) },
                supportingContent = {
                    Text(
                        when {
                            busy -> stringResource(R.string.settings_google_connecting)
                            state.googleAuthorized -> stringResource(R.string.settings_google_connected)
                            else -> stringResource(R.string.settings_google_disconnected)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_free_space)) },
                supportingContent = { Text(stringResource(R.string.settings_free_space_hint)) },
            )
            HorizontalDivider()
            val transportState by viewModel.transportState.collectAsState()
            val transportConnected = transportState is io.github.pnck.gallery.network.transport.TransportState.Connected
            ListItem(
                modifier = Modifier.clickable(onClick = onTransportClick),
                headlineContent = { Text(stringResource(R.string.settings_transport)) },
                supportingContent = {
                    Text(
                        if (transportConnected) {
                            stringResource(R.string.settings_transport_connected)
                        } else {
                            stringResource(R.string.settings_transport_hint)
                        },
                        color = if (transportConnected) {
                            androidx.compose.ui.graphics.Color(0xFF2E7D32)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
        }
    }
}
