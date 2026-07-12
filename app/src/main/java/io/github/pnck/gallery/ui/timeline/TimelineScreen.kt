package io.github.pnck.gallery.ui.timeline

import android.Manifest
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.work.WorkInfo
import coil3.compose.AsyncImage
import io.github.pnck.gallery.R
import io.github.pnck.gallery.domain.SyncCounts
import io.github.pnck.gallery.domain.SyncState
import io.github.pnck.gallery.ui.util.pinchToStep
import io.github.pnck.gallery.ui.util.rememberSystemDelete
import androidx.compose.ui.unit.Dp

/** Clean selection accent (Google-blue), instead of the dynamic-theme purple. */
private val SelectionAccent = Color(0xFF1A73E8)

/** The media-read permission for this SDK level (PRD §6.3 matrix). */
private fun mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * Timeline: square-cropped adaptive grid, Google Photos style (PRD §9.1).
 * Compose discipline (PRD §2.4): stable keys, explicit sizes for Coil downsampling.
 * Long-press OR the Select action enters multi-select; each item carries a
 * sync-state badge (PRD §3.7). The title opens a sync-status sheet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimelineScreen(
    onPhotoClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val photos = viewModel.photosFlow.collectAsLazyPagingItems()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val selectionMode by viewModel.selectionActive.collectAsState()
    val deleteRequest by viewModel.deleteRequest.collectAsState()
    val deleteConfirm by viewModel.deleteConfirm.collectAsState()
    val syncCounts by viewModel.syncCounts.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val backupState by viewModel.backupState.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbarHost = remember { SnackbarHostState() }
    val systemDelete = rememberSystemDelete()

    var showStatus by remember { mutableStateOf(false) }

    // Pinch to change thumbnail density (Google-Photos grid zoom).
    val cellSizes: List<Dp> = remember { listOf(72.dp, 100.dp, 148.dp) }
    var cellIndex by remember { mutableStateOf(1) }

    // Aggressive in-app scan: react to any media change while the timeline is shown
    // (T-304). SyncPipeline's unique KEEP coalesces bursts, so no extra debounce.
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                viewModel.processIntent(TimelineIntent.ForceSync)
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    // Route a pending delete through the system dialog, then purge cloud + rows.
    LaunchedEffect(deleteRequest) {
        deleteRequest?.let { request ->
            systemDelete(request.localUris.map { it.toUri() }) { viewModel.purge(request.ids) }
            viewModel.onDeleteHandled()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.processIntent(TimelineIntent.ForceSync)
    }

    // Ask for media access once on entry; API 34+ partial grant still returns
    // granted=true and MediaStore serves the user-selected subset (PRD §6.3).
    LaunchedEffect(Unit) {
        val permission = mediaPermission()
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            viewModel.processIntent(TimelineIntent.ForceSync)
        } else {
            permissionLauncher.launch(permission)
        }
    }

    // One-shot feedback (sync queued / save started / deleted). Resolve the string
    // in composition (lint: no LocalContext.getString in effects), then show it.
    var pendingEvent by remember { mutableStateOf<TimelineEvent?>(null) }
    LaunchedEffect(Unit) { viewModel.eventFlow.collect { pendingEvent = it } }
    val eventMessage = when (val event = pendingEvent) {
        is TimelineEvent.SyncQueued -> stringResource(R.string.sync_queued, event.count)
        is TimelineEvent.SaveStarted -> stringResource(R.string.save_started, event.count)
        is TimelineEvent.Deleted -> stringResource(R.string.photos_deleted, event.count)
        null -> null
    }
    LaunchedEffect(eventMessage) {
        eventMessage?.let {
            snackbarHost.showSnackbar(it)
            pendingEvent = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            if (selectionMode) {
                SelectionAppBar(
                    count = selection.size,
                    onClear = viewModel::exitSelectionMode,
                    onSync = viewModel::syncSelected,
                    onSave = viewModel::saveSelected,
                    onDelete = viewModel::deleteSelected,
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            text = syncStatusLabel(syncStatus),
                            modifier = Modifier.clickable { showStatus = true },
                        )
                    },
                    actions = {
                        IconButton(onClick = viewModel::enterSelectionMode) {
                            Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.action_select))
                        }
                        IconButton(onClick = { viewModel.processIntent(TimelineIntent.ForceSync) }) {
                            Icon(Icons.Default.Sync, contentDescription = stringResource(R.string.force_sync))
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            BackupBanner(
                state = backupState,
                onRetry = { viewModel.processIntent(TimelineIntent.ForceSync) },
            )
            if (photos.itemCount == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.timeline_empty),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = cellSizes[cellIndex]),
                    modifier = Modifier.fillMaxSize().pinchToStep(
                        onZoomIn = { cellIndex = (cellIndex + 1).coerceAtMost(cellSizes.lastIndex) },
                        onZoomOut = { cellIndex = (cellIndex - 1).coerceAtLeast(0) },
                    ),
                ) {
                items(
                    count = photos.itemCount,
                    key = { index -> photos.peek(index)?.id ?: index },
                ) { index ->
                    val photo = photos[index] ?: return@items
                    val selected = photo.id in selection
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) viewModel.toggleSelection(photo.id)
                                    else onPhotoClick(photo.id)
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.toggleSelection(photo.id)
                                },
                            ),
                    ) {
                        AsyncImage(
                            model = photo.renderUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        SyncStateBadge(
                            state = photo.syncState,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                        )
                        if (selectionMode) {
                            if (selected) {
                                Box(Modifier.fillMaxSize().background(SelectionAccent.copy(alpha = 0.25f)))
                            }
                            Icon(
                                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selected) SelectionAccent else Color.White,
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp),
                            )
                        }
                    }
                }
            }
            }
        }
    }

    deleteConfirm?.let { confirm ->
        DeleteConfirmDialog(
            count = confirm.ids.size,
            notBackedUp = confirm.notBackedUp,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }

    if (showStatus) {
        ModalBottomSheet(onDismissRequest = { showStatus = false }) {
            SyncStatusSheet(
                status = syncStatus,
                counts = syncCounts,
                queue = queue,
                onSyncNow = { viewModel.processIntent(TimelineIntent.ForceSync) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionAppBar(
    count: Int,
    onClear: () -> Unit,
    onSync: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.selection_clear))
            }
        },
        title = { Text(stringResource(R.string.selection_count, count)) },
        actions = {
            IconButton(onClick = onSync) {
                Icon(Icons.Default.CloudUpload, contentDescription = stringResource(R.string.action_sync_selected))
            }
            IconButton(onClick = onSave) {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.action_save_selected))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete_selected))
            }
        },
    )
}

