package io.github.pnck.gallery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * App theme (PRD §2.4). Uses a fixed brand palette rather than dynamic (wallpaper)
 * color so the UI is consistent across devices — the backup banner, multi-select
 * accents and buttons all share one blue seed.
 */
@Composable
fun GalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) GalleryDarkColors else GalleryLightColors,
        content = content,
    )
}
