package io.github.pnck.gallery.ui.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One year-month notch on the fast-scroll axis (Google-Photos style). The axis
 * maps RAIL POSITION → TIME PERIOD (not → item count): the whole point of the
 * interaction is coarse time navigation — "grab, slide to 2024-03, land there".
 */
data class TimeMarker(
    val year: Int,
    /** 1-12; 0 for year-only markers (collapsed axis). */
    val month: Int,
    /** Display label, e.g. "Mar 2024" (or "2024" when collapsed). */
    val label: String,
    /** Grid cell index of the first photo in this period (scrollToItem target). */
    val firstCellIndex: Int,
)

/**
 * Google-Photos-style fast scroller with a year-month axis.
 *
 * - A fat chevron handle (bigger than a native scrollbar thumb, easy to grab)
 *   appears while the grid flings or the rail is touched.
 * - Dragging it unfolds the axis: year ticks (labeled) + month dots along the
 *   right edge; the focused period is amplified in a floating label.
 * - Dragging scrubs the grid live to that period; release returns to normal
 *   scrolling. Axis and handle auto-hide shortly after.
 *
 * With [markers] = null (non-chronological sorts, where a time axis is
 * meaningless) it degrades to a plain fraction thumb with a drag bubble.
 */
@Composable
fun FastScroller(
    state: LazyGridState,
    itemCount: Int,
    labelForIndex: (Int) -> String?,
    markers: List<TimeMarker>?,
    modifier: Modifier = Modifier,
) {
    if (itemCount <= 1) return
    val scope = rememberCoroutineScope()

    var dragging by remember { mutableStateOf(false) }
    val active = dragging || state.isScrollInProgress
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (active) {
            visible = true
        } else {
            delay(1200)
            visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "scrollerAlpha")
    if (alpha <= 0.01f) return

    if (markers.isNullOrEmpty()) {
        FractionThumb(state, itemCount, labelForIndex, dragging, { dragging = it }, alpha, modifier.graphicsLayer { this.alpha = alpha })
    } else {
        AxisScroller(state, markers, dragging, { dragging = it }, alpha, modifier.graphicsLayer { this.alpha = alpha }, scope)
    }
}

// ── Axis mode (year-month rail) ──────────────────────────────────────────────

