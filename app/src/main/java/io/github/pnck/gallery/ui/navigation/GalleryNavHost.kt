package io.github.pnck.gallery.ui.navigation

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
        gesturesEnabled = topLevel,
        drawerContent = {
            GalleryDrawer(
                selected = route ?: Routes.TIMELINE,
                onMyPhotos = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.TIMELINE) {
                        popUpTo(Routes.TIMELINE) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onMyDrive = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Routes.MY_DRIVE) { launchSingleTop = true }
                },
            )
        },
    ) {
        NavHost(navController = navController, startDestination = Routes.TIMELINE) {
            composable(Routes.TIMELINE) {
                TimelineScreen(
                    onPhotoClick = { photoId -> navController.navigate(Routes.photoDetail(photoId)) },
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                    onOpenDrawer = openDrawer,
                )
            }
            composable(Routes.MY_DRIVE) {
                MyDriveScreen(onOpenDrawer = openDrawer)
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
    }
}
