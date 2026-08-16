package io.github.pnck.gallery.ui.util

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat

/** The media-read permission for this SDK level (PRD §6.3 matrix). */
fun mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/** All media-read permissions to request at runtime (images + videos on 33+;
 *  single legacy permission below). */
fun mediaPermissionsToRequest(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
        // Legacy model (requestLegacyExternalStorage): WRITE covers silent
        // batch delete — the Android-10 counterpart of MANAGE_MEDIA.
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

/** Legacy-model write grant (API ≤29): with it, deletes are silent — no system dialog. */
fun hasLegacyWritePermission(context: Context): Boolean =
    Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED

/** Runtime-grant check over BOTH media permissions (33+; legacy below). */
fun hasMediaRuntimePermission(context: Context): Boolean =
    mediaPermissionsToRequest().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * FULL (not foreground-restricted) media read access.
 *
 * checkSelfPermission alone is not enough: MIUI's "仅前台允许" keeps the runtime
 * permission GRANTED but flips the app-op to MODE_FOREGROUND — every BACKGROUND
 * MediaStore query then returns zero rows (no exception), which is what made
 * background reconcile see an empty library and abort. The app-op is the only
 * place the restriction is visible.
 */
fun hasFullMediaAccess(context: Context): Boolean {
    if (!hasMediaRuntimePermission(context)) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true // no app-op modes below Q
    val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
    val targets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(OPSTR_READ_MEDIA_IMAGES, OPSTR_READ_MEDIA_VIDEO)
    } else {
        listOf(AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE)
    }
    // checkOpNoThrow (API 29+) — the modern, non-deprecated form; this path
    // only runs on Q+, and unsafeCheckOpNoThrow is deprecated since API 35.
    return targets.all { op ->
        when (ops.checkOpNoThrow(op, Process.myUid(), context.packageName)) {
            AppOpsManager.MODE_ALLOWED -> true
            else ->
                // API 34+ partial access ("selected photos") is a complete, supported
                // state (PRD §6.3), not a degradation.
                Build.VERSION.SDK_INT >= 34 && ops.checkOpNoThrow(
                    OPSTR_READ_MEDIA_VISUAL_USER_SELECTED,
                    Process.myUid(),
                    context.packageName,
                ) == AppOpsManager.MODE_ALLOWED
        }
    }
}

// The media app-op strings are hidden API (no public constants) — the values
// are stable, set by the platform.
private const val OPSTR_READ_MEDIA_IMAGES = "android:read_media_images"
private const val OPSTR_READ_MEDIA_VIDEO = "android:read_media_video"
private const val OPSTR_READ_MEDIA_VISUAL_USER_SELECTED = "android:read_media_visual_user_selected"

/**
 * Fix degraded media access. When the runtime permission itself is revoked the
 * standard dialog applies (handled by the caller's permission launcher); when it
 * is GRANTED but app-op restricted (MIUI foreground-only), NO system dialog
 * exists — the only remedy is the permission editor page.
 */
fun openPermissionEditor(context: Context) {
    val intents = listOfNotNull(
        // MIUI per-app STORAGE permission page (始终允许/仅前台允许 lives here).
        // Verified on MIUI 12 (cepheus) — opens directly with the pkg extra.
        Intent()
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppStoragePermissionsActivity")
            .putExtra("extra_pkgname", context.packageName),
        // Generic MIUI permission editor (note: PermissionsEditorActivity, plural —
        // the singular class name circulated in blog posts does not exist on MIUI 12).
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )
    for (intent in intents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // try the next fallback
        } catch (e: SecurityException) {
            // try the next fallback
        }
    }
}

/** Xiaomi/MIUI build — the only family with the AutoStartManager job killer. */
fun isMiui(): Boolean =
    Build.MANUFACTURER.equals("xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("redmi", ignoreCase = true)

/**
 * MIUI AutoStart management page. There is NO API to query or request the
 * autostart grant — deep-linking to the page is the only remedy, so callers
 * should only offer it when background sync is observably stale.
 */
fun openAutostartSettings(activity: Activity) {
    val intents = listOfNotNull(
        Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")),
    )
    for (intent in intents) {
        try {
            activity.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // try the next fallback
        } catch (e: SecurityException) {
            // try the next fallback
        }
    }
}

/**
 * MANAGE_MEDIA ("媒体管理" special access, API 31+): while held, the app may
 * delete media WITHOUT the system delete dialog — the batch-cleanup path.
 * Always false below S (the grant doesn't exist there).
 */
fun canManageMedia(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

/** Deep-link to the system's media-management grant page (API 31+). */
fun openManageMediaSettings(activity: Activity) {
    val intents = listOfNotNull(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${activity.packageName}"))
        } else {
            null
        },
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")),
    )
    for (intent in intents) {
        try {
            activity.startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // try the next fallback
        } catch (e: SecurityException) {
            // try the next fallback
        }
    }
}

// ── SAF tree access (the API-30 silent-delete path) ─────────────────────────
// Android 11 hard-blocks the legacy storage model for targetSdk 30+, and
// MANAGE_MEDIA doesn't exist until 31 — so on API 30 the gallery-app standard
// (Simple Gallery & co.) is a ONE-TIME document-tree grant over shared storage;
// afterwards DocumentsContract deletes run silently, with zero system dialogs.

/** Intent for the one-time tree picker, opened at the primary storage root. */
fun buildTreeAccessIntent(): Intent =
    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildTreeDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:",
                ),
            )
        }
    }

/** Persist a granted tree uri so [hasStorageTreeAccess] survives reboots. */
fun persistStorageTree(context: Context, treeUri: Uri) {
    context.contentResolver.takePersistableUriPermission(
        treeUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    )
}

/** True while a persisted storage-tree grant is held (the API-30 silent path). */
fun hasStorageTreeAccess(context: Context, treeUri: String?): Boolean {
    if (treeUri == null) return false
    val held = context.contentResolver.persistedUriPermissions
    return held.any { it.uri.toString() == treeUri && it.isWritePermission }
}

/**
 * Delete ONE media item through the SAF tree (API-30 silent path). Resolves the
 * MediaStore row's volume + relative path + name into the tree's document id.
 * Returns false when the row can't be resolved (caller falls back).
 */
fun deleteViaStorageTree(context: Context, mediaUri: Uri, treeUri: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false // RELATIVE_PATH
    return runCatching {
    val projection = arrayOf(
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DISPLAY_NAME,
    )
    val docId = context.contentResolver.query(mediaUri, projection, null, null, null)?.use { c ->
        if (!c.moveToFirst()) return false
        val rel = c.getString(0) ?: return false
        val name = c.getString(1) ?: return false
        // "external_primary" → SAF's "primary"; sd cards carry their uuid.
        val volume = when (val v = MediaStore.getVolumeName(mediaUri)) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY -> "primary"
            else -> v
        }
        "$volume:$rel$name"
    } ?: return false
    val docUri = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), docId)
    DocumentsContract.deleteDocument(context.contentResolver, docUri)
    }.getOrDefault(false)
}
