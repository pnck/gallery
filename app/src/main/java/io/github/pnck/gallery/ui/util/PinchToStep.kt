package io.github.pnck.gallery.ui.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Two-finger pinch that fires once per gesture when the spread crosses a
 * threshold — used to step the timeline's thumbnail density (Google-Photos grid
 * zoom). Two-finger events are consumed so the grid doesn't scroll mid-pinch;
 * one-finger scrolling is untouched.
 *
 * @param onZoomIn  fingers spread apart → larger thumbnails / fewer columns
 * @param onZoomOut fingers pinch together → smaller thumbnails / more columns
 */
fun Modifier.pinchToStep(
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        var startDistance: Float? = null
        var fired = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                pressed.forEach { it.consume() }
                val distance = (pressed[0].position - pressed[1].position).getDistance()
                val start = startDistance
                if (start == null) {
                    startDistance = distance
                } else if (!fired && start > 0f) {
                    val ratio = distance / start
                    if (ratio > 1.3f) {
                        onZoomIn(); fired = true
                    } else if (ratio < 0.77f) {
                        onZoomOut(); fired = true
                    }
                }
            } else {
                startDistance = null
            }
            if (event.changes.none { it.pressed }) break
        }
    }
}
