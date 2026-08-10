package io.github.pnck.gallery.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.pnck.gallery.BuildConfig
import io.github.pnck.gallery.diagnostics.DiagnosticsScreen
import io.github.pnck.gallery.ui.detail.PhotoDetailScreen
import io.github.pnck.gallery.ui.mydrive.MyDriveScreen
import io.github.pnck.gallery.ui.settings.SettingsScreen
import io.github.pnck.gallery.ui.settings.TransportScreen
import io.github.pnck.gallery.ui.settings.folders.LibraryFoldersScreen
import io.github.pnck.gallery.ui.storage.SpaceManagementScreen
import io.github.pnck.gallery.ui.timeline.TimelineScreen
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** NavHost (PRD §9.1): timeline / photo detail / settings, plus the transport debug screen. */
object Routes {
    const val TIMELINE = "timeline"
    const val MY_DRIVE = "mydrive"
    const val SETTINGS = "settings"
    const val TRANSPORT = "transport"
    const val STORAGE = "storage"
    const val DIAGNOSTICS = "diagnostics"
    const val LIBRARY_FOLDERS = "library_folders"
    const val PHOTO_DETAIL = "photo/{photoId}"

    fun photoDetail(photoId: String) = "photo/$photoId"
}

/**
 * Custom modal drawer replacing M3's ModalNavigationDrawer. M3 (1.4) attaches its
 * drag detector to the WHOLE content box — any horizontal swipe anywhere opened
 * the drawer — and exposes no public API for progressive dragging from a custom
 * (edge-only) zone. MD wants BOTH: leading-edge-only activation AND the sheet
 * following the finger in real time. One [Animatable] offset drives everything:
 *  - the 24.dp leading-edge strip drags it open (finger-tracked, settle on release);
 *  - the sheet itself drags it closed the same way;
 *  - items only respond when the offset is fully settled open (see GalleryDrawer);
 *  - scrim fades with the same fraction and taps close it; BackHandler too.
 */
@Composable
fun GalleryNavHost() {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    // Drawer affordances respond ONLY when the top-level destination has fully
    // settled (route match + RESUMED): a fast second tap on the top-left spot
    // while the previous screen is still transitioning out hits a disabled
    // hamburger (no-op) instead of opening the drawer under the gesture.
    val entryLifecycle by backStackEntry?.lifecycle?.currentStateFlow
        ?.collectAsState()
        ?: remember { mutableStateOf(Lifecycle.State.INITIALIZED) }
    val topLevel = (route == Routes.TIMELINE || route == Routes.MY_DRIVE) &&
        entryLifecycle == Lifecycle.State.RESUMED

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // MD3 modal drawer width: full width capped at the 360.dp ContainerWidth token.
        val sheetWidthPx = minOf(
            with(density) { DrawerDefaults.MaximumDrawerWidth.toPx() },
            constraints.maxWidth.toFloat(),
        )
        val closedX = -sheetWidthPx
        val offset = remember { Animatable(closedX) }
        // 0f = shut … 1f = fully open, derived from the ONE offset (never tracked apart).
        val fraction = ((offset.value - closedX) / sheetWidthPx).coerceIn(0f, 1f)
        val settledOpen = offset.value > -8f

        val openDrawer: () -> Unit = { scope.launch { offset.animateTo(0f, tween(300)) } }
        val closeDrawer: () -> Unit = { scope.launch { offset.animateTo(closedX, tween(250)) } }

        /** Finger-tracked horizontal drag driving the shared offset; settles by position. */
        fun Modifier.drawerDrag() = pointerInput(sheetWidthPx) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    scope.launch { offset.snapTo((offset.value + dragAmount).coerceIn(closedX, 0f)) }
                },
                onDragEnd = {
                    scope.launch {
                        val target = if (offset.value > closedX / 2f) 0f else closedX
                        offset.animateTo(target, tween(200))
                    }
                },
            )
        }

        NavHost(navController = navController, startDestination = Routes.TIMELINE) {
            composable(Routes.TIMELINE) {
                TimelineScreen(
                    onPhotoClick = { photoId -> navController.navigate(Routes.photoDetail(photoId)) },
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                    onOpenDrawer = openDrawer,
                    drawerEnabled = topLevel,
                )
            }
            composable(Routes.MY_DRIVE) {
                MyDriveScreen(
                    onOpenDrawer = openDrawer,
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                    drawerEnabled = topLevel,
                )
            }
            composable(
                route = Routes.PHOTO_DETAIL,
                arguments = listOf(navArgument("photoId") { type = NavType.StringType }),
            ) { backStackEntry ->
                PhotoDetailScreen(
                    photoId = backStackEntry.arguments?.getString("photoId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onTransportClick = { navController.navigate(Routes.TRANSPORT) },
                    onStorageClick = { navController.navigate(Routes.STORAGE) },
                    onDiagnosticsClick = { navController.navigate(Routes.DIAGNOSTICS) },
                    onLibraryFoldersClick = { navController.navigate(Routes.LIBRARY_FOLDERS) },
                )
            }
            composable(Routes.LIBRARY_FOLDERS) {
                LibraryFoldersScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.TRANSPORT) {
                TransportScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.STORAGE) {
                SpaceManagementScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DIAGNOSTICS) {
                DiagnosticsScreen(onBack = { navController.popBackStack() })
            }
        }

        // Scrim: fades with the same offset fraction; a tap on it closes the drawer.
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f * fraction))
                    .pointerInput(settledOpen) {
                        if (settledOpen) detectTapGestures { closeDrawer() }
                    },
            )
        }

        // The sheet: always composed (offscreen when shut), finger-draggable to close.
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(with(density) { sheetWidthPx.toDp() })
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .drawerDrag(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
        ) {
            GalleryDrawer(
                selected = route ?: Routes.TIMELINE,
                interactionsEnabled = settledOpen,
                onMyPhotos = {
                    closeDrawer()
                    // My Drive always sits ON TOP of the timeline — going back to
                    // My Photos is a pop, never a re-create: the timeline entry (its
                    // ViewModel, scroll state, paging) survives, and My Drive's own
                    // state is saved for restoreState on the way back. The old code
                    // popped the START destination itself (inclusive), momentarily
                    // emptying the back stack → NavHost composed zero destinations
                    // → the "black screen after switching tabs" report.
                    if (!navController.popBackStack(Routes.TIMELINE, inclusive = false, saveState = true)) {
                        navController.navigate(Routes.TIMELINE) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                onMyDrive = {
                    closeDrawer()
                    navController.navigate(Routes.MY_DRIVE) {
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }

        // The ONLY swipe-open zone (MD: leading edge). 24.dp, finger-tracked.
        if (topLevel) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(24.dp)
                    .drawerDrag(),
            )
        }

        BackHandler(enabled = fraction > 0.5f) { closeDrawer() }
    }
}
