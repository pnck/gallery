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
 * requirement). Paths, best first:
 *
 *  1. Silent, [preferDirect] callers: MANAGE_MEDIA held (API 31+), a SAF
 *     document-tree grant held (API 30 — legacy storage is hard-blocked there
 *     for targetSdk 30+, so the tree grant is the gallery-app standard), or
 *     legacy-model WRITE_EXTERNAL_STORAGE held (API ≤29, e.g. the MI 9
 *     baseline). Zero system UI — our in-app confirm (count + size /
 *     not-backed-up warning) is the one and only design-language confirmation.
 *  2. A SINGLE MediaStore.createDeleteRequest dialog for the whole batch
 *     (API 30+ without any silent grant).
 *  3. Best-effort direct delete (API ≤29 without the write grant).
 *
 * [onConfirmed] runs once with the uris actually deleted (empty on cancel).
 */
@Composable
fun rememberSystemDelete(
    preferDirect: Boolean = false,
    /** Persisted SAF tree grant (settings.storageTreeUri) — the API-30 silent path. */
    treeUri: String? = null,
): (uris: List<Uri>, onConfirmed: (List<Uri>) -> Unit) -> Unit {
    val context = LocalContext.current
    val direct by rememberUpdatedState(preferDirect)
    val tree by rememberUpdatedState(treeUri)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) pending?.invoke()
        pending = null
    }

    return remember {
        { uris, onConfirmed ->
            val treeDelete = direct &&
                Build.VERSION.SDK_INT == Build.VERSION_CODES.R &&
                hasStorageTreeAccess(context, tree)
            when {
                uris.isEmpty() -> onConfirmed(emptyList())
                // Silent: confirm only the uris that ACTUALLY deleted.
                (direct && canManageMedia(context)) || hasLegacyWritePermission(context) -> {
                    val resolver = context.contentResolver
                    onConfirmed(
                        uris.filter { runCatching { resolver.delete(it, null, null) > 0 }.getOrDefault(false) },
                    )
                }
                treeDelete -> {
                    onConfirmed(uris.filter { deleteViaStorageTree(context, it, tree.orEmpty()) })
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    pending = { onConfirmed(uris) }
                    val request = MediaStore.createDeleteRequest(context.contentResolver, uris)
                    launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
                else -> {
                    val resolver = context.contentResolver
                    onConfirmed(
                        uris.filter { runCatching { resolver.delete(it, null, null) > 0 }.getOrDefault(false) },
                    )
                }
            }
        }
    }
}
