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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.compose.foundation.shape.CircleShape
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import io.github.pnck.gallery.R
import io.github.pnck.gallery.domain.SyncState
import io.github.pnck.gallery.ui.util.rememberSystemDelete

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
 * Long-press enters multi-select; each item carries a sync-state badge (PRD §3.7).
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
    val deleteRequest by viewModel.deleteRequest.collectAsState()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val systemDelete = rememberSystemDelete()

    val selectionMode = selection.isNotEmpty()

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

    // One-shot feedback (sync queued / save started). Resolve the string in
    // composition (lint: no LocalContext.getString in effects), then show it.
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
                    onClear = viewModel::clearSelection,
                    onSync = viewModel::syncSelected,
                    onSave = viewModel::saveSelected,
                    onDelete = viewModel::deleteSelected,
                )
            } else {
                TopAppBar(
                    title = { Text(syncStatusLabel(syncStatus)) },
                    actions = {
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
        if (photos.itemCount == 0) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.timeline_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
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
                                onLongClick = { viewModel.toggleSelection(photo.id) },
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
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                            }
                            Icon(
                                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp),
                            )
                        }
                    }
                }
            }
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

/** Per-state badge on a translucent scrim so it stays legible over bright photos. */
@Composable
private fun SyncStateBadge(state: SyncState, modifier: Modifier = Modifier) {
    val (icon: ImageVector, description: String) = when (state) {
        SyncState.PENDING_UPLOAD -> Icons.Default.CloudUpload to stringResource(R.string.badge_pending_upload)
        SyncState.SYNCED -> Icons.Default.CloudDone to stringResource(R.string.badge_synced)
        SyncState.CLOUD_ONLY -> Icons.Default.Cloud to stringResource(R.string.badge_cloud_only)
        SyncState.PENDING_DELETE -> return // transient; no badge
    }
    val tint = when (state) {
        SyncState.PENDING_UPLOAD -> MaterialTheme.colorScheme.error
        SyncState.SYNCED -> Color(0xFF66BB6A)
        else -> Color.White
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun syncStatusLabel(status: SyncStatus): String = when (status) {
    is SyncStatus.Idle -> stringResource(R.string.app_name)
    is SyncStatus.Scanning -> stringResource(R.string.sync_scanning)
    is SyncStatus.Uploading -> stringResource(R.string.sync_uploading, status.done, status.total)
}
