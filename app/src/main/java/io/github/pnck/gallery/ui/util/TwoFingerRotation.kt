package io.github.pnck.gallery.ui.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Two-finger twist detector for the viewer (PRD §9.1). Telephoto owns zoom/pan
 * but exposes no rotation, so we *observe* the gesture in the Initial pass and
 * never consume it — Telephoto still gets the same pointers for pinch-zoom on the
 * Main pass. [onRotate] receives incremental degrees during the twist; [onSettle]
 * fires when the fingers lift so the caller can snap to the nearest 90°.
 */
fun Modifier.twoFingerRotation(
    enabled: Boolean = true,
    onRotate: (degrees: Float) -> Unit,
    onSettle: () -> Unit,
): Modifier = if (!enabled) this else pointerInput(Unit) {
    awaitEachGesture {
        var previousAngle: Float? = null
        var twisted = false
        while (true) {
            // Initial pass + no consume → observe only; Telephoto still zooms/pans.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val a = pressed[0].position
                val b = pressed[1].position
                val angle = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                previousAngle?.let { prev ->
                    var delta = angle - prev
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    if (delta != 0f) {
                        onRotate(delta)
                        twisted = true
                    }
                }
                previousAngle = angle
            } else {
                previousAngle = null
            }
            if (event.changes.none { it.pressed }) break
        }
        if (twisted) onSettle()
    }
}

/** Snap an accumulated angle to the nearest quarter turn. */
fun snapTo90(angle: Float): Float = (angle / 90f).roundToInt() * 90f
