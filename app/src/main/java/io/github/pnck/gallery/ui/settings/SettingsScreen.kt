package io.github.pnck.gallery.ui.settings

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
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

    state.pendingChallenge?.let { challenge ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPending,
            title = { Text(stringResource(R.string.auth_device_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.auth_device_body,
                        challenge.userCode,
                        challenge.verificationUrl,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Best-effort: open the verification page on this device if it can
                    // reach it; otherwise the user types the code on a second screen.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, challenge.verificationUrl.toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text(stringResource(R.string.auth_device_open)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelPending) {
                    Text(stringResource(R.string.cancel))
                }
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
            ListItem(
                modifier = Modifier.clickable {
                    if (state.googleAuthorized) viewModel.signOutGoogle() else viewModel.signInGoogle()
                },
                headlineContent = { Text(stringResource(R.string.settings_google_account)) },
                supportingContent = {
                    Text(
                        when {
                            state.authError != null -> stringResource(R.string.auth_failed, state.authError ?: "")
                            state.googleAuthorized -> stringResource(R.string.settings_google_connected)
                            else -> stringResource(R.string.settings_google_disconnected)
                        },
                        color = if (state.authError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_free_space)) },
                supportingContent = { Text(stringResource(R.string.settings_free_space_hint)) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_transport)) },
                supportingContent = { Text(stringResource(R.string.settings_transport_hint)) },
            )
        }
    }
}