@Composable
private fun AxisScroller(
    state: LazyGridState,
    markers: List<TimeMarker>,
    dragging: Boolean,
    setDragging: (Boolean) -> Unit,
    alpha: Float,
    modifier: Modifier,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableIntStateOf(0) }

    // Handle position when not dragging: the marker the grid is currently inside.
    val currentIdx by remember(markers) {
        derivedStateOf { markerIndexFor(markers, state.firstVisibleItemIndex) }
    }
    val shownIdx = if (dragging) focused else currentIdx

    BoxWithConstraints(modifier.fillMaxHeight().width(56.dp)) {
        val railTop = 16.dp
        val railBottom = 16.dp
        val railHeightPx = with(density) { (maxHeight - railTop - railBottom).toPx() }.coerceAtLeast(1f)
        val handleH = 44.dp
        val handleHPx = with(density) { handleH.toPx() }

        fun idxToY(idx: Int): Float =
            with(density) { railTop.toPx() } +
                (idx.toFloat() / (markers.size - 1).coerceAtLeast(1)) * railHeightPx

        fun yToIdx(y: Float): Int =
            (((y - with(density) { railTop.toPx() }) / railHeightPx) * (markers.size - 1))
                .toInt()
                .coerceIn(0, markers.size - 1)

        // Axis ticks (year labels + month dots), visible while dragging.
        val axisAlpha by animateFloatAsState(if (dragging) 1f else 0f, label = "axisAlpha")
        if (axisAlpha > 0.01f) {
            val yearPaint = remember(colors) {
                android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = colors.onSurfaceVariant.toArgb()
                    textAlign = android.graphics.Paint.Align.RIGHT
                }
            }
            Canvas(Modifier.fillMaxHeight().width(56.dp)) {
                val textSizePx = 10.sp.toPx()
                yearPaint.textSize = textSizePx
                val dotX = size.width - with(density) { 14.dp.toPx() }
                val labelX = dotX - with(density) { 10.dp.toPx() }
                val minYearGapPx = with(density) { 26.dp.toPx() }
                var lastYearLabelY = Float.NEGATIVE_INFINITY
                markers.forEachIndexed { i, m ->
                    val y = idxToY(i)
                    val isFocus = i == focused
                    val isYearMark = m.month <= 1
                    val dotR = with(density) { (if (isFocus) 5.dp else if (isYearMark) 3.5.dp else 2.dp).toPx() }
                    drawCircle(
                        color = if (isFocus) colors.primary else colors.onSurfaceVariant,
                        radius = dotR,
                        center = Offset(dotX, y),
                        alpha = axisAlpha,
                    )
                    if (isYearMark && y - lastYearLabelY > minYearGapPx) {
                        lastYearLabelY = y
                        drawContext.canvas.nativeCanvas.drawText(
                            m.year.toString(),
                            labelX,
                            y + textSizePx / 3,
                            yearPaint,
                        )
                    }
                }
            }

            // Amplified focused period label, floating left of the rail.
            Surface(
                color = colors.inverseSurface,
                contentColor = colors.inverseOnSurface,
                shape = RoundedCornerShape(50),
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = with(density) { (idxToY(focused) - handleHPx / 2).toDp() })
                    .padding(end = 60.dp),
            ) {
                Text(
                    markers[focused].label,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // The fat chevron handle.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = with(density) { (idxToY(shownIdx) - handleHPx / 2).coerceAtLeast(0f).toDp() })
                .padding(end = 2.dp)
                .width(28.dp)
                .height(handleH)
                .background(colors.primary, RoundedCornerShape(14.dp)),
        ) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(20.dp),
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }

        // Gesture layer: the whole rail is the grab zone while visible. A drag
        // anywhere snaps the focus to the finger's time period and scrubs live.
        Box(
            Modifier
                .fillMaxHeight()
                .width(56.dp)
                .align(Alignment.TopEnd)
                .pointerInput(markers, railHeightPx) {
                    detectVerticalDragGestures(
                        onDragStart = { startY ->
                            setDragging(true)
                            focused = yToIdx(startY.y)
                            scope.launch { state.scrollToItem(markers[focused].firstCellIndex) }
                        },
                        onDragEnd = { setDragging(false) },
                        onDragCancel = { setDragging(false) },
                    ) { change, _ ->
                        change.consume()
                        val idx = yToIdx(change.position.y)
                        if (idx != focused) {
                            focused = idx
                            scope.launch { state.scrollToItem(markers[idx].firstCellIndex) }
                        }
                    }
                },
        )
    }
}

/** The marker the grid is currently inside (last marker at or before the item). */
private fun markerIndexFor(markers: List<TimeMarker>, firstVisibleCell: Int): Int {
    var lo = 0
    var hi = markers.size - 1
    var ans = 0
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (markers[mid].firstCellIndex <= firstVisibleCell) {
            ans = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return ans
}

// ── Fallback: plain fraction thumb (non-chronological sorts) ─────────────────

@Composable
private fun FractionThumb(
    state: LazyGridState,
    itemCount: Int,
    labelForIndex: (Int) -> String?,
    dragging: Boolean,
    setDragging: (Boolean) -> Unit,
    alpha: Float,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val fraction by remember {
        derivedStateOf {
            (state.firstVisibleItemIndex.toFloat() / (itemCount - 1)).coerceIn(0f, 1f)
        }
    }
    var dragFraction by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

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
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                .pointerInput(itemCount, maxOffsetPx) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            setDragging(true)
                            dragFraction = fraction
                        },
                        onDragEnd = { setDragging(false) },
                        onDragCancel = { setDragging(false) },
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
