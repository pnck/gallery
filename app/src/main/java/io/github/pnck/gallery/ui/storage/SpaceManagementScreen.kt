package io.github.pnck.gallery.ui.storage

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.pnck.gallery.R
import io.github.pnck.gallery.domain.StorageSummary
import io.github.pnck.gallery.ui.util.rememberSystemDelete

/**
 * Space management (T-302, PRD §7.3): the app's on-device photo footprint against real
 * device storage, and the only entry point to "free up space" — which releases verified
 * backed-up local copies after an explicit confirmation (the system delete dialog is the
 * second confirmation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceManagementScreen(
    onBack: () -> Unit,
    viewModel: SpaceManagementViewModel = hiltViewModel(),
) {
    val summary by viewModel.summary.collectAsState()
    val device by viewModel.device.collectAsState()
    val freeUris by viewModel.freeUris.collectAsState()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val systemDelete = rememberSystemDelete()
    var confirmFree by remember { mutableStateOf(false) }

    // Refresh device figures each time the screen is shown (a sync may have changed them).
    LaunchedEffect(Unit) { viewModel.refreshDevice() }

    // Run the verified freeable uris through the system delete dialog, then release rows.
    LaunchedEffect(freeUris) {
        freeUris?.let { uris ->
            systemDelete(uris.map { it.toUri() }) { viewModel.confirmFreed(uris) }
            viewModel.onFreeHandled()
        }
    }

    var pendingEvent by remember { mutableStateOf<SpaceEvent?>(null) }
    LaunchedEffect(Unit) { viewModel.eventFlow.collect { pendingEvent = it } }
    val message = when (val event = pendingEvent) {
        SpaceEvent.NothingToFree -> stringResource(R.string.free_space_none)
        is SpaceEvent.Freed -> stringResource(R.string.space_freed, event.count)
        null -> null
    }
    LaunchedEffect(message) {
        message?.let {
            snackbarHost.showSnackbar(it)
            pendingEvent = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storage_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            DeviceStorageCard(device, summary, context)
            Spacer(Modifier.height(16.dp))
            AppMediaCard(summary, context)
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { confirmFree = true },
                enabled = summary.freeableCount > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (summary.freeableCount > 0) {
                        stringResource(
                            R.string.storage_free_button,
                            Formatter.formatShortFileSize(context, summary.freeableBytes),
                        )
                    } else {
                        stringResource(R.string.storage_free_none)
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.storage_free_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (confirmFree) {
        AlertDialog(
            onDismissRequest = { confirmFree = false },
            title = { Text(stringResource(R.string.storage_free_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.storage_free_confirm_message,
                        summary.freeableCount,
                        Formatter.formatShortFileSize(context, summary.freeableBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmFree = false; viewModel.requestFreeSpace() }) {
                    Text(stringResource(R.string.storage_free_confirm_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFree = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun DeviceStorageCard(device: DeviceStorage, summary: StorageSummary, context: android.content.Context) {
    val total = device.totalBytes.coerceAtLeast(1)
    val used = (total - device.freeBytes).coerceIn(0, total)
    val appMedia = summary.localBytes.coerceIn(0, used)
    val otherUsed = (used - appMedia).coerceAtLeast(0)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.storage_device_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            // Stacked bar: other-apps used · this app's photos · free. The three
            // weighted segments sum to the whole device, so they fill the bar exactly.
            Row(
                Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Segment(otherUsed, total, MaterialTheme.colorScheme.outline)
                Segment(appMedia, total, MaterialTheme.colorScheme.primary)
                Segment(device.freeBytes, total, MaterialTheme.colorScheme.surfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            LegendRow(
                color = MaterialTheme.colorScheme.primary,
                label = stringResource(R.string.storage_legend_app),
                value = Formatter.formatShortFileSize(context, appMedia),
            )
            LegendRow(
                color = MaterialTheme.colorScheme.outline,
                label = stringResource(R.string.storage_legend_other),
                value = Formatter.formatShortFileSize(context, otherUsed),
            )
            LegendRow(
                color = MaterialTheme.colorScheme.surfaceVariant,
                label = stringResource(R.string.storage_legend_free),
                value = Formatter.formatShortFileSize(context, device.freeBytes),
            )
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.storage_device_total,
                    Formatter.formatShortFileSize(context, device.totalBytes),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Segment(bytes: Long, total: Long, color: Color) {
    if (bytes <= 0) return
    Box(Modifier.weight(bytes.toFloat() / total).height(14.dp).background(color))
}

@Composable
private fun LegendRow(color: Color, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AppMediaCard(summary: StorageSummary, context: android.content.Context) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.storage_app_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            StatRow(
                label = stringResource(R.string.storage_app_total, summary.localCount),
                value = Formatter.formatShortFileSize(context, summary.localBytes),
            )
            StatRow(
                label = stringResource(R.string.storage_app_freeable, summary.freeableCount),
                value = Formatter.formatShortFileSize(context, summary.freeableBytes),
            )
            StatRow(
                label = stringResource(R.string.storage_app_not_backed),
                value = Formatter.formatShortFileSize(context, summary.notBackedUpBytes),
            )
            StatRow(
                label = stringResource(R.string.storage_app_cloud_only),
                value = stringResource(R.string.storage_photo_count, summary.cloudOnlyCount),
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
