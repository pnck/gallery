package io.github.pnck.gallery.ui.settings.folders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.pnck.gallery.R

/**
 * "Library folders" — the library/backup scope picker as a full screen
 * (docs/GALLERY-UX-INTERACTION.md §5.4). One scrolling column, instant apply,
 * folder path + count per row so same-named folders and IM buckets are obvious.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFoldersScreen(
    onBack: () -> Unit,
    viewModel: LibraryFoldersViewModel = hiltViewModel(),
) {
    val buckets by viewModel.buckets.collectAsState()
    val selected by viewModel.selected.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.library_folders_title)) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.library_folders_purpose),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            item {
                ListItem(
                    modifier = Modifier.clickable(onClick = viewModel::selectAll),
                    leadingContent = {
                        Checkbox(checked = selected.isEmpty(), onCheckedChange = { viewModel.selectAll() })
                    },
                    headlineContent = { Text(stringResource(R.string.folders_all)) },
                )
                HorizontalDivider()
            }
            val list = buckets
            if (list == null) {
                item {
                    Text(
                        stringResource(R.string.library_folders_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                items(list, key = { it.id }) { bucket ->
                    ListItem(
                        modifier = Modifier.clickable { viewModel.toggle(bucket.id) },
                        leadingContent = {
                            Checkbox(checked = bucket.id in selected, onCheckedChange = { viewModel.toggle(bucket.id) })
                        },
                        headlineContent = { Text(bucket.name) },
                        supportingContent = {
                            val path = bucket.path // hoisted: cross-module vals can't smart-cast
                            Text(
                                when {
                                    bucket.videoCount > 0 && path != null ->
                                        stringResource(
                                            R.string.folders_path_photo_video_count,
                                            path,
                                            bucket.photoCount,
                                            bucket.videoCount,
                                        )
                                    bucket.videoCount > 0 ->
                                        stringResource(R.string.folders_photo_video_count, bucket.photoCount, bucket.videoCount)
                                    path != null ->
                                        stringResource(R.string.folders_path_count, path, bucket.photoCount)
                                    else -> stringResource(R.string.folders_photo_count, bucket.photoCount)
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
        }
    }
}
