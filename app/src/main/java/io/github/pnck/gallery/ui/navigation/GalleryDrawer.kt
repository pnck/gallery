package io.github.pnck.gallery.ui.navigation

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.pnck.gallery.R

/**
 * The swipe-out side panel (per the agreed nav model): 【My Photos · My Drive · reserved】.
 *
 * "My Photos" is the default backup/album surface (least-privilege drive.file). "My
 * Drive" is a DELIBERATELY separate feature that requests broader Drive-read access on
 * its own — kept apart from the main flow so the least-privilege default is never
 * silently widened. The third slot is reserved.
 *
 * Width follows the MD3 modal-drawer spec (full width capped at the 360.dp token);
 * a leftward drag on the sheet closes it (the container's own drag gestures are
 * disabled — see GalleryNavHost — so swipe-to-close must live on the sheet).
 */
@Composable
fun GalleryDrawer(
    selected: String,
    onClose: () -> Unit,
    onMyPhotos: () -> Unit,
    onMyDrive: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier
            .widthIn(max = DrawerDefaults.MaximumDrawerWidth)
            .fillMaxWidth()
            .pointerInput(Unit) {
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragStart = { acc = 0f },
                    onHorizontalDrag = { _, dragAmount -> acc += dragAmount },
                    onDragEnd = { if (acc < -48.dp.toPx()) onClose() },
                )
            },
    ) {
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
            onClick = onMyPhotos,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.CloudQueue, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_my_drive)) },
            selected = selected == Routes.MY_DRIVE,
            onClick = onMyDrive,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
        NavigationDrawerItem(
            icon = { Icon(Icons.Default.MoreHoriz, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_reserved)) },
            selected = false,
            onClick = {},
            // Reserved slot — not yet a destination.
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
    }
}
