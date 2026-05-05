@file:OptIn(ExperimentalFoundationApi::class)

package com.arthlem.dragdrop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val LONG_PRESS_TIMEOUT_MS = 200L

@Composable
fun WidgetsScreen(viewModel: WidgetsViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    when (val state = uiState) {
        UiState.Loading, UiState.Loaded -> WidgetsContent(viewModel)
        is UiState.Error -> ErrorScreen(cause = state.cause)
    }
}

@Composable
private fun WidgetsContent(viewModel: WidgetsViewModel) {
    val entries: List<GridEntry> = viewModel.entries
    val dragState = viewModel.dragState

    val dragBounds: SnapshotStateMap<String, Rect> = remember { mutableStateMapOf() }
    val pressOffsetWithinCell: MutableState<Offset> = remember { mutableStateOf(Offset.Zero) }
    val fingerInWindow: MutableState<Offset?> = remember { mutableStateOf(null) }
    val draggingWidget: MutableState<GenericWidget?> = remember { mutableStateOf(null) }
    var boxCoords: LayoutCoordinates? by remember { mutableStateOf(null) }
    val lazyGridState = rememberLazyGridState()
    val edgeAutoScroll = rememberEdgeAutoScroll(lazyGridState)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords -> boxCoords = coords },
    ) {
        LazyVerticalGrid(
            state = lazyGridState,
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .systemGestureExclusion()
                .onGloballyPositioned { coords -> edgeAutoScroll.bindGridBounds(coords) },
        ) {
        items(
            items = entries,
            key = { it.key },
            span = { entry ->
                when (entry) {
                    is GridEntry.Header, is GridEntry.Empty -> GridItemSpan(maxLineSpan)
                    is GridEntry.Cell -> if (cellSize(entry.state) == WidgetSize.FULL) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                }
            },
            contentType = { entry ->
                when (entry) {
                    is GridEntry.Cell -> when (entry.state) {
                        is WidgetState.Loaded -> WidgetState.Loaded::class
                        is WidgetState.Skeleton -> WidgetState.Skeleton::class
                        is WidgetState.Failure -> WidgetState.Failure::class
                    }
                    else -> entry::class
                }
            },
        ) { entry ->
            when (entry) {
                is GridEntry.Header -> HeaderCell(
                    title = entry.title,
                    modifier = Modifier
                        .animateItem()
                        .bindBounds(entry.key, dragBounds),
                )
                is GridEntry.Empty -> EmptyDropZone(
                    message = entry.message,
                    isDragActive = dragState != null,
                    modifier = Modifier
                        .animateItem()
                        .bindBounds(entry.key, dragBounds),
                )
                is GridEntry.Cell -> when (val s = entry.state) {
                    is WidgetState.Loaded -> WidgetCard(
                        widget = s.widget,
                        isBeingDragged = dragState?.draggedWidget?.id == s.widget.id,
                        dragBounds = dragBounds,
                        pressOffsetWithinCell = pressOffsetWithinCell,
                        fingerInWindow = fingerInWindow,
                        draggingWidget = draggingWidget,
                        edgeAutoScroll = edgeAutoScroll,
                        onDragStart = { viewModel.onDragStart(it) },
                        onDragHover = { viewModel.onDragHover(it) },
                        onDragCommit = { viewModel.onDragCommit() },
                        onTransfer = { viewModel.onTransfer(s.widget.id) },
                        modifier = Modifier
                            .animateItem()
                            .bindBounds(s.widget.id, dragBounds),
                    )
                    is WidgetState.Skeleton -> SkeletonCell(
	                    size = s.size,
	                    modifier = Modifier.animateItem(),
                    )
                    is WidgetState.Failure -> FailureCell(
	                    size = s.size,
	                    modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        }

        val finger = fingerInWindow.value
        val widget = draggingWidget.value
        if (finger != null && widget != null) {
            val boxOriginInWindow = boxCoords?.positionInWindow() ?: Offset.Zero
            val floatingTopLeft = finger - pressOffsetWithinCell.value - boxOriginInWindow
            Box(
                modifier = Modifier
                    .offset { IntOffset(floatingTopLeft.x.roundToInt(), floatingTopLeft.y.roundToInt()) }
                    .zIndex(1f),
            ) {
                FloatingWidgetCard(widget = widget)
            }
        }
    }
}

@Composable
private fun HeaderCell(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun WidgetCard(
    widget: GenericWidget,
    isBeingDragged: Boolean,
    dragBounds: SnapshotStateMap<String, Rect>,
    pressOffsetWithinCell: MutableState<Offset>,
    fingerInWindow: MutableState<Offset?>,
    draggingWidget: MutableState<GenericWidget?>,
    edgeAutoScroll: EdgeAutoScroll,
    onDragStart: (String) -> Unit,
    onDragHover: (String) -> Unit,
    onDragCommit: () -> Unit,
    onTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val widgetId = widget.id
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragHover by rememberUpdatedState(onDragHover)
    val currentOnDragCommit by rememberUpdatedState(onDragCommit)

    var cellCoords: LayoutCoordinates? by remember { mutableStateOf(null) }

    val minHeight = if (widget.size == WidgetSize.FULL) 120.dp else 96.dp
    val elevation by animateDpAsState(if (isBeingDragged) 4.dp else 0.dp, label = "drag-elevation")
    val scale by animateFloatAsState(if (isBeingDragged) 1.05f else 1f, label = "drag-scale")

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .alpha(if (isBeingDragged) 0f else 1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
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

                    pressOffsetWithinCell.value = down.position
                    val cellOrigin = cellCoords?.boundsInWindow()?.topLeft ?: Offset.Zero
                    fingerInWindow.value = cellOrigin + down.position
                    draggingWidget.value = widget
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
                            fingerInWindow.value = finger
                            hitTest(finger, dragBounds, draggedKey = widgetId)?.let { key ->
                                currentOnDragHover(key)
                            }
                            edgeAutoScroll.update(finger.y)
                        }
                    } finally {
                        fingerInWindow.value = null
                        draggingWidget.value = null
                        edgeAutoScroll.stop()
                    }
                }
            },
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = debugLabel(widget),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onTransfer) {
                Icon(
                    imageVector = if (widget.isInYourWidgets) Icons.Default.Remove else Icons.Default.Add,
                    contentDescription = if (widget.isInYourWidgets)
                        "Move to Other widgets"
                    else
                        "Move to Your widgets",
                )
            }
        }
    }
}

