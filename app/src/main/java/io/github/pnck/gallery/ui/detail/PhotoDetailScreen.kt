package io.github.pnck.gallery.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.pnck.gallery.R
import io.github.pnck.gallery.domain.SyncState
import io.github.pnck.gallery.domain.TimelinePhoto
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val systemDelete = rememberSystemDelete()

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
            systemDelete(request.localUris.map { it.toUri() }) { viewModel.purge(request.ids) }
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

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.4f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
                title = { Text(stringResource(R.string.photo_detail)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        bottomBar = {
            if (current != null) {
                DetailActionBar(
                    photo = current,
                    onRotate = {
                        val p = pagerState.currentPage
                        rotations[p] = ((rotations[p] ?: 0f) + 90f) % 360f
                    },
                    onEdit = { edit(current) },
                    onShare = { share(current) },
                    onSave = { viewModel.save(current) },
                    onDelete = { viewModel.requestDelete(current) },
                )
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { page ->
            val photo = photos.getOrNull(page) ?: return@HorizontalPager
            // Kick off the cloud download for pages the user actually reaches.
            LaunchedEffect(photo.id) { viewModel.ensureOriginal(photo) }

            val model: String? = photo.localUri ?: (originals[photo.id] as? OriginalState.Ready)?.uri

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    model != null -> ZoomableAsyncImage(
                        model = model,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().rotate(rotations[page] ?: 0f),
                    )
                    originals[photo.id] is OriginalState.Failed ->
                        Text(stringResource(R.string.detail_download_failed), color = Color.White)
                    else -> CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun DetailActionBar(
    photo: TimelinePhoto,
    onRotate: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = onRotate) {
            Icon(Icons.Default.RotateRight, contentDescription = stringResource(R.string.detail_rotate), tint = Color.White)
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.detail_edit), tint = Color.White)
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.detail_share), tint = Color.White)
        }
        // Save-to-device only makes sense while the local copy is gone (CLOUD_ONLY).
        if (photo.syncState == SyncState.CLOUD_ONLY) {
            IconButton(onClick = onSave) {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.detail_save), tint = Color.White)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.detail_delete), tint = Color.White)
        }
    }
}
