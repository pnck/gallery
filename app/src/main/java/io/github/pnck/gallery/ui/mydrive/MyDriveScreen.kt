package io.github.pnck.gallery.ui.mydrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.pnck.gallery.R

/**
 * "My Drive" — a deliberately separate feature (agreed nav model) for browsing the
 * user's whole Drive, so a backup whose app-created provenance was lost (cross-account
 * transfer, out-of-band edits) can still be found and re-synced by content. It requests
 * broader Drive-read access ON ITS OWN, apart from the least-privilege main flow.
 *
 * This is the gate screen; the browse + drive.readonly elevation land in a follow-up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDriveScreen(onOpenDrawer: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_my_drive)) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_drawer))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.CloudQueue,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.mydrive_gate_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.mydrive_gate_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = {}, enabled = false) {
                Text(stringResource(R.string.mydrive_enable))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.mydrive_coming_soon),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
