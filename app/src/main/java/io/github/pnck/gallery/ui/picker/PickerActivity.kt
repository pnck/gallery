package io.github.pnck.gallery.ui.picker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dagger.hilt.android.AndroidEntryPoint
import io.github.pnck.gallery.R
import io.github.pnck.gallery.domain.PhotoRepository
import io.github.pnck.gallery.domain.SyncFilter
import io.github.pnck.gallery.domain.TimelinePhoto
import io.github.pnck.gallery.domain.TimelineQuery
import io.github.pnck.gallery.domain.TimelineSort
import io.github.pnck.gallery.ui.theme.GalleryTheme
import io.github.pnck.gallery.ui.timeline.formatDuration
import javax.inject.Inject
import kotlinx.coroutines.flow.map

/**
 * System media picker (ACTION_PICK / ACTION_GET_CONTENT for image|video): other
 * apps get the device's local photos & videos through OUR grid instead of the
 * AOSP picker. Single-select only; the result is the MediaStore content:// uri
 * with a read grant, so the caller can open the bytes directly.
 *
 * Only items with a LOCAL copy are offered — a CLOUD_ONLY row would need a
 * network download the calling app never asked for.
 */
@AndroidEntryPoint
class PickerActivity : ComponentActivity() {

    @Inject lateinit var repo: PhotoRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The caller's mime narrows the grid: image/* hides videos and vice versa.
        val wantsVideo = intent.type?.startsWith("video/") == true
        val wantsImage = intent.type?.startsWith("image/") == true
        setContent {
            GalleryTheme {
                PickerScreen(
                    repo = repo,
                    showImages = !wantsVideo || wantsImage,
                    showVideos = !wantsImage || wantsVideo,
                    onPick = { uri ->
                        setResult(
                            RESULT_OK,
                            Intent().setData(Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScreen(
    repo: PhotoRepository,
    showImages: Boolean,
    showVideos: Boolean,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val photos by remember(repo, showImages, showVideos) {
        repo.getTimeline(TimelineQuery(sort = TimelineSort.DATE_DESC, filter = SyncFilter.ALL))
            .map { list ->
                list.filter { p ->
                    p.localUri != null && (if (p.isVideo) showVideos else showImages)
                }
            }
    }.collectAsState(initial = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        val list = photos
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding))
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.picker_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(list, key = { it.id }) { photo ->
                    PickerCell(photo, onClick = { photo.localUri?.let(onPick) })
                }
            }
        }
    }
}

@Composable
private fun PickerCell(photo: TimelinePhoto, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = photo.renderUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Same film+duration badge as the main wall.
        if (photo.isVideo) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Movie,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
                if (photo.durationMs > 0) {
                    Spacer(Modifier.width(3.dp))
                    Text(
                        formatDuration(photo.durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
