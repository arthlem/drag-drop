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
 * Per-cell drag-source modifier. Detects a 200 ms long-press, then drives the drag through
 * release: writes [DragController.pressOffsetWithinCell] / [DragController.fingerInWindow] /
 * [DragController.draggingWidget], hit-tests against [DragController.dragBounds], invokes
 * the callbacks, and runs [DragController.edgeAutoScroll] near grid edges. Cleans up in `finally`.
 */
@Composable
fun Modifier.dragSource(
    widget: GenericWidget,
    controller: DragController,
    onDragStart: (String) -> Unit,
    onDragHover: (String) -> Unit,
    onDragCommit: () -> Unit,
): Modifier {
    val widgetId = widget.id
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragHover by rememberUpdatedState(onDragHover)
    val currentOnDragCommit by rememberUpdatedState(onDragCommit)
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

                controller.pressOffsetWithinCell.value = down.position
                val cellOrigin = cellCoords?.boundsInWindow()?.topLeft ?: Offset.Zero
                controller.fingerInWindow.value = cellOrigin + down.position
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
 * Drives [LazyGridState.scrollBy] while a finger is past the grid's vertical edges.
 * Velocity ramps linearly from 0 at the edge itself to ~MAX_PX_PER_FRAME once the finger
 * is [bandPx] beyond the edge. Triggering only past the edge means accidental scroll
 * during mid-screen drags is impossible — the user has to deliberately pull off the grid.
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
            fingerY < gridTopInWindow ->
                -lerp(0f, MAX_PX_PER_FRAME, ((gridTopInWindow - fingerY) / bandPx).coerceIn(0f, 1f))
            fingerY > gridBottomInWindow ->
                lerp(0f, MAX_PX_PER_FRAME, ((fingerY - gridBottomInWindow) / bandPx).coerceIn(0f, 1f))
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
