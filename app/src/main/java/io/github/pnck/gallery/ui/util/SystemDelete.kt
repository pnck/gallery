package io.github.pnck.gallery.ui.util

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** A pending delete the UI must route through the system delete dialog (PRD §7.3). */
data class DeleteRequest(val localUris: List<String>, val ids: List<String>)

/**
 * Scoped-storage local media deletion (PRD §7.3, invariant #7). On Android 11+
 * this shows the system delete dialog via MediaStore.createDeleteRequest; the
 * [onConfirmed] callback runs only after the user approves. Older devices fall
 * back to a best-effort direct delete.
 *
 * Shared by "delete" (→ purge cloud + row) and "free up space" (→ CLOUD_ONLY),
 * which differ only in what [onConfirmed] does.
 */
@Composable
fun rememberSystemDelete(): (uris: List<Uri>, onConfirmed: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) pending?.invoke()
        pending = null
    }

    return remember {
        { uris, onConfirmed ->
            when {
                uris.isEmpty() -> onConfirmed()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    pending = onConfirmed
                    val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
                else -> {
                    uris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                    onConfirmed()
                }
            }
        }
    }
}
