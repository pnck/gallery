package io.github.pnck.gallery.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.pnck.gallery.ui.detail.PhotoDetailScreen
import io.github.pnck.gallery.ui.settings.SettingsScreen
import io.github.pnck.gallery.ui.settings.TransportScreen
import io.github.pnck.gallery.ui.storage.SpaceManagementScreen
import io.github.pnck.gallery.ui.timeline.TimelineScreen

/** NavHost (PRD §9.1): timeline / photo detail / settings, plus the transport debug screen. */
object Routes {
    const val TIMELINE = "timeline"
    const val SETTINGS = "settings"
    const val TRANSPORT = "transport"
    const val STORAGE = "storage"
    const val PHOTO_DETAIL = "photo/{photoId}"

    fun photoDetail(photoId: String) = "photo/$photoId"
}

@Composable
fun GalleryNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.TIMELINE) {
        composable(Routes.TIMELINE) {
            TimelineScreen(
                onPhotoClick = { photoId -> navController.navigate(Routes.photoDetail(photoId)) },
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
