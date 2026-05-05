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

private const val LONG_PRESS_TIMEOUT_MS = 200L

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

                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            currentOnDragCommit()
                            break
                        }
                        val origin = cellCoords?.boundsInWindow()?.topLeft ?: cellOrigin
                        val finger = origin + change.position
                        controller.fingerInWindow.value = finger
                        hitTest(finger, controller.dragBounds, draggedKey = widgetId)?.let { key ->
                            currentOnDragHover(key)
                        }
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
 * Drives [LazyGridState.scrollBy] while a finger sits within [bandPx] of the grid's vertical edges.
 * Velocity ramps linearly from 0 at the band's outer edge to ~12 px/frame at the screen edge.
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
