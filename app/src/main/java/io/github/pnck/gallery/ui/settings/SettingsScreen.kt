package io.github.pnck.gallery.ui.settings

import io.github.pnck.gallery.BuildConfig

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
import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import io.github.pnck.gallery.ui.util.AppLocale
import io.github.pnck.gallery.ui.util.showAppToast
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
    onStorageClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    onLibraryFoldersClick: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folderUnavailable = stringResource(R.string.account_folder_unavailable)

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
        val busy = state.signIn is SignInPhase.Requesting || state.signIn is SignInPhase.AwaitingApproval
        val folderName by viewModel.remoteFolderName.collectAsState()
        var showAccount by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().padding(padding)) {
            SettingsGroupHeader(stringResource(R.string.settings_group_account))
            ListItem(
                // Tap opens the account panel — sign-out is a deliberate action there,
                // not a one-tap on the row.
                modifier = Modifier.clickable { showAccount = true },
                headlineContent = { Text(stringResource(R.string.settings_google_account)) },
                supportingContent = {
                    Text(
                        when {
                            busy -> stringResource(R.string.settings_google_connecting)
                            !state.googleAuthorized -> stringResource(R.string.settings_google_disconnected)
                            // "Connected" is only claimed after a live probe — a held
                            // token with a dead tunnel is signed-in-but-offline.
                            state.cloudReachable == false -> stringResource(R.string.account_unreachable)
                            state.cloudReachable == null -> stringResource(R.string.account_checking)
                            else -> stringResource(R.string.settings_google_connected)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            SettingsGroupHeader(stringResource(R.string.settings_group_library))
            ListItem(
                modifier = Modifier.clickable(onClick = onLibraryFoldersClick),
                headlineContent = { Text(stringResource(R.string.library_folders_title)) },
                supportingContent = {
                    val scope by viewModel.scanBuckets.collectAsState()
                    Text(
                        if (scope.isEmpty()) {
                            stringResource(R.string.folders_all)
                        } else {
                            stringResource(R.string.library_folders_some, scope.size)
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            ListItem(
                modifier = Modifier.clickable(onClick = onStorageClick),
                headlineContent = { Text(stringResource(R.string.settings_storage)) },
                supportingContent = { Text(stringResource(R.string.settings_storage_hint)) },
            )
            SettingsGroupHeader(stringResource(R.string.settings_group_general))
            // In-app language override; recreates the activity to apply.
            var showLanguage by remember { mutableStateOf(false) }
            val languageTag = AppLocale.current(context)
            ListItem(
                modifier = Modifier.clickable { showLanguage = true },
                headlineContent = { Text(stringResource(R.string.settings_language)) },
                supportingContent = {
                    Text(
                        AppLocale.label(languageTag).ifBlank { stringResource(R.string.language_system) },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            if (showLanguage) {
                AlertDialog(
                    onDismissRequest = { showLanguage = false },
                    title = { Text(stringResource(R.string.settings_language)) },
                    text = {
                        Column {
                            listOf(
                                AppLocale.FOLLOW_SYSTEM to stringResource(R.string.language_system),
                                "en" to AppLocale.label("en"),
                                "zh-CN" to AppLocale.label("zh-CN"),
                            ).forEach { (tag, label) ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        showLanguage = false
                                        if (tag != languageTag) {
                                            AppLocale.set(context, tag)
                                            (context as? Activity)?.recreate()
                                        }
                                    }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = tag == languageTag, onClick = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(label)
                                }
                            }
                        }
                    },
                    confirmButton = {},
                )
            }
            SettingsGroupHeader(stringResource(R.string.settings_group_network))
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
            if (BuildConfig.DIAGNOSTICS_ENABLED) {
                SettingsGroupHeader(stringResource(R.string.settings_group_developer))
                ListItem(
                    modifier = Modifier.clickable(onClick = onDiagnosticsClick),
                    headlineContent = { Text(stringResource(R.string.diagnostics_title)) },
                    supportingContent = { Text(stringResource(R.string.diagnostics_hint)) },
                )
            }
        }

        // Re-probe reachability every time the panel opens — the user checks it
        // right after toggling the tunnel, so a stale verdict is worse than none.
        LaunchedEffect(showAccount) {
            if (showAccount) viewModel.refreshAuthState()
        }

        if (showAccount) {
            AccountSheet(
                authorized = state.googleAuthorized,
                busy = busy,
                accountEmail = state.accountEmail,
                reachable = state.cloudReachable,
                folderName = folderName,
                myDriveAuthorized = state.myDriveAuthorized,
                myDriveReachable = state.myDriveReachable,
                onMyDriveSignOut = viewModel::signOutMyDrive,
                onSignIn = { showAccount = false; viewModel.signInGoogle() },
                onSignOut = { showAccount = false; viewModel.signOutGoogle() },
                onUpdateFolder = viewModel::updateRemoteFolderName,
                onOpenFolder = {
                    scope.launch {
                        val link = viewModel.backupFolderLink()
                        if (link != null) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
                        } else {
                            showAppToast(context, folderUnavailable)
                        }
                    }
                },
                onDismiss = { showAccount = false },
            )
        }
    }
}

/** Group label above a settings section (M3 settings pattern: primary, small). */
@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    authorized: Boolean,
    busy: Boolean,
    accountEmail: String?,
    /** null = probe in flight; false = signed in but the cloud doesn't answer. */
    reachable: Boolean?,
    myDriveAuthorized: Boolean,
    /** The separate drive.readonly grant ("My Drive" browser): null = probing/offline,
     *  false = the server says the grant is dead (expired/revoked). */
    myDriveReachable: Boolean?,
    onMyDriveSignOut: () -> Unit,
    folderName: String,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onUpdateFolder: (String) -> Unit,
    onOpenFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editingFolder by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.settings_google_account), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text = when {
                    busy -> stringResource(R.string.settings_google_connecting)
                    !authorized -> stringResource(R.string.account_disconnected)
                    reachable == false -> stringResource(R.string.account_unreachable)
                    reachable == null -> stringResource(R.string.account_checking)
                    else -> stringResource(R.string.account_connected)
                },
                color = if (authorized && reachable == true) {
                    androidx.compose.ui.graphics.Color(0xFF2E7D32)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (authorized) {
                if (accountEmail != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.account_signed_in_as, accountEmail),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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
            }
            // The separate drive.readonly grant ("My Drive") is managed in the same
            // panel — revoking it never touches the backup grant above. The status is
            // probe-derived: a stored token alone says nothing about server-side death.
            ListItem(
                headlineContent = { Text(stringResource(R.string.mydrive_account_row)) },
                supportingContent = {
                    Text(
                        stringResource(
                            when {
                                !myDriveAuthorized -> R.string.mydrive_account_off
                                myDriveReachable == false -> R.string.mydrive_account_expired
                                else -> R.string.mydrive_account_on
                            },
                        ),
                        color = if (myDriveAuthorized && myDriveReachable == false) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                trailingContent = if (myDriveAuthorized) {
                    { TextButton(onClick = onMyDriveSignOut) { Text(stringResource(R.string.mydrive_account_revoke)) } }
                } else {
                    null
                },
            )
            if (authorized) {
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
