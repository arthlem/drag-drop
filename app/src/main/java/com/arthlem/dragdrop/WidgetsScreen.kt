@file:OptIn(ExperimentalFoundationApi::class)

package com.arthlem.dragdrop

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

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

    var boxCoords: LayoutCoordinates? by remember { mutableStateOf(null) }
    val lazyGridState = rememberLazyGridState()
    val controller = rememberDragController(lazyGridState)

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
                .onGloballyPositioned { coords -> controller.edgeAutoScroll.bindGridBounds(coords) },
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
                            .bindBounds(entry.key, controller.dragBounds),
                    )
                    is GridEntry.Empty -> EmptyDropZone(
                        message = entry.message,
                        isDragActive = dragState != null,
                        modifier = Modifier
                            .animateItem()
                            .bindBounds(entry.key, controller.dragBounds),
                    )
                    is GridEntry.Cell -> when (val s = entry.state) {
                        is WidgetState.Loaded -> WidgetCard(
                            widget = s.widget,
                            isBeingDragged = dragState?.draggedWidget?.id == s.widget.id,
                            controller = controller,
                            onDragStart = { viewModel.onDragStart(it) },
                            onDragHover = { viewModel.onDragHover(it) },
                            onDragCommit = { viewModel.onDragCommit() },
                            onTransfer = { viewModel.onTransfer(s.widget.id) },
                            modifier = Modifier
                                .animateItem()
                                .bindBounds(s.widget.id, controller.dragBounds),
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

        val finger = controller.fingerInWindow.value
        val widget = controller.draggingWidget.value
        if (finger != null && widget != null) {
            val boxOriginInWindow = boxCoords?.positionInWindow() ?: Offset.Zero
            val floatingTopLeft = finger - controller.pressOffsetWithinCell.value - boxOriginInWindow
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
    controller: DragController,
    onDragStart: (String) -> Unit,
    onDragHover: (String) -> Unit,
    onDragCommit: () -> Unit,
    onTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val elevation by animateDpAsState(if (isBeingDragged) 4.dp else 0.dp, label = "drag-elevation")
    val scale by animateFloatAsState(if (isBeingDragged) 1.05f else 1f, label = "drag-scale")
    val height = if (widget.size == WidgetSize.FULL) 120.dp else 96.dp

    WidgetCardContent(
        widget = widget,
        onTransfer = onTransfer,
        elevation = elevation,
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .alpha(if (isBeingDragged) 0f else 1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .dragSource(
                widget = widget,
                controller = controller,
                onDragStart = onDragStart,
                onDragHover = onDragHover,
                onDragCommit = onDragCommit,
            ),
    )
}

@Composable
private fun FloatingWidgetCard(widget: GenericWidget) {
    var lifted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { lifted = true }
    val elevation by animateDpAsState(if (lifted) 4.dp else 0.dp, label = "float-elevation")
    val scale by animateFloatAsState(if (lifted) 1.05f else 1f, label = "float-scale")
    val height = if (widget.size == WidgetSize.FULL) 120.dp else 96.dp

    WidgetCardContent(
        widget = widget,
        onTransfer = null,
        elevation = elevation,
        modifier = Modifier
            .fillMaxWidth(if (widget.size == WidgetSize.FULL) 1f else 0.5f)
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    )
}

@Composable
private fun WidgetCardContent(
    widget: GenericWidget,
    onTransfer: (() -> Unit)?,
    elevation: Dp,
    modifier: Modifier = Modifier,
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = modifier.shadow(elevation, RoundedCornerShape(12.dp)),
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
            if (onTransfer != null) {
                IconButton(onClick = onTransfer) {
                    Icon(
                        imageVector = if (widget.isInYourWidgets) Icons.Default.Remove else Icons.Default.Add,
                        contentDescription = if (widget.isInYourWidgets) "Move to Other widgets" else "Move to Your widgets",
                    )
                }
            } else {
                Icon(
                    imageVector = if (widget.isInYourWidgets) Icons.Default.Remove else Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

private fun cellSize(state: WidgetState): WidgetSize = when (state) {
    is WidgetState.Loaded -> state.widget.size
    is WidgetState.Skeleton -> state.size
    is WidgetState.Failure -> state.size
}

private fun debugLabel(widget: GenericWidget): String = when (widget) {
    is GenericWidget.InvestmentEntryPoint -> "Investment · ${widget.id}"
    is GenericWidget.Pfm -> "PFM · ${widget.id}"
    is GenericWidget.Tile.Monizze -> "Monizze · ${widget.id}"
    is GenericWidget.Tile.Cashback -> "Cashback · ${widget.id}"
    is GenericWidget.Tile.Pluxee -> "Pluxee · ${widget.id}"
}
