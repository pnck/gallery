package io.github.pnck.gallery.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * A single fixed brand palette (blue) so the whole app is visually consistent —
 * the backup banner, the multi-select accents, buttons and progress all derive
 * from one seed instead of the wallpaper-driven dynamic (purple) default, which
 * clashed with the hard-coded blue selection accent.
 *
 * Tonal values follow Material 3's blue tonal palette.
 */
private val BrandLight = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF041E49),
    secondary = Color(0xFF00639B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE5FF),
    onSecondaryContainer = Color(0xFF001D33),
    tertiary = Color(0xFF3F6837),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC0EFB1),
    onTertiaryContainer = Color(0xFF002204),
    background = Color(0xFFFCFCFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
)

private val BrandDark = darkColorScheme(
    primary = Color(0xFFA8C7FA),
    onPrimary = Color(0xFF002F65),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF94CCFF),
    onSecondary = Color(0xFF003354),
    secondaryContainer = Color(0xFF004A77),
    onSecondaryContainer = Color(0xFFCFE5FF),
    tertiary = Color(0xFFA5D396),
    onTertiary = Color(0xFF11380C),
    tertiaryContainer = Color(0xFF285021),
    onTertiaryContainer = Color(0xFFC0EFB1),
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE2E2E6),
    inverseOnSurface = Color(0xFF2F3033),
)

/** The brand color schemes used by [GalleryTheme]. */
val GalleryLightColors = BrandLight
val GalleryDarkColors = BrandDark
