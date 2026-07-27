package io.github.pnck.gallery.ui.navigation

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
import io.github.pnck.gallery.ui.storage.SpaceManagementScreen
import io.github.pnck.gallery.ui.timeline.TimelineScreen
import kotlinx.coroutines.launch

/** NavHost (PRD §9.1): timeline / photo detail / settings, plus the transport debug screen. */
object Routes {
    const val TIMELINE = "timeline"
    const val MY_DRIVE = "mydrive"
    const val SETTINGS = "settings"
    const val TRANSPORT = "transport"
    const val STORAGE = "storage"
    const val DIAGNOSTICS = "diagnostics"
    const val PHOTO_DETAIL = "photo/{photoId}"

    fun photoDetail(photoId: String) = "photo/$photoId"
}

@Composable
fun GalleryNavHost() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    // Drawer gestures must track the SETTLED top-level tab, not the route alone:
    // the route flips the instant popBackStack is called, while the previous
    // screen is still animating out — enabling the drawer mid-transition lets it
    // hijack an in-flight multi-touch gesture (its drag detector joins the
    // stream without the down events) and exposes its items under the fingers,
    // so a stray two-finger touch could "tap" My Drive. The top entry only
    // reaches RESUMED when the transition completes — a pure projection, no
    // transition tracking of our own.
    val entryLifecycle by backStackEntry?.lifecycle?.currentStateFlow
        ?.collectAsState()
        ?: remember { mutableStateOf(Lifecycle.State.INITIALIZED) }
    val topLevel = (route == Routes.TIMELINE || route == Routes.MY_DRIVE) &&
        entryLifecycle == Lifecycle.State.RESUMED

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // gesturesEnabled = false deliberately: M3's ModalNavigationDrawer attaches
        // its drag detector to the WHOLE content box (any horizontal swipe anywhere
        // opens the drawer). Material Design opens a modal drawer from the LEADING
        // EDGE only — enforced below by an explicit 24.dp edge strip instead.
        gesturesEnabled = false,
        drawerContent = {
            GalleryDrawer(
                selected = route ?: Routes.TIMELINE,
                // Items only respond once the sheet has SETTLED open — see GalleryDrawer.
                interactionsEnabled = drawerState.isOpen && !drawerState.isAnimationRunning,
                onClose = { scope.launch { drawerState.close() } },
                onMyPhotos = {
                    scope.launch { drawerState.close() }
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
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.MY_DRIVE) {
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) {
        Box {
            NavHost(navController = navController, startDestination = Routes.TIMELINE) {
                composable(Routes.TIMELINE) {
                    TimelineScreen(
                        onPhotoClick = { photoId -> navController.navigate(Routes.photoDetail(photoId)) },
                        onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                        onOpenDrawer = openDrawer,
                    )
                }
                composable(Routes.MY_DRIVE) {
                    MyDriveScreen(
                        onOpenDrawer = openDrawer,
                        onSettingsClick = { navController.navigate(Routes.SETTINGS) },
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
                    )
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

            // The ONLY swipe-open zone (MD: leading edge). 24.dp ≈ the system gesture
            // strip; a rightward fling past the threshold springs the drawer open.
            if (topLevel) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val openThreshold = with(density) { 48.dp.toPx() }
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(24.dp)
                        .pointerInput(Unit) {
                            var acc = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { acc = 0f },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    acc += dragAmount
                                },
                                onDragEnd = {
                                    if (acc > openThreshold) scope.launch { drawerState.open() }
                                },
                            )
                        },
                )
            }
        }
    }
}
