package io.github.pnck.gallery.ui.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Google-Photos-style fast scrollbar: a draggable thumb on the right edge that
 * appears while the grid scrolls or is dragged, and — while dragging — shows a
 * date bubble (e.g. "Jul 2026") for the item it will land on. Auto-hides shortly
 * after activity stops.
 *
 * Purely a navigation aid over the existing [LazyGridState]; it never mutates data.
 *
 * @param labelForIndex maps a grid item index to its bubble label (year-month),
 *   or null when the item isn't loaded yet.
 */
@Composable
fun FastScroller(
    state: LazyGridState,
    itemCount: Int,
    labelForIndex: (Int) -> String?,
    modifier: Modifier = Modifier,
) {
    if (itemCount <= 1) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var dragging by remember { mutableStateOf(false) }
    val active = dragging || state.isScrollInProgress

    // Keep the thumb up briefly after scrolling stops, then fade out.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            visible = true
        } else {
            delay(1200)
            visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "fastScrollerAlpha")
    if (alpha <= 0.01f) return

    val fraction by remember {
        derivedStateOf {
            (state.firstVisibleItemIndex.toFloat() / (itemCount - 1)).coerceIn(0f, 1f)
        }
    }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier.fillMaxHeight().width(40.dp)) {
        val thumbHeight = 52.dp
        val maxOffsetPx = with(density) { (maxHeight - thumbHeight).toPx() }.coerceAtLeast(1f)
        val shownFraction = if (dragging) dragFraction else fraction
        val offsetY = with(density) { (shownFraction * maxOffsetPx).toDp() }
        val topIndex = (shownFraction * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
        val label = labelForIndex(topIndex)

        if (dragging && label != null) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(50),
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = offsetY)
                    .padding(end = 44.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = offsetY)
                .padding(end = 4.dp)
                .width(8.dp)
                .height(thumbHeight)
                .graphicsLayer { this.alpha = alpha }
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary)
                .pointerInput(itemCount, maxOffsetPx) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragFraction = fraction
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, delta ->
                        change.consume()
                        dragFraction = ((dragFraction * maxOffsetPx) + delta)
                            .coerceIn(0f, maxOffsetPx) / maxOffsetPx
                        val target = (dragFraction * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
                        scope.launch { state.scrollToItem(target) }
                    }
                },
        )
    }
}
