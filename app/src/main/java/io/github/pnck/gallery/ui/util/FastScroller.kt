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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One notch on the fast-scroll axis. The axis is UNIFORM PER PERIOD: notches are
 * evenly spaced regardless of how many items each period holds (Google-Photos
 * semantics — a year with 1,000 photos must not eat 99% of the rail).
 */
data class TimeMarker(
    /** Labeled tick text (year / size bucket); null = a plain dot (month). */
    val axisLabel: String?,
    /** Amplified bubble text for the focused period (e.g. "Mar 2024"). */
    val label: String,
    /** Grid cell index of the period's section start (scrollToItem target). */
    val firstCellIndex: Int,
)

/**
 * Google-Photos-style fast scroller:
 *  - a translucent WHITE half-pill handle with shadow + chevrons, peeking from
 *    the right edge while the grid flings;
 *  - dragging it unfolds a masked axis CARD (scrim-isolated from the photo
 *    wall) carrying labeled year ticks + month dots, evenly per period;
 *  - the handle FOLLOWS THE FINGER smoothly while the focus snaps to the
 *    nearest period — amplified in a large floating bubble and a highlight
 *    band on the card;
 *  - release returns to normal scrolling; everything auto-hides shortly after.
 *
 * [markers] is null only when the grid is empty — every sort provides an axis
 * (year-month for date sorts, size buckets for size sorts: same mechanism).
 */
@Composable
fun FastScroller(
    state: LazyGridState,
    itemCount: Int,
    markers: List<TimeMarker>?,
    modifier: Modifier = Modifier,
) {
    if (itemCount <= 1 || markers.isNullOrEmpty()) return
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

    AxisScroller(state, markers, dragging, { dragging = it }, scope, modifier.graphicsLayer { this.alpha = alpha })
}

@Composable
private fun AxisScroller(
    state: LazyGridState,
    markers: List<TimeMarker>,
    dragging: Boolean,
    setDragging: (Boolean) -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableIntStateOf(0) }
    var fingerY by remember { mutableFloatStateOf(0f) }

    val currentIdx by remember(markers) {
        derivedStateOf { markerIndexFor(markers, state.firstVisibleItemIndex) }
    }

    BoxWithConstraints(modifier.fillMaxHeight().width(64.dp)) {
        // Geometry: card occupies the rail with padding; ticks inside the card.
        val cardPadV = 32.dp
        val cardPadVpx = with(density) { cardPadV.toPx() }
        val tickPadV = 14.dp
        val tickPadVpx = with(density) { tickPadV.toPx() }
        val usableH = (constraints.maxHeight - 2 * (cardPadVpx + tickPadVpx)).coerceAtLeast(1f)
        val handleH = 48.dp
        val handleHPx = with(density) { handleH.toPx() }
        val maxY = constraints.maxHeight.toFloat()

        fun tickY(i: Int): Float =
            cardPadVpx + tickPadVpx + (i.toFloat() / (markers.size - 1).coerceAtLeast(1)) * usableH

        fun yToIdx(y: Float): Int =
            (((y - cardPadVpx - tickPadVpx) / usableH) * (markers.size - 1))
                .toInt()
                .coerceIn(0, markers.size - 1)

        val handleY = if (dragging) {
            (fingerY - handleHPx / 2).coerceIn(0f, maxY - handleHPx)
        } else {
            (tickY(currentIdx) - handleHPx / 2).coerceIn(0f, maxY - handleHPx)
        }

        // ── Axis card (visible while dragging): scrim-isolated from the wall ──
        val axisAlpha by animateFloatAsState(if (dragging) 1f else 0f, label = "axisAlpha")
        if (axisAlpha > 0.01f) {
            val labelPaint = remember {
                android.graphics.Paint().apply {
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.RIGHT
                }
            }
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = cardPadV)
                    .height(with(density) { (maxY - 2 * cardPadVpx).toDp() })
                    .width(60.dp)
                    .graphicsLayer { this.alpha = axisAlpha },
            ) {
                Canvas(Modifier.fillMaxHeight().padding(vertical = tickPadV)) {
                    val textX = size.width - with(density) { 12.dp.toPx() }
                    val dotX = size.width - with(density) { 14.dp.toPx() }
                    val bandPad = with(density) { 7.dp.toPx() }
                    markers.forEachIndexed { i, m ->
                        // Card-local Y: tickY is box-coords; the card starts at
                        // cardPadV and the canvas is further padded by tickPadV.
                        val y = tickY(i) - cardPadVpx - tickPadVpx
                        val isFocus = i == focused
                        if (isFocus) {
                            // Highlight band isolating the focused notch.
                            drawRoundRect(
                                color = colors.primary.copy(alpha = 0.45f),
                                topLeft = Offset(with(density) { 4.dp.toPx() }, y - bandPad),
                                size = Size(size.width - with(density) { 8.dp.toPx() }, bandPad * 2),
                                cornerRadius = CornerRadius(bandPad, bandPad),
                            )
                        }
                        if (m.axisLabel != null) {
                            labelPaint.textSize = with(density) { (if (isFocus) 13.sp else 11.sp).toPx() }
                            labelPaint.color = (if (isFocus) Color.White else Color.White.copy(alpha = 0.75f)).toArgb()
                            labelPaint.typeface =
                                if (isFocus) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                            drawContext.canvas.nativeCanvas.drawText(
                                m.axisLabel,
                                textX,
                                y + labelPaint.textSize / 3,
                                labelPaint,
                            )
                        } else {
                            drawCircle(
                                color = if (isFocus) Color.White else Color.White.copy(alpha = 0.6f),
                                radius = with(density) { (if (isFocus) 5.dp else 2.5.dp).toPx() },
                                center = Offset(dotX, y),
                            )
                        }
                    }
                }
            }

            // Amplified focused period, floating left of the rail.
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(50),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = with(density) { (fingerY - 26.dp.toPx()).coerceIn(0f, maxY - 52.dp.toPx()).toDp() })
                    .padding(end = 68.dp),
            ) {
                Text(
                    markers[focused].label,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }

        // ── The white translucent half-pill handle with shadow + chevrons ──
        Surface(
            color = Color.White.copy(alpha = 0.92f),
            shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
            shadowElevation = 6.dp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = with(density) { handleY.toDp() })
                .width(26.dp)
                .height(handleH),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color(0xFF5F6368),
                    modifier = Modifier.size(22.dp),
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color(0xFF5F6368),
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Gesture layer: the whole rail is the grab zone while visible. The
        // handle tracks the FINGER; the focus snaps to the nearest period.
        Box(
            Modifier
                .fillMaxHeight()
                .width(64.dp)
                .align(Alignment.TopEnd)
                .pointerInput(markers, constraints.maxHeight) {
                    detectVerticalDragGestures(
                        onDragStart = { start ->
                            setDragging(true)
                            fingerY = start.y
                            focused = yToIdx(start.y)
                            scope.launch { state.scrollToItem(markers[focused].firstCellIndex) }
                        },
                        onDragEnd = { setDragging(false) },
                        onDragCancel = { setDragging(false) },
                    ) { change, _ ->
                        change.consume()
                        fingerY = change.position.y
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
