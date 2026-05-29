@file:OptIn(ExperimentalFoundationApi::class)

package com.arthlem.dragdrop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val LONG_PRESS_TIMEOUT_MS = 400L

/**
 * Shared drag state holders — created once per `WidgetsContent` via [rememberDragController]
 * and passed to drop targets ([Modifier.bindBounds]) and the source ([Modifier.dragSource]).
 */
@Stable
class DragController(
    val dragBounds: SnapshotStateMap<String, Rect>,
    val pressOffsetWithinCell: MutableState<Offset>,
    val fingerInWindow: MutableState<Offset?>,
    val draggingWidget: MutableState<GenericWidget?>,
    val edgeAutoScroll: EdgeAutoScroll,
)

@Composable
fun rememberDragController(lazyGridState: LazyGridState): DragController {
    val edgeAutoScroll = rememberEdgeAutoScroll(lazyGridState)
    return remember(edgeAutoScroll) {
        DragController(
            dragBounds = mutableStateMapOf(),
            pressOffsetWithinCell = mutableStateOf(Offset.Zero),
            fingerInWindow = mutableStateOf(null),
            draggingWidget = mutableStateOf(null),
            edgeAutoScroll = edgeAutoScroll,
        )
    }
}

/**
 * Registers `coords.boundsInWindow()` into [bounds] under [key], and removes the entry on dispose.
 * Used by drop-target composables (HeaderCell, EmptyDropZone, WidgetCard's source slot).
 */
@Composable
fun Modifier.bindBounds(
    key: String,
    bounds: SnapshotStateMap<String, Rect>,
): Modifier {
    DisposableEffect(key) {
        onDispose { bounds.remove(key) }
    }
    return this.onGloballyPositioned { coords ->
        bounds[key] = coords.boundsInWindow()
    }
}

/**
 * Per-cell drag-source modifier with a unified long-press gesture:
 *
 * 1. A successful long-press ([LONG_PRESS_TIMEOUT_MS]) opens the contextual menu via
 *    [setMenuExpanded]`(true)` — the finger stays down and armed.
 * 2. If the still-pressed finger then moves past `viewConfiguration.touchSlop`, the menu is
 *    dismissed ([setMenuExpanded]`(false)`) and the drag begins — no separate "reorder mode".
 * 3. If the finger lifts before crossing the slop, the menu simply stays open (plain long-press).
 *
 * Once dragging, it writes [DragController.pressOffsetWithinCell] / [DragController.fingerInWindow] /
 * [DragController.draggingWidget], hit-tests against [DragController.dragBounds], invokes the
 * callbacks, and runs [DragController.edgeAutoScroll] near grid edges. Cleans up in `finally`.
 */
