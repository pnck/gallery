package io.github.pnck.gallery.ui.util

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** A pending delete the UI must route through the system delete dialog (PRD §7.3). */
data class DeleteRequest(val localUris: List<String>, val ids: List<String>)

/**
 * A delete awaiting the user's confirmation. [notBackedUp] counts photos with no
 * cloud copy — those are lost forever, so the dialog warns harder.
 */
data class DeleteConfirm(val ids: List<String>, val notBackedUp: Int)

/**
 * Local media deletion (PRD §7.3, invariant #7). OWNER'S HARD RULE: the system
 * delete dialog (createDeleteRequest) is BANNED in every form — no chunked
 * confirmations, no single batch dialog, no fallback. Deletion is either
 * silent (a one-time grant is held) or it DOES NOT RUN and the user is taken
 * to the grant instead ([onGrantMissing]).
 *
 * Silent paths, by platform:
 *  - API 31+: MANAGE_MEDIA (one-time system-settings grant);
 *  - API 30:  SAF document-tree grant (one-time picker);
 *  - API ≤29: legacy-model WRITE_EXTERNAL_STORAGE (runtime prompt, one time).
 *
 * [onConfirmed] runs once with the uris actually deleted.
 */
@Composable
fun rememberSystemDelete(
    /** Persisted SAF tree grant (settings.storageTreeUri) — the API-30 silent path. */
    treeUri: String?,
    /** No silent grant held: take the user to the right grant UI. No deletion happens. */
    onGrantMissing: () -> Unit,
): (uris: List<Uri>, onConfirmed: (List<Uri>) -> Unit) -> Unit {
    val context = LocalContext.current
    val tree by rememberUpdatedState(treeUri)

    return remember {
        { uris, onConfirmed ->
            when {
                uris.isEmpty() -> onConfirmed(emptyList())
                // Silent: confirm only the uris that ACTUALLY deleted.
                canManageMedia(context) || hasLegacyWritePermission(context) -> {
                    val resolver = context.contentResolver
                    onConfirmed(
                        uris.filter { runCatching { resolver.delete(it, null, null) > 0 }.getOrDefault(false) },
                    )
                }
                Build.VERSION.SDK_INT == Build.VERSION_CODES.R && hasStorageTreeAccess(context, tree) -> {
                    onConfirmed(uris.filter { deleteViaStorageTree(context, it, tree.orEmpty()) })
                }
                else -> onGrantMissing()
            }
        }
    }
}

/**
 * The platform-correct grant request for the silent-delete capability:
 * MANAGE_MEDIA settings (31+), the SAF tree picker (30), or the media runtime
 * permission prompt (≤29, which includes WRITE under the legacy model).
 */
@Composable
fun rememberDeleteGrantRequest(
    /** Persist the picked tree uri (settings + takePersistableUriPermission is done here). */
    onTreeGranted: (Uri) -> Unit,
    /** ≤29 path: launch the runtime permission prompt (the screen's own launcher). */
    onRequestRuntimePermissions: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persistStorageTree(context, uri)
            onTreeGranted(uri)
        }
    }
    return remember {
        {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    (context as? android.app.Activity)?.let(::openManageMediaSettings)
                Build.VERSION.SDK_INT == Build.VERSION_CODES.R ->
                    treeLauncher.launch(storageRootTreeUri())
                else -> onRequestRuntimePermissions()
            }
        }
    }
}

private fun storageRootTreeUri(): Uri =
    android.provider.DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:")
