package io.github.pnck.gallery.ui.detail

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.pnck.gallery.R

/**
 * Full-screen viewer (T-403, PRD §9.1). Skeleton.
 *
 * Contract when implemented:
 *  - HorizontalPager for swiping; Telephoto ZoomableAsyncImage (coil3 variant) for
 *    zoom/pan — no hand-rolled graphicsLayer gestures; sub-sampling prevents OOM
 *  - each page owns its rememberZoomableState; reset zoom when settledPage != page
 *  - CLOUD_ONLY originals download to context.cacheDir, never DCIM
 *  - bottom action bar: EXIF, delete (→ PENDING_DELETE)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailScreen(
    photoId: String,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.photo_detail)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        // T-403: ZoomableAsyncImage(photo.renderUri) inside a HorizontalPager.
        Text(
            text = "photo: $photoId",
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}
