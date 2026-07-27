package io.github.pnck.gallery.ui.util

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The scrub model behind the fast scroller: the rail is divided into EQUAL
 * SLOTS, one per period (year-month, or a dynamic value bucket for size sorts)
 * — never weighted by item count and never collapsed (a month with 1,000
 * photos owns exactly one slot, same as a month with one). The focus SNAPS to
 * slots: crossing a slot boundary jumps the wall and ticks the haptics
 * (Google-Photos behavior).
 *
 * @param cellIndices landing cell of each slot, in display order
 * @param labels bubble text of each slot ("Mar 2020" / "3.2 MB")
 */
class ScrubModel(
    val cellIndices: IntArray,
    val labels: List<String>,
) {
    init {
        require(cellIndices.size == labels.size && labels.isNotEmpty())
    }

    private val slots get() = labels.size

    /** Fraction → the SNAPPED slot (round, not truncate — slots are the unit). */
    private fun slotAt(fraction: Float): Int =
        ((fraction.coerceIn(0f, 1f)) * (slots - 1)).toInt().coerceIn(0, slots - 1)

    fun cellIndexAt(fraction: Float): Int = cellIndices[slotAt(fraction)]

    fun labelAt(fraction: Float): String = labels[slotAt(fraction)]

    fun slotIndexAt(fraction: Float): Int = slotAt(fraction)

    fun fractionForSlot(slot: Int): Float = slot.toFloat() / (slots - 1).coerceAtLeast(1)

    /** The slot the grid is currently inside (last slot at or before the cell). */
    fun slotForCell(firstVisibleCell: Int): Int {
        var lo = 0
        var hi = cellIndices.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cellIndices[mid] <= firstVisibleCell) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}

/**
 * Google-Photos-style fast scroller, minimal two-piece look (GP/Apple/Immich):
 *  - a translucent white half-pill handle with shadow + chevrons, peeking from
 *    the right edge while the grid flings;
 *  - while dragging, a matching white bubble floats left of the handle with the
 *    focused period ("Mar 2020" / "3.2 MB"), following the finger;
 *  - the rail is divided into EQUAL SLOTS (one per year-month, or dynamic size
 *    buckets); the focus SNAPS to slots — crossing one jumps the wall and ticks
 *    the haptics; release returns to normal scrolling and everything fades out.
 */
@Composable
fun FastScroller(
    state: LazyGridState,
    itemCount: Int,
    model: ScrubModel?,
    modifier: Modifier = Modifier,
) {
    if (itemCount <= 1 || model == null) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

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

    var fingerFraction by remember { mutableFloatStateOf(0f) }
    val restingFraction by remember(model) {
        androidx.compose.runtime.derivedStateOf {
            model.fractionForSlot(model.slotForCell(state.firstVisibleItemIndex))
        }
    }
    val fraction = if (dragging) fingerFraction else restingFraction
    val haptics = LocalHapticFeedback.current

    // Full-size overlay: the bubble needs real width (it was squeezed into the
    // 56dp rail and collapsed to a blank strip). Only the 56dp rail consumes
    // touches — everything else stays scroll-through.
    BoxWithConstraints(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        val handleH = 48.dp
        val handleHPx = with(density) { handleH.toPx() }
        val maxY = constraints.maxHeight.toFloat()
        val padVpx = with(density) { 24.dp.toPx() }
        val usableH = (maxY - 2 * padVpx - handleHPx).coerceAtLeast(1f)
        val handleY = padVpx + fraction * usableH

        fun yToFraction(y: Float): Float = ((y - padVpx - handleHPx / 2) / usableH).coerceIn(0f, 1f)

        // Floating bubble: the focused period, following the finger.
        if (dragging) {
            Surface(
                color = Color.White.copy(alpha = 0.92f),
                contentColor = Color(0xFF202124),
                shape = RoundedCornerShape(50),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = with(density) { (handleY + handleHPx / 2 - 24.dp.toPx()).coerceAtLeast(0f).toDp() })
                    .padding(end = 40.dp),
            ) {
                Text(
                    model.labelAt(fraction),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }

        // The white translucent half-pill handle with shadow + chevrons.
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
                    modifier = Modifier.padding(top = 3.dp),
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color(0xFF5F6368),
                )
            }
        }

        // Gesture layer: the 56dp rail is the grab zone while visible. The
        // focus SNAPS to slots — crossing one jumps the wall and ticks haptics.
        Box(
            Modifier
                .fillMaxHeight()
                .width(56.dp)
                .align(Alignment.TopEnd)
                .pointerInput(model, constraints.maxHeight) {
                    detectVerticalDragGestures(
                        onDragStart = { start ->
                            dragging = true
                            fingerFraction = yToFraction(start.y)
                            scope.launch { state.scrollToItem(model.cellIndexAt(fingerFraction)) }
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        val f = yToFraction(change.position.y)
                        val slotChanged = model.slotIndexAt(f) != model.slotIndexAt(fingerFraction)
                        fingerFraction = f
                        if (slotChanged) {
                            // SegmentTick (API 34+) is purpose-built for scrub ticks;
                            // TextHandleMove is a no-op on many OEM ROMs, LongPress
                            // would be far too heavy per slot.
                            haptics.performHapticFeedback(
                                if (android.os.Build.VERSION.SDK_INT >= 34) {
                                    HapticFeedbackType.SegmentTick
                                } else {
                                    HapticFeedbackType.TextHandleMove
                                },
                            )
                            scope.launch { state.scrollToItem(model.cellIndexAt(f)) }
                        }
                    }
                },
        )
    }
}
