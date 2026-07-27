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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.work.WorkInfo
import coil3.compose.AsyncImage
import io.github.pnck.gallery.R
import io.github.pnck.gallery.domain.MediaBucket
import io.github.pnck.gallery.domain.SyncCounts
import io.github.pnck.gallery.domain.SyncFilter
import io.github.pnck.gallery.domain.SyncState
import io.github.pnck.gallery.domain.TimelineSort
import io.github.pnck.gallery.ui.util.FastScroller
import io.github.pnck.gallery.ui.util.ScrubModel
import io.github.pnck.gallery.ui.util.pinchToStep
import io.github.pnck.gallery.ui.util.rememberSystemDelete
import androidx.compose.ui.unit.Dp
import java.text.SimpleDateFormat
import java.util.Locale

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
    onOpenDrawer: () -> Unit = {},
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val photos by viewModel.photos.collectAsState()
    // Sort-paired list for grouping/scrubbing: `sort` (the UI selection) flips
    // before the new query lands; using the paired timeline.sort for cell building
    // means a stale list is never grouped by a sort it wasn't ordered with
    // (duplicate year-header keys → LazyGrid crash, the "size → date" flash-crash).
    val timeline by viewModel.timeline.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val selectionMode by viewModel.selectionActive.collectAsState()
    val deleteRequest by viewModel.deleteRequest.collectAsState()
    val deleteConfirm by viewModel.deleteConfirm.collectAsState()
    val freeRequest by viewModel.freeRequest.collectAsState()
    val syncCounts by viewModel.syncCounts.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val backupState by viewModel.backupState.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val syncFilter by viewModel.syncFilter.collectAsState()
    val scanBuckets by viewModel.scanBuckets.collectAsState()
    val buckets by viewModel.buckets.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val snackbarHost = remember { SnackbarHostState() }
    val systemDelete = rememberSystemDelete()
    // rememberSaveable (not plain remember): the grid leaves composition while the
    // detail viewer is open, and users expect to land back at the same scroll
    // position (Google-Photos behavior) — the nav back-stack entry's registry
    // carries it across the round trip.
    val gridState = androidx.compose.runtime.saveable.rememberSaveable(
        saver = androidx.compose.foundation.lazy.grid.LazyGridState.Saver,
    ) { androidx.compose.foundation.lazy.grid.LazyGridState() }

    var showStatus by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showViewOptions by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

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

    // "Free space for these photos": release the verified local copies via the system
    // dialog, then flip those rows to CLOUD_ONLY (they stay as a cloud preview).
    LaunchedEffect(freeRequest) {
        freeRequest?.let { uris ->
            systemDelete(uris.map { it.toUri() }) { viewModel.confirmFreed(uris) }
            viewModel.onFreeHandled()
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
        is TimelineEvent.FreeQueued -> stringResource(R.string.free_queued, event.count)
        is TimelineEvent.SpaceFreed -> stringResource(R.string.space_freed, event.count)
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
                    onClear = viewModel::exitSelectionMode,
                    onSelectAll = viewModel::selectAllVisible,
                    onSync = viewModel::syncSelected,
                    onSave = viewModel::saveSelected,
                    onFreeSpace = viewModel::freeSpaceSelected,
                    onDelete = viewModel::deleteSelected,
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.open_drawer))
                        }
                    },
                    title = {
                        // Plain title + a small spinner while syncing — the title is NOT a
                        // menu (see docs/GALLERY-UX-INTERACTION.md). Sync status lives in the
                        // overflow menu / status sheet.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.timeline_title))
                            if (syncStatus !is SyncStatus.Idle) {
                                Spacer(Modifier.width(10.dp))
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::enterSelectionMode) {
                            Icon(Icons.Default.Checklist, contentDescription = stringResource(R.string.action_select))
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.view_options)) },
                                onClick = { showMenu = false; showViewOptions = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.backup_now)) },
                                onClick = { showMenu = false; viewModel.backupNow() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sync_status_title)) },
                                onClick = { showMenu = false; showStatus = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                onClick = { showMenu = false; onSettingsClick() },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Selection count as a centered pill under the app bar — the bar itself
            // is packed with batch-action icons, a title there wraps and looks broken.
            if (selectionMode) {
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            stringResource(R.string.selection_count, selection.size),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            BackupBanner(
                state = backupState,
                onRetry = viewModel::backupNow,
                onPause = viewModel::pauseBackup,
                onResume = viewModel::resumeBackup,
            )
            if (photos.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.timeline_empty),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                // Group into year sections (Google-Photos style) when sorted by date;
                // other sorts (size) show a flat grid with no headers.
                val cells = remember(timeline) { buildTimelineCells(timeline.photos, timeline.sort) }
                Box(Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = cellSizes[cellIndex]),
                        modifier = Modifier.fillMaxSize().pinchToStep(
                            onZoomIn = { cellIndex = (cellIndex + 1).coerceAtMost(cellSizes.lastIndex) },
                            onZoomOut = { cellIndex = (cellIndex - 1).coerceAtLeast(0) },
                        ),
                    ) {
                        items(
                            count = cells.size,
                            key = { i -> when (val c = cells[i]) {
                                is GridCell.Header -> "h:${c.label}"
                                is GridCell.Item -> c.photo.id
                            } },
                            span = { i -> if (cells[i] is GridCell.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                        ) { i ->
                            when (val c = cells[i]) {
                                is GridCell.Header -> Text(
                                    c.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
                                )
                                is GridCell.Item -> PhotoCell(
                                    photo = c.photo,
                                    selectionMode = selectionMode,
                                    selected = c.photo.id in selection,
                                    onClick = {
                                        if (selectionMode) viewModel.toggleSelection(c.photo.id)
                                        else onPhotoClick(c.photo.id)
                                    },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.toggleSelection(c.photo.id)
                                    },
                                )
                            }
                        }
                    }
                    FastScroller(
                        state = gridState,
                        itemCount = cells.size,
                        model = remember(cells) { buildScrubModel(cells, timeline.sort) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
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
                onSyncNow = viewModel::backupNow,
                onRebuild = { showStatus = false; viewModel.rebuildSyncState() },
                onClearQueue = { showStatus = false; showClearConfirm = true },
            )
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_queue_title)) },
            text = { Text(stringResource(R.string.clear_queue_message, syncCounts.pendingUpload)) },
            confirmButton = {
                TextButton(onClick = { showClearConfirm = false; viewModel.clearBackupQueue() }) {
                    Text(stringResource(R.string.clear_queue_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showViewOptions) {
        ModalBottomSheet(onDismissRequest = { showViewOptions = false }) {
            ViewOptionsSheet(
                sort = sort,
                filter = syncFilter,
                folderCount = scanBuckets.size,
                onSortChange = viewModel::setSort,
                onFilterChange = viewModel::setSyncFilter,
                onFoldersClick = { showViewOptions = false; showFolders = true },
            )
        }
    }

    if (showFolders) {
        LaunchedEffect(Unit) { viewModel.refreshBuckets() }
        ModalBottomSheet(onDismissRequest = { showFolders = false }) {
            FoldersSheet(
                buckets = buckets,
                selected = scanBuckets,
                onApply = { ids -> viewModel.setScanFolders(ids); showFolders = false },
            )
        }
    }
}

@Composable
private fun ViewOptionsSheet(
    sort: TimelineSort,
    filter: SyncFilter,
    folderCount: Int,
    onSortChange: (TimelineSort) -> Unit,
    onFilterChange: (SyncFilter) -> Unit,
    onFoldersClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.sort_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        val sorts = listOf(
            TimelineSort.DATE_DESC to R.string.sort_date_desc,
            TimelineSort.DATE_ASC to R.string.sort_date_asc,
            TimelineSort.SIZE_DESC to R.string.sort_size_desc,
            TimelineSort.SIZE_ASC to R.string.sort_size_asc,
        )
        sorts.forEach { (value, label) ->
            OptionRow(selected = sort == value, label = stringResource(label)) { onSortChange(value) }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text(stringResource(R.string.filter_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        val filters = listOf(
            SyncFilter.ALL to R.string.filter_all,
            SyncFilter.NOT_BACKED_UP to R.string.filter_not_backed,
            SyncFilter.BACKED_UP to R.string.filter_backed,
            SyncFilter.CLOUD_ONLY to R.string.filter_cloud,
        )
        filters.forEach { (value, label) ->
            OptionRow(selected = filter == value, label = stringResource(label)) { onFilterChange(value) }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        ListItem(
            modifier = Modifier.clickable(onClick = onFoldersClick),
            leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
            headlineContent = { Text(stringResource(R.string.folders_row)) },
            supportingContent = {
                Text(
                    if (folderCount == 0) {
                        stringResource(R.string.folders_all)
                    } else {
                        stringResource(R.string.folders_selected, folderCount)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun OptionRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Directory picker = the scan allowlist (PRD §6.1). No folders checked means "all
 * folders"; checking a subset restricts both the grid AND what the scanner imports
 * and backs up. A local, editable copy is applied on confirm.
 */
@Composable
private fun FoldersSheet(
    buckets: List<MediaBucket>,
    selected: Set<String>,
    onApply: (Set<String>) -> Unit,
) {
    var working by remember(selected) { mutableStateOf(selected) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 16.dp)) {
        Text(
            stringResource(R.string.folders_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Text(
            stringResource(R.string.folders_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
            item {
                FolderRow(
                    name = stringResource(R.string.folders_all),
                    subtitle = null,
                    checked = working.isEmpty(),
                    onToggle = { working = emptySet() },
                )
            }
            items(buckets, key = { it.id }) { bucket ->
                val path = bucket.path
                FolderRow(
                    name = bucket.name,
                    subtitle = if (path != null) {
                        stringResource(R.string.folders_path_count, path, bucket.count)
                    } else {
                        stringResource(R.string.folders_photo_count, bucket.count)
                    },
                    checked = bucket.id in working,
                    onToggle = {
                        working = working.toMutableSet().apply { if (!add(bucket.id)) remove(bucket.id) }
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(
            onClick = { onApply(working) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) { Text(stringResource(R.string.folders_apply)) }
    }
}

@Composable
private fun FolderRow(name: String, subtitle: String?, checked: Boolean, onToggle: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onToggle),
        leadingContent = { Checkbox(checked = checked, onCheckedChange = { onToggle() }) },
        headlineContent = { Text(name) },
        supportingContent = subtitle?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
    )
}

/** A row in the sectioned timeline grid: a full-width date header or one photo. */
private sealed interface GridCell {
    data class Header(val label: String) : GridCell
    data class Item(val photo: io.github.pnck.gallery.domain.TimelinePhoto) : GridCell
}

/** Interleave year-MONTH section headers between photos when sorted by date
 *  (Google-Photos style). Size sorts show a flat grid — grouping is done by the
 *  scroller's size buckets instead. */
private fun buildTimelineCells(
    photos: List<io.github.pnck.gallery.domain.TimelinePhoto>,
    sort: TimelineSort,
): List<GridCell> {
    val dated = sort == TimelineSort.DATE_DESC || sort == TimelineSort.DATE_ASC
    if (!dated) return photos.map { GridCell.Item(it) }
    val out = ArrayList<GridCell>(photos.size + 32)
    // Header per FIRST occurrence of a year-month, not per consecutive change:
    // identical for correctly date-sorted input, and crash-proof (no duplicate
    // "h:<label>" grid keys) if the list is ever momentarily stale/mis-ordered.
    val seen = HashSet<Long>()
    for (p in photos) {
        val ym = yearOf(p.dateTaken) * 100L + monthOf(p.dateTaken)
        if (seen.add(ym)) {
            out += GridCell.Header(yearMonthLabel(p.dateTaken))
        }
        out += GridCell.Item(p)
    }
    return out
}

/**
 * The scrub model for the fast scroller: equal slots per period.
 *  - date sorts → ONE SLOT PER YEAR-MONTH, never collapsed (a 26-year library
 *    still snaps month by month; landing cell = that month's section header);
 *  - size sorts → DYNAMIC QUANTILE SLOTS: each slot holds an equal share of
 *    the photos and is labeled by its actual boundary size, so tick resolution
 *    follows the data (3.1/3.4/3.8 MB when everything clusters, wide gaps when
 *    it doesn't). Identical boundaries collapse; one size class → no scroller.
 */
private fun buildScrubModel(cells: List<GridCell>, sort: TimelineSort): ScrubModel? {
    val dated = sort == TimelineSort.DATE_DESC || sort == TimelineSort.DATE_ASC
    return if (dated) dateScrubModel(cells) else sizeScrubModel(cells)
}

private fun dateScrubModel(cells: List<GridCell>): ScrubModel? {
    val indices = ArrayList<Int>()
    val labels = ArrayList<String>()
    var lastYm = Long.MIN_VALUE
    var headerIdx = 0
    cells.forEachIndexed { index, cell ->
        when (cell) {
            is GridCell.Header -> headerIdx = index
            is GridCell.Item -> {
                val epochMs = cell.photo.dateTaken
                val ym = yearOf(epochMs) * 100L + monthOf(epochMs)
                if (ym != lastYm) {
                    lastYm = ym
                    indices += headerIdx
                    labels += yearMonthLabel(epochMs)
                }
            }
        }
    }
    if (labels.size < 2) return null
    return ScrubModel(indices.toIntArray(), labels)
}

private fun sizeScrubModel(cells: List<GridCell>): ScrubModel? {
    val items = ArrayList<Pair<Int, Long>>()
    cells.forEachIndexed { index, cell ->
        if (cell is GridCell.Item) items += index to cell.photo.sizeBytes
    }
    if (items.size < 2 || items.first().second == items.last().second) return null

    // Quantile boundaries: slot i starts at item (i * N / K) — equal PHOTO
    // COUNT per slot, so resolution concentrates where the data is dense.
    val k = 24.coerceAtMost(items.size)
    val indices = ArrayList<Int>(k)
    val labels = ArrayList<String>(k)
    var lastValue = Long.MIN_VALUE
    for (s in 0 until k) {
        val item = items[s * items.size / k]
        if (item.second == lastValue) continue // collapse identical boundaries
        lastValue = item.second
        indices += item.first
        labels += formatSize(item.second)
    }
    if (labels.size < 2) return null
    return ScrubModel(indices.toIntArray(), labels)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
}

private val yearCalendar = java.util.Calendar.getInstance()

private fun yearOf(epochMs: Long): Int {
    yearCalendar.timeInMillis = epochMs
    return yearCalendar.get(java.util.Calendar.YEAR)
}

private fun monthOf(epochMs: Long): Int {
    yearCalendar.timeInMillis = epochMs
    return yearCalendar.get(java.util.Calendar.MONTH) + 1
}

/** Year-month label for the fast-scroll bubble (e.g. "Jul 2026"). */
private val yearMonthFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())

private fun yearMonthLabel(epochMs: Long): String = yearMonthFormat.format(java.util.Date(epochMs))

/** One photo cell: thumbnail + sync badge + selection affordance. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCell(
    photo: io.github.pnck.gallery.domain.TimelinePhoto,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = photo.renderUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        SyncStateBadge(
            state = photo.syncState,
            excluded = photo.excluded,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        )
        if (selectionMode) {
            val accent = MaterialTheme.colorScheme.primary
            if (selected) {
                Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.25f)))
            }
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) accent else Color.White,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionAppBar(
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    onSync: () -> Unit,
    onSave: () -> Unit,
    onFreeSpace: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.selection_clear))
            }
        },
        // No title: the count lives in the centered banner below the bar — with five
        // batch-action icons, a title here crowds and wraps.
        title = { },
        actions = {
            // Primary batch actions (backup-first): Back up · Free up space · Delete.
            // Save-to-device is secondary → overflow (docs/GALLERY-UX-INTERACTION.md §3).
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.action_select_all))
            }
            IconButton(onClick = onSync) {
                Icon(Icons.Default.CloudUpload, contentDescription = stringResource(R.string.action_sync_selected))
            }
            IconButton(onClick = onFreeSpace) {
                Icon(Icons.Default.DeleteSweep, contentDescription = stringResource(R.string.action_free_selected))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete_selected))
            }
            var menu by remember { mutableStateOf(false) }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_save_selected)) },
                    onClick = { menu = false; onSave() },
                )
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
    onRebuild: () -> Unit,
    onClearQueue: () -> Unit,
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
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRebuild, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.rebuild_sync))
        }
        Text(
            stringResource(R.string.rebuild_sync_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (counts.pendingUpload > 0) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClearQueue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.clear_queue))
            }
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
private fun BackupBanner(
    state: BackupState,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    when (state) {
        is BackupState.Idle -> Unit

        is BackupState.Running -> Surface(color = MaterialTheme.colorScheme.primaryContainer) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
                IconButton(onClick = onPause) {
                    Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.backup_pause))
                }
            }
        }

        is BackupState.Paused -> Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Pause, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.backup_paused, state.count),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onResume) {
                    Text(stringResource(R.string.backup_resume))
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
private fun SyncStateBadge(state: SyncState, excluded: Boolean, modifier: Modifier = Modifier) {
    val (icon: ImageVector, description: String) = when {
        excluded -> Icons.Default.CloudOff to stringResource(R.string.badge_excluded)
        state == SyncState.PENDING_UPLOAD -> Icons.Default.CloudUpload to stringResource(R.string.badge_pending_upload)
        state == SyncState.SYNCED -> Icons.Default.CloudDone to stringResource(R.string.badge_synced)
        state == SyncState.CLOUD_ONLY -> Icons.Default.Cloud to stringResource(R.string.badge_cloud_only)
        else -> return // PENDING_DELETE: transient; no badge
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
