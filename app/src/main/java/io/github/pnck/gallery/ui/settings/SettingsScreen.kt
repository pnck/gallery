package io.github.pnck.gallery.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.github.pnck.gallery.ui.util.rememberSystemDelete
import kotlinx.coroutines.launch

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
    val freeUris by viewModel.freeUris.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val systemDelete = rememberSystemDelete()
    val folderUnavailable = stringResource(R.string.account_folder_unavailable)

    // "Free up space": run the gathered synced-local uris through the system
    // delete dialog, then flip those rows to CLOUD_ONLY (T-302).
    LaunchedEffect(freeUris) {
        freeUris?.let { uris ->
            systemDelete(uris.map { it.toUri() }) { viewModel.confirmFreed(uris) }
            viewModel.onFreeHandled()
        }
    }

    var pendingEvent by remember { mutableStateOf<SettingsEvent?>(null) }
    LaunchedEffect(Unit) { viewModel.eventFlow.collect { pendingEvent = it } }
    val freeMessage = when (val event = pendingEvent) {
        SettingsEvent.NothingToFree -> stringResource(R.string.free_space_none)
        is SettingsEvent.Freed -> stringResource(R.string.space_freed, event.count)
        null -> null
    }
    LaunchedEffect(freeMessage) {
        freeMessage?.let {
            snackbarHost.showSnackbar(it)
            pendingEvent = null
        }
    }

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
        snackbarHost = { SnackbarHost(snackbarHost) },
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
        val busy = state.signIn is SignInPhase.Requesting || state.signIn is SignInPhase.AwaitingApproval
        val folderName by viewModel.remoteFolderName.collectAsState()
        var showAccount by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().padding(padding)) {
            ListItem(
                // Tap opens the account panel — sign-out is a deliberate action there,
                // not a one-tap on the row.
                modifier = Modifier.clickable { showAccount = true },
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
                modifier = Modifier.clickable(onClick = viewModel::requestFreeSpace),
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

        if (showAccount) {
            AccountSheet(
                authorized = state.googleAuthorized,
                busy = busy,
                folderName = folderName,
                onSignIn = { showAccount = false; viewModel.signInGoogle() },
                onSignOut = { showAccount = false; viewModel.signOutGoogle() },
                onUpdateFolder = viewModel::updateRemoteFolderName,
                onOpenFolder = {
                    scope.launch {
                        val link = viewModel.backupFolderLink()
                        if (link != null) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
                        } else {
                            snackbarHost.showSnackbar(folderUnavailable)
                        }
                    }
                },
                onDismiss = { showAccount = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    authorized: Boolean,
    busy: Boolean,
    folderName: String,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onUpdateFolder: (String) -> Unit,
    onOpenFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editingFolder by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.settings_google_account), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    busy -> stringResource(R.string.settings_google_connecting)
                    authorized -> stringResource(R.string.account_connected)
                    else -> stringResource(R.string.account_disconnected)
                },
                color = if (authorized) {
                    androidx.compose.ui.graphics.Color(0xFF2E7D32)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (authorized) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                ListItem(
                    modifier = Modifier.clickable { editingFolder = true },
                    headlineContent = { Text(stringResource(R.string.settings_folder)) },
                    supportingContent = { Text(folderName, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                ListItem(
                    modifier = Modifier.clickable(onClick = onOpenFolder),
                    headlineContent = { Text(stringResource(R.string.account_open_folder)) },
                    supportingContent = {
                        Text(stringResource(R.string.account_open_folder_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
                HorizontalDivider()
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = { confirmSignOut = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.account_sign_out)) }
            } else {
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onSignIn,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.account_sign_in)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editingFolder) {
        FolderNameDialog(
            initial = folderName,
            onConfirm = { onUpdateFolder(it); editingFolder = false },
            onDismiss = { editingFolder = false },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.account_sign_out_title)) },
            text = { Text(stringResource(R.string.account_sign_out_message)) },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; onSignOut() }) {
                    Text(stringResource(R.string.account_sign_out), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun FolderNameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
