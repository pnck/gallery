package io.github.pnck.gallery.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.pnck.gallery.ui.detail.PhotoDetailScreen
import io.github.pnck.gallery.ui.settings.SettingsScreen
import io.github.pnck.gallery.ui.timeline.TimelineScreen

/** Three-screen NavHost (PRD §9.1): timeline / photo detail / settings. */
object Routes {
    const val TIMELINE = "timeline"
    const val SETTINGS = "settings"
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
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