@Composable
private fun FloatingWidgetCard(widget: GenericWidget) {
    var lifted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { lifted = true }
    val elevation by animateDpAsState(if (lifted) 4.dp else 0.dp, label = "float-elevation")
    val scale by animateFloatAsState(if (lifted) 1.05f else 1f, label = "float-scale")

    val minHeight = if (widget.size == WidgetSize.FULL) 120.dp else 96.dp

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = Modifier
            .fillMaxWidth(if (widget.size == WidgetSize.FULL) 1f else 0.5f)
            .heightIn(min = minHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = debugLabel(widget),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (widget.isInYourWidgets) Icons.Default.Remove else Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SkeletonCell(
	size: WidgetSize,
	modifier: Modifier = Modifier,
) {
    val minHeight = if (size == WidgetSize.FULL) 120.dp else 96.dp
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {}
}

@Composable
private fun FailureCell(
	size: WidgetSize,
	modifier: Modifier = Modifier,
) {
    val minHeight = if (size == WidgetSize.FULL) 120.dp else 96.dp
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error,
                shape = RoundedCornerShape(12.dp),
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Failed to load",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun EmptyDropZone(
    message: String,
    isDragActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 96.dp)
            .border(
                width = if (isDragActive) 2.dp else 1.dp,
                color = if (isDragActive)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isDragActive) "Drop here" else message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorScreen(cause: Throwable) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Couldn't load widgets",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = cause.message ?: cause::class.simpleName.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Modifier.bindBounds(
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

private fun cellSize(state: WidgetState): WidgetSize = when (state) {
    is WidgetState.Loaded -> state.widget.size
    is WidgetState.Skeleton -> state.size
    is WidgetState.Failure -> state.size
}

private fun hitTest(
    finger: Offset,
    bounds: Map<String, Rect>,
    draggedKey: String,
): String? = bounds.entries.firstOrNull { (key, rect) ->
    key != draggedKey && rect.contains(finger)
}?.key

private fun debugLabel(widget: GenericWidget): String = when (widget) {
    is GenericWidget.InvestmentEntryPoint -> "Investment · ${widget.id}"
    is GenericWidget.Pfm -> "PFM · ${widget.id}"
    is GenericWidget.Tile.Monizze -> "Monizze · ${widget.id}"
    is GenericWidget.Tile.Cashback -> "Cashback · ${widget.id}"
    is GenericWidget.Tile.Pluxee -> "Pluxee · ${widget.id}"
}

@Composable
private fun rememberEdgeAutoScroll(
    lazyGridState: LazyGridState,
    bandHeightDp: Dp = 80.dp,
): EdgeAutoScroll {
    val scope = rememberCoroutineScope()
    val band = with(LocalDensity.current) { bandHeightDp.toPx() }
    return remember(lazyGridState, band, scope) {
        EdgeAutoScroll(lazyGridState, scope, band)
    }
}

private class EdgeAutoScroll(
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
