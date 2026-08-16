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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** A pending delete the UI must route through the system delete dialog (PRD §7.3). */
data class DeleteRequest(val localUris: List<String>, val ids: List<String>)

/**
 * A delete awaiting the user's confirmation. [notBackedUp] counts photos with no
 * cloud copy — those are lost forever, so the dialog warns harder.
 */
data class DeleteConfirm(val ids: List<String>, val notBackedUp: Int)

/**
 * Scoped-storage local media deletion (PRD §7.3, invariant #7) — ONE action, AT
 * MOST one system dialog, never a chunked confirmation sequence (owner's hard
 * requirement). Three paths, best first:
 *
 *  1. Silent: [preferDirect] + MANAGE_MEDIA held (API 31+), or legacy-model
 *     WRITE_EXTERNAL_STORAGE held (API ≤29, e.g. the MI 9 baseline). Direct
 *     ContentResolver deletes, zero system UI — our in-app confirm (count +
 *     size / not-backed-up warning) is the one and only design-language
 *     confirmation.
 *  2. Android 11+ otherwise: a SINGLE MediaStore.createDeleteRequest system
 *     dialog for the whole batch — the only legal path on API 30 and the
 *     pre-grant path on 31+.
 *  3. Older devices without the write grant: best-effort direct delete.
 *
 * [onConfirmed] runs once with the confirmed uris (all of them, or empty on
 * cancel).
 */
@Composable
fun rememberSystemDelete(
    preferDirect: Boolean = false,
): (uris: List<Uri>, onConfirmed: (List<Uri>) -> Unit) -> Unit {
    val context = LocalContext.current
    val direct by rememberUpdatedState(preferDirect)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) pending?.invoke()
        pending = null
    }

    return remember {
        { uris, onConfirmed ->
            val silent = (direct && canManageMedia(context)) || hasLegacyWritePermission(context)
            when {
                uris.isEmpty() -> onConfirmed(emptyList())
                silent || Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> {
                    uris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                    onConfirmed(uris)
                }
                else -> {
                    pending = { onConfirmed(uris) }
                    val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
            }
        }
    }
}
