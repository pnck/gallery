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
import android.provider.Settings
import androidx.core.content.ContextCompat

/** The media-read permission for this SDK level (PRD §6.3 matrix). */
fun mediaPermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
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
    if (ContextCompat.checkSelfPermission(context, mediaPermission()) != PackageManager.PERMISSION_GRANTED) {
        return false
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true // no app-op modes below Q
    val ops = context.getSystemService(AppOpsManager::class.java) ?: return true
    val op = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        OPSTR_READ_MEDIA_IMAGES
    } else {
        AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE
    }
    return when (ops.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)) {
        AppOpsManager.MODE_ALLOWED -> true
        else ->
            // API 34+ partial access ("selected photos") is a complete, supported
            // state (PRD §6.3), not a degradation.
            Build.VERSION.SDK_INT >= 34 && ops.unsafeCheckOpNoThrow(
                OPSTR_READ_MEDIA_VISUAL_USER_SELECTED,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
    }
}

// The media app-op strings are hidden API (no public constants) — the values
// are stable, set by the platform.
private const val OPSTR_READ_MEDIA_IMAGES = "android:read_media_images"
private const val OPSTR_READ_MEDIA_VISUAL_USER_SELECTED = "android:read_media_visual_user_selected"

/**
 * Fix degraded media access. When the runtime permission itself is revoked the
 * standard dialog applies (handled by the caller's permission launcher); when it
 * is GRANTED but app-op restricted (MIUI foreground-only), NO system dialog
 * exists — the only remedy is the permission editor page.
 */
fun openPermissionEditor(context: Context) {
    val intents = listOfNotNull(
        // MIUI per-app permission editor (仅前台允许 → 始终允许 lives here).
        Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
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