@Composable
private fun DeleteConfirmDialog(
    count: Int,
    notBackedUp: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_confirm_title, count)) },
        text = {
            Column {
                Text(stringResource(R.string.delete_confirm_message))
                if (notBackedUp > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.delete_confirm_unsynced, notBackedUp),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete_confirm_button), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun SyncStatusSheet(
    status: SyncStatus,
    counts: SyncCounts,
    queue: List<SyncJob>,
    onSyncNow: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.sync_status_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = syncStatusDetail(status),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            CountPill(counts.pendingUpload, stringResource(R.string.sync_count_pending))
            CountPill(counts.synced, stringResource(R.string.sync_count_synced))
            CountPill(counts.cloudOnly, stringResource(R.string.sync_count_cloud))
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.sync_queue_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (queue.isEmpty()) {
            Text(
                stringResource(R.string.sync_queue_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            queue.forEach { job ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(job.name)
                    Text(workStateLabel(job.state), color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onSyncNow, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.force_sync))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CountPill(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Google-Photos-style backup banner pinned above the grid: live per-item progress
 * while running, a waiting count with Retry otherwise, and nothing when idle.
 */
@Composable
private fun BackupBanner(state: BackupState, onRetry: () -> Unit) {
    when (state) {
        is BackupState.Idle -> Unit

        is BackupState.Running -> Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.currentUri != null) {
                    AsyncImage(
                        model = state.currentUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.backup_running, state.done, state.total),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    val fraction = if (state.total > 0) {
                        (state.done + state.pct / 100f) / state.total
                    } else {
                        0f
                    }
                    LinearProgressIndicator(
                        progress = { fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        is BackupState.Pending -> Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = if (state.failed > 0) {
                        stringResource(R.string.backup_pending_failed, state.count, state.failed)
                    } else {
                        stringResource(R.string.backup_pending, state.count)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(if (state.failed > 0) R.string.backup_retry else R.string.backup_now))
                }
            }
        }
    }
}

/**
 * Per-state badge on a translucent scrim so it stays legible over bright photos.
 * Monochrome (white) icons — differentiated by shape, not colour, for a clean look.
 */
@Composable
private fun SyncStateBadge(state: SyncState, modifier: Modifier = Modifier) {
    val (icon: ImageVector, description: String) = when (state) {
        SyncState.PENDING_UPLOAD -> Icons.Default.CloudUpload to stringResource(R.string.badge_pending_upload)
        SyncState.SYNCED -> Icons.Default.CloudDone to stringResource(R.string.badge_synced)
        SyncState.CLOUD_ONLY -> Icons.Default.Cloud to stringResource(R.string.badge_cloud_only)
        SyncState.PENDING_DELETE -> return // transient; no badge
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun syncStatusLabel(status: SyncStatus): String = when (status) {
    is SyncStatus.Idle -> stringResource(R.string.app_name)
    is SyncStatus.Scanning -> stringResource(R.string.sync_scanning)
    is SyncStatus.Uploading -> stringResource(R.string.sync_uploading, status.done, status.total)
}

@Composable
private fun syncStatusDetail(status: SyncStatus): String = when (status) {
    is SyncStatus.Idle -> stringResource(R.string.sync_status_idle)
    is SyncStatus.Scanning -> stringResource(R.string.sync_scanning)
    is SyncStatus.Uploading -> stringResource(R.string.sync_uploading, status.done, status.total)
}

@Composable
private fun workStateLabel(state: WorkInfo.State): String = when (state) {
    WorkInfo.State.RUNNING -> stringResource(R.string.work_running)
    WorkInfo.State.ENQUEUED -> stringResource(R.string.work_enqueued)
    WorkInfo.State.BLOCKED -> stringResource(R.string.work_blocked)
    else -> state.name
}
