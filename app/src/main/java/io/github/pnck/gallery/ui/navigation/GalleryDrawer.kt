package io.github.pnck.gallery.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.pnck.gallery.R

/**
 * The side panel's CONTENT (the chrome — offset, scrim, drag zones — lives in
 * GalleryNavHost's custom drawer): 【My Photos · My Drive · reserved】.
 *
 * "My Photos" is the default backup/album surface (least-privilege drive.file). "My
 * Drive" is a DELIBERATELY separate feature that requests broader Drive-read access on
 * its own — kept apart from the main flow so the least-privilege default is never
 * silently widened. The third slot is reserved.
 *
 * [interactionsEnabled] is false while the sheet is still sliding in: a tap that
 * OPENED the drawer (e.g. a fast double-tap on the top-left back/hamburger spot)
 * must not be able to select an item mid-stream — the sheet slides in under the
 * still-active gesture and would navigate somewhere the user never aimed at.
 */
@Composable
fun GalleryDrawer(
    selected: String,
    interactionsEnabled: Boolean,
    onMyPhotos: () -> Unit,
    onMyDrive: () -> Unit,
) {
    // statusBarsPadding first: MD3 keeps drawer content clear of the system bar
    // (the title previously sat almost against it).
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_my_photos)) },
            selected = selected == Routes.TIMELINE,
            onClick = { if (interactionsEnabled) onMyPhotos() },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.CloudQueue, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_my_drive)) },
            selected = selected == Routes.MY_DRIVE,
            onClick = { if (interactionsEnabled) onMyDrive() },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
    }
}
