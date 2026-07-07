package io.github.pnck.gallery.ui.timeline

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import io.github.pnck.gallery.R

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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(
    onPhotoClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: TimelineViewModel = hiltViewModel(),
) {
    val photos = viewModel.photosFlow.collectAsLazyPagingItems()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val context = LocalContext.current

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

    Scaffold(
        topBar = {
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
                    Box(Modifier.aspectRatio(1f)) {
                        AsyncImage(
                            model = photo.renderUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { onPhotoClick(photo.id) },
                        )
                        if (photo.showCloudIcon) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun syncStatusLabel(status: SyncStatus): String = when (status) {
    is SyncStatus.Idle -> stringResource(R.string.app_name)
    is SyncStatus.Scanning -> stringResource(R.string.sync_scanning)
    is SyncStatus.Uploading -> stringResource(R.string.sync_uploading, status.done, status.total)
}
