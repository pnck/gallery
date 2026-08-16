package io.github.pnck.gallery.ui.util

import android.app.Activity
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * Binder safety bound for one createDeleteRequest: each URI rides the intent
 * extras (~1 MB transaction cap), so a 20k-photo cleanup is chunked into a few
 * SEQUENTIAL system dialogs instead of one oversized (crashing) request — and
 * never one dialog per file.
 */
private const val DELETE_CHUNK = 1000

private data class PendingDelete(
    val chunks: List<List<Uri>>,
    val index: Int,
    val onConfirmed: (List<Uri>) -> Unit,
    /** The dialog for [index] has been launched — guards against recomposition re-fires. */
    val fired: Boolean = false,
)

/**
 * Scoped-storage local media deletion (PRD §7.3, invariant #7). On Android 11+
 * this shows the system delete dialog via MediaStore.createDeleteRequest —
 * ONE dialog per [DELETE_CHUNK]-item chunk, and [onConfirmed] runs per
 * confirmed chunk (so a mid-batch cancel keeps already-deleted chunks'
 * bookkeeping instead of pretending the whole batch went through). Older
 * devices fall back to a best-effort direct delete.
 *
 * Shared by "delete" (→ purge cloud + row) and "free up space" (→ CLOUD_ONLY),
 * which differ only in what [onConfirmed] does.
 */
@Composable
fun rememberSystemDelete(): (uris: List<Uri>, onConfirmed: (List<Uri>) -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<PendingDelete?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val p = pending
        if (result.resultCode == Activity.RESULT_OK && p != null) {
            p.onConfirmed(p.chunks[p.index])
            // Advance (or finish): the LaunchedEffect below fires the next dialog.
            pending = if (p.index + 1 < p.chunks.size) {
                p.copy(index = p.index + 1, fired = false)
            } else {
                null
            }
        } else {
            // Cancelled (or nothing pending): stop the batch — chunks already
            // confirmed stay confirmed, the rest never happens.
            pending = null
        }
    }

    // The request for the current chunk is fired from an effect, not from the
    // result callback — the callback can't reference its own launcher. (The SDK
    // guard looks redundant — pending is only set on R+ — but lint is right to
    // demand it here: this call site itself must be provably safe.)
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        if (p.fired || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@LaunchedEffect
        pending = p.copy(fired = true)
        val request = MediaStore.createDeleteRequest(context.contentResolver, p.chunks[p.index])
        launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    return remember {
        { uris, onConfirmed ->
            when {
                uris.isEmpty() -> onConfirmed(emptyList())
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    pending = PendingDelete(uris.chunked(DELETE_CHUNK), 0, onConfirmed)
                else -> {
                    uris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                    onConfirmed(uris)
                }
            }
        }
    }
}
