package io.github.pnck.gallery.ui.navigation

import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
    // The swipe-out drawer only lives on the top-level tabs (My Photos / My Drive).
    val topLevel = route == Routes.TIMELINE || route == Routes.MY_DRIVE

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
                )
            }
            composable(Routes.TRANSPORT) {
                TransportScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.STORAGE) {
                SpaceManagementScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
