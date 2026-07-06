package io.github.pnck.gallery.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.pnck.gallery.R

/**
 * Settings (PRD §9.1). Skeleton with the planned sections:
 *  - accounts: AppAuth entry points for Google / Microsoft (T-101)
 *  - free up space: triggers the PRD §7.3 system delete flow (T-302)
 *  - sync policy: WiFi-only / charging-only / release threshold days
 *  - network acceleration: transport mode, wg-quick import, probe (EPIC-5)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
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
                headlineContent = { Text(stringResource(R.string.settings_accounts)) },
                supportingContent = { Text(stringResource(R.string.settings_accounts_hint)) },
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
