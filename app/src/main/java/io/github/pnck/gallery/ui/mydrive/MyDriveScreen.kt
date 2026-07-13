package io.github.pnck.gallery.ui.mydrive

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import io.github.pnck.gallery.R
import io.github.pnck.gallery.provider.DriveEntry
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

/**
 * "My Drive": a separate, drive.readonly-gated browser over ALL file types + folders.
 * Images open a full-screen zoomable preview; any files can be multi-selected and
 * downloaded. Entirely apart from the least-privilege backup flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDriveScreen(
    onOpenDrawer: () -> Unit,
    viewModel: MyDriveViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    var pendingEvent by remember { mutableStateOf<MyDriveEvent?>(null) }
    LaunchedEffect(Unit) { viewModel.eventFlow.collect { pendingEvent = it } }
    val message = when (val e = pendingEvent) {
        is MyDriveEvent.Downloaded -> stringResource(R.string.mydrive_downloaded, e.ok, e.failed)
        null -> null
    }
    LaunchedEffect(message) {
        message?.let { snackbarHost.showSnackbar(it); pendingEvent = null }
    }

    // Back closes the preview, then walks up the folder stack, before leaving the tab.
    BackHandler(enabled = state.preview != null || state.stack.size > 1) {
        if (state.preview != null) viewModel.closePreview() else viewModel.goUp()
    }

    if (!state.granted) {
        MyDriveGate(
            onEnable = viewModel::enableBrowsing,
            onOpenDrawer = onOpenDrawer,
        )
        ElevateDialogs(state.elevating, context, viewModel::enableBrowsing, viewModel::cancelElevate)
        return
    }

    val inSubfolder = state.stack.size > 1
    val selecting = state.selection.isNotEmpty()
    val listState = rememberLazyListState()
    var filter by remember { mutableStateOf(DriveFilter.ALL) }

    // Client-side type filter; folders always stay visible so navigation still works.
    val shown = remember(state.entries, filter) {
        state.entries.filter { it.isFolder || filter.matches(it.mimeType) }
    }

    // Pick a destination folder (SAF, any volume) then download the selection into it.
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.downloadSelectedTo(uri)
    }

    // Infinite-ish paging: fetch the next page as the end approaches. loadMore() is a
    // no-op when there's no next page or a load is in flight, so over-calling is safe.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible -> if (lastVisible >= shown.size - 4) viewModel.loadMore() }
    }
    // When a filter hides most of a page, keep pulling pages so matches can surface.
    LaunchedEffect(filter, state.entries.size, state.nextPageToken) {
        if (state.nextPageToken != null && shown.size < 25) viewModel.loadMore()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.selection_clear))
                        }
                    },
                    title = { Text(stringResource(R.string.selection_count, state.selection.size)) },
                    actions = {
                        IconButton(onClick = { downloadPicker.launch(null) }) {
                            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.mydrive_download))
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { if (inSubfolder) viewModel.goUp() else onOpenDrawer() }) {
                            if (inSubfolder) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            } else {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_drawer))
                            }
                        }
                    },
                    title = {
                        Text(
                            if (inSubfolder) state.stack.last().name else stringResource(R.string.drawer_my_drive),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            FilterRow(selected = filter, onSelect = { filter = it })
            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading && state.entries.isEmpty() ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))

                    state.error != null && state.entries.isEmpty() ->
                        Text(
                            stringResource(R.string.mydrive_error, state.error!!),
                            Modifier.align(Alignment.Center).padding(24.dp),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )

                    shown.isEmpty() ->
                        Text(stringResource(R.string.mydrive_empty), Modifier.align(Alignment.Center))

                    else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                        items(shown, key = { it.id }) { entry ->
                            DriveRow(
                                entry = entry,
                                selected = entry.id in state.selection,
                                thumbModel = if (entry.isImage) "g_drive://${entry.id}" else null,
                                sizeText = entry.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
                                onClick = { if (selecting && !entry.isFolder) viewModel.toggleSelect(entry.id) else viewModel.open(entry) },
                                onLongClick = { if (!entry.isFolder) viewModel.toggleSelect(entry.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    state.preview?.let { entry ->
        ImagePreview(url = viewModel.originalUrl(entry.id), name = entry.name, onClose = viewModel::closePreview)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyDriveGate(onEnable: () -> Unit, onOpenDrawer: () -> Unit) {
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
            Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.mydrive_gate_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.mydrive_gate_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onEnable) { Text(stringResource(R.string.mydrive_enable)) }
        }
    }
}

@Composable
private fun ElevateDialogs(
    phase: ElevatePhase,
    context: android.content.Context,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    when (phase) {
        ElevatePhase.Idle -> Unit
        ElevatePhase.Requesting -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.auth_requesting_title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.size(16.dp))
                    Text(stringResource(R.string.auth_requesting_body))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        )
        is ElevatePhase.Approve -> AlertDialog(
            onDismissRequest = onCancel,
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
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, phase.challenge.verificationUrl.toUri())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }) { Text(stringResource(R.string.auth_device_open)) }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        )
        is ElevatePhase.Failed -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(stringResource(R.string.auth_error_title)) },
            text = { Text(phase.message, color = MaterialTheme.colorScheme.error) },
            confirmButton = { TextButton(onClick = onRetry) { Text(stringResource(R.string.auth_retry)) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveRow(
    entry: DriveEntry,
    selected: Boolean,
    thumbModel: String?,
    sizeText: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        leadingContent = {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                when {
                    selected -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    entry.isFolder -> Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    thumbModel != null -> AsyncImage(
                        model = thumbModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    else -> Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
                }
            }
        },
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = sizeText?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

/** File-type categories for the browser filter. Folders always show regardless. */
enum class DriveFilter(val labelRes: Int) {
    ALL(R.string.mydrive_filter_all),
    IMAGES(R.string.mydrive_filter_images),
    VIDEOS(R.string.mydrive_filter_videos),
    DOCS(R.string.mydrive_filter_docs),
    AUDIO(R.string.mydrive_filter_audio),
    OTHER(R.string.mydrive_filter_other),
    ;

    fun matches(mime: String): Boolean = when (this) {
        ALL -> true
        IMAGES -> mime.startsWith("image/")
        VIDEOS -> mime.startsWith("video/")
        AUDIO -> mime.startsWith("audio/")
        DOCS -> mime == "application/pdf" || mime.startsWith("text/") ||
            mime.startsWith("application/vnd.google-apps") ||
            mime.contains("word") || mime.contains("document") ||
            mime.contains("sheet") || mime.contains("presentation") || mime.contains("excel")
        OTHER -> !(mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/") ||
            mime == "application/pdf" || mime.startsWith("text/") ||
            mime.startsWith("application/vnd.google-apps") ||
            mime.contains("word") || mime.contains("document") ||
            mime.contains("sheet") || mime.contains("presentation") || mime.contains("excel"))
    }
}

@Composable
private fun FilterRow(selected: DriveFilter, onSelect: (DriveFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DriveFilter.entries.forEach { f ->
            FilterChip(
                selected = selected == f,
                onClick = { onSelect(f) },
                label = { Text(stringResource(f.labelRes)) },
            )
        }
    }
}

@Composable
private fun ImagePreview(url: String, name: String, onClose: () -> Unit) {
    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            ZoomableAsyncImage(
                model = url,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.back), tint = Color.White)
            }
        }
    }
}
