package io.github.pnck.gallery.ui.util

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The app-level toast: a THIRD window layer (its own non-focusable, touch-
 * transparent dialog), created on demand — so it floats above modal sheets
 * (themselves dialog windows) and its lifetime is bound to NOTHING: sheets
 * open/close beneath it without any routing or handoff logic.
 *
 * Why not a view-hierarchy overlay: M3's ModalBottomSheet is a separate
 * window, and any composable in the activity tree renders strictly below it.
 * Why not android.widget.Toast: an unstyleable system bubble, off-design.
 */
private const val TOAST_DURATION_MS = 4_000L

private var currentToast: ComponentDialog? = null

fun showAppToast(context: Context, message: String) {
    currentToast?.dismiss()
    val dialog = ComponentDialog(context)
    val view = ComposeView(context)
    dialog.setContentView(view)
    view.setContent {
        // M3 snackbar look: inverse surface, 8dp corners, bottom-centered.
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
    dialog.setCancelable(false)
    dialog.window?.let { window ->
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        // The stock dialog theme dims whatever is behind it — that's what made
        // the toast look like a modal blackout. This layer must be invisible
        // except for the pill itself.
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        )
        window.attributes = window.attributes.apply {
            dimAmount = 0f
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (96 * context.resources.displayMetrics.density).toInt()
        }
    }
    currentToast = dialog
    dialog.show()
    Handler(Looper.getMainLooper()).postDelayed({
        if (currentToast === dialog) currentToast = null
        dialog.dismiss()
    }, TOAST_DURATION_MS)
}
