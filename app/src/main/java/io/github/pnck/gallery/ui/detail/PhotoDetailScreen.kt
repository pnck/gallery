package io.github.pnck.gallery.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import me.saket.telephoto.zoomable.rememberZoomableImageState
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.pnck.gallery.R
import io.github.pnck.gallery.domain.SyncState
import io.github.pnck.gallery.domain.TimelinePhoto
import io.github.pnck.gallery.ui.util.openPermissionEditor
import io.github.pnck.gallery.ui.util.rememberDeleteGrantRequest
import io.github.pnck.gallery.ui.util.rememberSystemDelete
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

/**
 * Full-screen viewer (T-403, PRD §9.1): a HorizontalPager of the timeline with
 * Telephoto zoom/pan (tile sub-sampling → no OOM on huge originals), 90° rotation,
 * and edit / share / save-to-device actions. CLOUD_ONLY originals download to
 * cacheDir only, never DCIM (invariant #9).
 */
// Full-bleed immersive viewer: chrome is overlaid, so the Scaffold's content
// padding is intentionally ignored (the photo goes edge-to-edge).
@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailScreen(
    photoId: String,
    onBack: () -> Unit,
    viewModel: PhotoDetailViewModel = hiltViewModel(),
) {
    val photos by viewModel.photos.collectAsState()
    val originals by viewModel.originals.collectAsState()
    val deleteRequest by viewModel.deleteRequest.collectAsState()
    val deleteConfirm by viewModel.deleteConfirm.collectAsState()
    val info by viewModel.info.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val storageTreeUri by viewModel.storageTreeUri.collectAsState()
    val requestDeleteGrant = rememberDeleteGrantRequest(
        onTreeGranted = { viewModel.saveStorageTree(it.toString()) },
        onRequestRuntimePermissions = { (context as? android.app.Activity)?.let(::openPermissionEditor) },
    )
    val systemDelete = rememberSystemDelete(treeUri = storageTreeUri, onGrantMissing = requestDeleteGrant)
    var menuExpanded by remember { mutableStateOf(false) }

    // Immersive chrome + swipe-down-to-dismiss (Google-Photos style).
    var chromeVisible by remember { mutableStateOf(true) }
    val dismissOffset = remember { Animatable(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { 140.dp.toPx() }
    val dismissProgress = (abs(dismissOffset.value) / dismissThresholdPx).coerceIn(0f, 1f)

    val pagerState = rememberPagerState(pageCount = { photos.size })

    // Resolve strings in composition (lint: no LocalContext.getString in effects).
    val msgSaved = stringResource(R.string.detail_saved)
    val msgSaveFailed = stringResource(R.string.detail_save_failed)
    val msgDownloadFailed = stringResource(R.string.detail_download_failed)
    val msgNoEditor = stringResource(R.string.detail_no_editor)
    val msgDeleted = stringResource(R.string.detail_deleted)
    val shareTitle = stringResource(R.string.detail_share_title)
    val editTitle = stringResource(R.string.detail_edit)

    // Per-page 90° rotation, hoisted so the bottom bar can drive the visible page.
    val rotations = remember { mutableStateMapOf<Int, Float>() }

    // Jump to the tapped photo once the snapshot has loaded (only once).
    var didInit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(photos.size) {
        if (!didInit && photos.isNotEmpty()) {
            val index = photos.indexOfFirst { it.id == photoId }.coerceAtLeast(0)
            pagerState.scrollToPage(index)
            didInit = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            val message = when (event) {
                DetailEvent.Saved -> msgSaved
                DetailEvent.SaveFailed -> msgSaveFailed
                DetailEvent.DownloadFailed -> msgDownloadFailed
                DetailEvent.NoEditor -> msgNoEditor
                DetailEvent.Deleted -> msgDeleted
            }
            snackbarHost.showSnackbar(message)
        }
    }

    // Route a pending delete through the system dialog, then purge cloud + row.
    LaunchedEffect(deleteRequest) {
        deleteRequest?.let { request ->
            systemDelete(request.localUris.map { it.toUri() }) { _ -> viewModel.purge(request.ids) }
            viewModel.onDeleteHandled()
        }
    }

    val current = photos.getOrNull(pagerState.currentPage)

    fun withUri(photo: TimelinePhoto, block: (Uri) -> Unit) {
        scope.launch {
            val uri = viewModel.shareableUri(photo)
            if (uri == null) snackbarHost.showSnackbar(msgDownloadFailed)
            else block(Uri.parse(uri))
        }
    }

    fun share(photo: TimelinePhoto) = withUri(photo) { uri ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, shareTitle))
    }

    fun edit(photo: TimelinePhoto) = withUri(photo) { uri ->
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, editTitle))
        } catch (_: ActivityNotFoundException) {
            viewModel.reportNoEditor()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - 0.6f * dismissProgress))) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHost) },
        ) { _ ->
          Box(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val photo = photos.getOrNull(page) ?: return@HorizontalPager
                // Kick off the cloud download for pages the user actually reaches.
                LaunchedEffect(photo.id) { viewModel.ensureOriginal(photo) }

                val model: String? = photo.localUri ?: (originals[photo.id] as? OriginalState.Ready)?.uri
                val zoomState = rememberZoomableImageState()
                val notZoomed = (zoomState.zoomableState.zoomFraction ?: 0f) < 0.01f

                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = dismissOffset.value
                            val p = dismissProgress
                            scaleX = 1f - 0.1f * p
                            scaleY = 1f - 0.1f * p
                            alpha = 1f - 0.3f * p
                        }
                        // Swipe down to dismiss — only when the image isn't zoomed,
                        // so Telephoto still owns pan/zoom otherwise.
                        .then(
                            if (notZoomed && !photo.isVideo) {
                                Modifier.pointerInput(Unit) {
                                    detectVerticalDragGestures(
                                        onVerticalDrag = { change, dy ->
                                            change.consume()
                                            scope.launch { dismissOffset.snapTo(dismissOffset.value + dy) }
                                        },
                                        onDragEnd = {
                                            if (abs(dismissOffset.value) > dismissThresholdPx) onBack()
                                            else scope.launch { dismissOffset.animateTo(0f) }
                                        },
                                        onDragCancel = { scope.launch { dismissOffset.animateTo(0f) } },
                                    )
                                }
                            } else {
                                Modifier
                            },
                        ),
                        // Two-finger rotation gesture DISABLED (owner: it fights the
                        // horizontal pager swipe). Rotation stays available as the
                        // top-bar control only.
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        photo.isVideo -> {
                            // Controls-only player (play/pause/seek — no gestures);
                            // rotation rides the top-bar control like images do.
                            val videoUri = photo.localUri ?: (originals[photo.id] as? OriginalState.Ready)?.uri
                            if (videoUri != null) {
                                VideoPlayerPage(
                                    uri = videoUri,
                                    rotationDeg = rotations[page] ?: 0f,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (originals[photo.id] is OriginalState.Failed) {
                                Text(stringResource(R.string.detail_download_failed), color = Color.White)
                            } else {
                                CircularProgressIndicator(color = Color.White)
                            }
                        }
                        model != null -> ZoomableAsyncImage(
                            model = model,
                            contentDescription = null,
                            state = zoomState,
                            onClick = { chromeVisible = !chromeVisible },
                            modifier = Modifier.fillMaxSize().rotate(rotations[page] ?: 0f),
                        )
                        originals[photo.id] is OriginalState.Failed ->
                            Text(stringResource(R.string.detail_download_failed), color = Color.White)
                        else -> CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            // Chrome overlaid at the TOP (immersive): tapping the photo toggles it;
            // it hides while dragging to dismiss. Overlaying keeps the photo full-bleed.
            AnimatedVisibility(
                visible = chromeVisible && dismissProgress < 0.02f,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.4f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        if (current != null) {
                            // One rotate control for both media kinds: images rotate
                            // the canvas, videos rotate decoder output (VideoPlayerPage).
                            IconButton(onClick = {
                                val p = pagerState.currentPage
                                rotations[p] = snapTo90((rotations[p] ?: 0f) + 90f)
                            }) {
                                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = stringResource(R.string.detail_rotate))
                            }
                            IconButton(onClick = { share(current) }) {
                                Icon(Icons.Default.Share, contentDescription = stringResource(R.string.detail_share))
                            }
                            IconButton(onClick = { viewModel.requestDelete(current) }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.detail_delete))
                            }
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more))
                                }
                                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.detail_edit)) },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                        onClick = { menuExpanded = false; edit(current) },
                                    )
                                    if (current.syncState == SyncState.CLOUD_ONLY) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.detail_save)) },
                                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                                            onClick = { menuExpanded = false; viewModel.save(current) },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.detail_info)) },
                                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                                        onClick = { menuExpanded = false; viewModel.showInfo(current) },
                                    )
                                }
                            }
                        }
                    },
                )
            }
          }
        }
    }

    info?.let { data ->
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissInfo,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            PhotoInfoSheet(data)
        }
    }

    deleteConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.delete_confirm_title, confirm.ids.size)) },
            text = {
                Column {
                    Text(stringResource(R.string.delete_confirm_message))
                    if (confirm.notBackedUp > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.delete_confirm_unsynced, confirm.notBackedUp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.delete_confirm_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PhotoInfoSheet(info: PhotoInfo) {
    val rows = buildList {
        add(stringResource(R.string.info_dimensions) to "${info.width} × ${info.height}")
        if (info.dateTakenMs > 0) add(stringResource(R.string.info_date) to formatDate(info.dateTakenMs))
        info.sizeBytes?.let { add(stringResource(R.string.info_size) to formatSize(it)) }
        add(stringResource(R.string.info_state) to syncStateLabel(info.syncState))
        info.provider?.let { add(stringResource(R.string.info_storage) to it) }
        info.folder?.let { add(stringResource(R.string.info_folder) to it) }
        info.mediaId?.let { add(stringResource(R.string.info_media_id) to it) }
        val camera = listOfNotNull(info.cameraMake, info.cameraModel).joinToString(" ").ifBlank { null }
        camera?.let { add(stringResource(R.string.info_camera) to it) }
        info.aperture?.let { add(stringResource(R.string.info_aperture) to it) }
        info.exposure?.let { add(stringResource(R.string.info_shutter) to it) }
        info.iso?.let { add(stringResource(R.string.info_iso) to it) }
        info.focalLength?.let { add(stringResource(R.string.info_focal) to it) }
        info.latLon?.let { add(stringResource(R.string.info_location) to "%.5f, %.5f".format(it.first, it.second)) }
        info.contentHash?.let { add(stringResource(R.string.info_hash) to it) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.info_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(
                    label,
                    modifier = Modifier.width(120.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun syncStateLabel(state: SyncState): String = when (state) {
    SyncState.PENDING_UPLOAD -> stringResource(R.string.badge_pending_upload)
    SyncState.SYNCED -> stringResource(R.string.badge_synced)
    SyncState.CLOUD_ONLY -> stringResource(R.string.badge_cloud_only)
    SyncState.PENDING_DELETE -> stringResource(R.string.detail_delete)
}

private fun formatDate(ms: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

private fun formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return if (kb < 1024) "%.0f KB".format(kb) else "%.1f MB".format(kb / 1024)
}

/** Snap any angle to the nearest 90° step (used by the toolbar rotate control). */
private fun snapTo90(angle: Float): Float = ((angle % 360f) + 360f) % 360f