@Composable
fun Modifier.dragSource(
    widget: GenericWidget,
    controller: DragController,
    onDragStart: (String) -> Unit,
    onDragHover: (String) -> Unit,
    onDragCommit: () -> Unit,
    setMenuExpanded: (Boolean) -> Unit,
): Modifier {
    val widgetId = widget.id
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragHover by rememberUpdatedState(onDragHover)
    val currentOnDragCommit by rememberUpdatedState(onDragCommit)
    val currentSetMenuExpanded by rememberUpdatedState(setMenuExpanded)
    var cellCoords: LayoutCoordinates? by remember { mutableStateOf<LayoutCoordinates?>(null) }

    return this
        .onGloballyPositioned { coords -> cellCoords = coords }
        .pointerInput(widgetId) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)

                val longPressed = withTimeoutOrNull(LONG_PRESS_TIMEOUT_MS) {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val change = ev.changes.firstOrNull { it.id == down.id }
                        if (change != null && !change.pressed) return@withTimeoutOrNull false
                    }
                    @Suppress("UNREACHABLE_CODE") true
                } ?: true

                if (longPressed != true) return@awaitEachGesture

                // Long-press fired: open the menu and arm for a possible drag. Don't start the
                // drag yet — wait to see if the finger lifts (menu stays) or drags (menu goes).
                currentSetMenuExpanded(true)

                // Phase: watch the still-down finger. Consume here too — past the long-press we're
                // committed to either the menu or a drag, never a grid scroll, so claiming these
                // events keeps the grid from sliding under the open menu.
                var dragStartPosition: Offset? = null
                while (true) {
                    val ev = awaitPointerEvent()
                    val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        // Lifted without dragging — leave the menu open, end the gesture.
                        return@awaitEachGesture
                    }
                    change.consume()
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        dragStartPosition = change.position
                        break
                    }
                }
                val startPosition = dragStartPosition ?: return@awaitEachGesture

                // Crossed the slop: hand off from menu to drag.
                currentSetMenuExpanded(false)
                controller.pressOffsetWithinCell.value = down.position
                val cellOrigin = cellCoords?.boundsInWindow()?.topLeft ?: Offset.Zero
                controller.fingerInWindow.value = cellOrigin + startPosition
                controller.draggingWidget.value = widget
                currentOnDragStart(widgetId)

                // Track the last reported hover target so we don't spam onDragHover for the same
                // cell on every pointer event. Combined with floating-center hit-testing below,
                // this gives the natural hysteresis you'd expect from a reorder list: one swap per
                // boundary crossing, not one swap per pointer event while the finger sits on a tile.
                var lastHoverKey: String? = null

                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            currentOnDragCommit()
                            break
                        }
                        // Claim the gesture so LazyVerticalGrid's built-in scroll detector doesn't
                        // also process these drag motions and scroll the grid under our finger.
                        change.consume()
                        val cellRect = cellCoords?.boundsInWindow()
                        val origin = cellRect?.topLeft ?: cellOrigin
                        val finger = origin + change.position
                        controller.fingerInWindow.value = finger

                        // Hit-test against the floating widget's center, not the raw finger.
                        // The floating widget is offset from the finger by pressOffsetWithinCell,
                        // so its visual center is `finger - pressOffset + cellSize/2`. Using this
                        // means a swap only fires when the *visible* dragged tile crosses into a
                        // new slot. After the swap, the floating widget is over the new slot, so
                        // it takes another deliberate move to trigger another swap — no flip-flop.
                        val cellWidth = cellRect?.width ?: 0f
                        val cellHeight = cellRect?.height ?: 0f
                        val floatingCenter = finger - controller.pressOffsetWithinCell.value +
                            Offset(cellWidth / 2f, cellHeight / 2f)
                        val hoverKey = hitTest(floatingCenter, controller.dragBounds, draggedKey = widgetId)
                        if (hoverKey != null && hoverKey != lastHoverKey) {
                            currentOnDragHover(hoverKey)
                        }
                        lastHoverKey = hoverKey

                        controller.edgeAutoScroll.update(finger.y)
                    }
                } finally {
                    controller.fingerInWindow.value = null
                    controller.draggingWidget.value = null
                    controller.edgeAutoScroll.stop()
                }
            }
        }
}

/**
 * Returns the first key in [bounds] (excluding [draggedKey]) whose rect contains [finger],
 * or null if no target is hit.
 */
internal fun hitTest(
    finger: Offset,
    bounds: Map<String, Rect>,
    draggedKey: String,
): String? = bounds.entries.firstOrNull { (key, rect) ->
    key != draggedKey && rect.contains(finger)
}?.key

@Composable
fun rememberEdgeAutoScroll(
    lazyGridState: LazyGridState,
    bandHeightDp: Dp = 80.dp,
): EdgeAutoScroll {
    val scope = rememberCoroutineScope()
    val band = with(LocalDensity.current) { bandHeightDp.toPx() }
    return remember(lazyGridState, band, scope) {
        EdgeAutoScroll(lazyGridState, scope, band)
    }
}

/**
 * Drives [LazyGridState.scrollBy] while a finger sits within [bandPx] of the grid's vertical
 * edges (inside the grid). Velocity ramps linearly from MAX_PX_PER_FRAME at the edge itself to
 * 0 at [bandPx] inside the grid. Mid-screen drags don't trigger scroll because the drag loop
 * `change.consume()`s pointer events — `LazyVerticalGrid`'s built-in scroll detector never sees
 * them. This is the only path that can scroll the grid during drag.
 */
class EdgeAutoScroll(
    private val state: LazyGridState,
    private val scope: CoroutineScope,
    private val bandPx: Float,
) {
    private var gridTopInWindow: Float = 0f
    private var gridBottomInWindow: Float = 0f
    private var job: Job? = null
    private var currentVelocity: Float = 0f

    fun bindGridBounds(coords: LayoutCoordinates) {
        val rect = coords.boundsInWindow()
        gridTopInWindow = rect.top
        gridBottomInWindow = rect.bottom
    }

    fun update(fingerY: Float) {
        val velocity = when {
            fingerY < gridTopInWindow + bandPx ->
                -lerp(MAX_PX_PER_FRAME, 0f, ((fingerY - gridTopInWindow) / bandPx).coerceIn(0f, 1f))
            fingerY > gridBottomInWindow - bandPx ->
                lerp(0f, MAX_PX_PER_FRAME, ((fingerY - (gridBottomInWindow - bandPx)) / bandPx).coerceIn(0f, 1f))
            else -> 0f
        }
        if (velocity == 0f) {
            stop()
            return
        }
        currentVelocity = velocity
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                state.scrollBy(currentVelocity)
                withFrameNanos { /* tick */ }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val MAX_PX_PER_FRAME = 12f
    }
}
