@file:OptIn(ExperimentalFoundationApi::class)

package com.arthlem.dragdrop

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val LONG_PRESS_TIMEOUT_MS = 200L

private fun acceptPlainText(event: DragAndDropEvent): Boolean =
    event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)

@Composable
private fun rememberDropTarget(
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
): DragAndDropTarget {
    val currentOnHover by rememberUpdatedState(onHover)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnEnded by rememberUpdatedState(onEnded)
    return remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { currentOnHover() }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentOnDrop()
                return true
            }
            override fun onEnded(event: DragAndDropEvent) { currentOnEnded() }
        }
    }
}

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
    val commitIfDragging: () -> Unit = remember(viewModel) {
        { if (viewModel.dragState != null) viewModel.onDragCommit() }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .systemGestureExclusion(),
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
                    onHover = { viewModel.onDragHover(entry.key) },
                    onDrop = { viewModel.onDragCommit() },
                    onEnded = commitIfDragging,
                    modifier = Modifier.animateItem(),
                )
                is GridEntry.Empty -> EmptyDropZone(
                    message = entry.message,
                    isDragActive = dragState != null,
                    onHover = { viewModel.onDragHover(entry.key) },
                    onDrop = { viewModel.onDragCommit() },
                    onEnded = commitIfDragging,
                    modifier = Modifier.animateItem(),
                )
                is GridEntry.Cell -> when (val s = entry.state) {
                    is WidgetState.Loaded -> WidgetCard(
                        state = s,
                        isBeingDragged = dragState?.draggedWidget?.id == s.id,
                        onDragStart = { viewModel.onDragStart(s.id) },
                        onHover = { viewModel.onDragHover(entry.key) },
                        onDrop = { viewModel.onDragCommit() },
                        onEnded = commitIfDragging,
                        onTransfer = { viewModel.onTransfer(s.id) },
                        modifier = Modifier.animateItem(),
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
}

@Composable
private fun LazyGridItemScope.HeaderCell(
    title: String,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dropTarget = rememberDropTarget(onHover, onDrop, onEnded)
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp)
            .dragAndDropTarget(
                shouldStartDragAndDrop = ::acceptPlainText,
                target = dropTarget,
            ),
    )
}

@Composable
private fun LazyGridItemScope.WidgetCard(
    state: WidgetState.Loaded,
    isBeingDragged: Boolean,
    onDragStart: () -> Unit,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
    onTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val widgetId = state.id
    val cardLayer = rememberGraphicsLayer()
    val dropTarget = rememberDropTarget(onHover, onDrop, onEnded)

    val minHeight = if (state.size == WidgetSize.FULL) 120.dp else 96.dp
    val elevation by animateDpAsState(if (isBeingDragged) 4.dp else 0.dp, label = "drag-elevation")
    val scale by animateFloatAsState(if (isBeingDragged) 1.05f else 1f, label = "drag-scale")

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .drawWithContent {
                cardLayer.record { this@drawWithContent.drawContent() }
                if (!isBeingDragged) drawLayer(cardLayer)
            }
            .dragAndDropTarget(
                shouldStartDragAndDrop = ::acceptPlainText,
                target = dropTarget,
            )
            .dragAndDropSource(drawDragDecoration = { drawLayer(cardLayer) }) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (detectShortLongPress(down.id, LONG_PRESS_TIMEOUT_MS)) {
                        currentOnDragStart()
                        startTransfer(
                            DragAndDropTransferData(
                                ClipData.newPlainText("widgetId", widgetId),
                            )
                        )
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
                text = debugLabel(state),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onTransfer) {
                Icon(
                    imageVector = if (state.isInYourWidgets) Icons.Default.Remove else Icons.Default.Add,
                    contentDescription = if (state.isInYourWidgets)
                        "Move to Other widgets"
                    else
                        "Move to Your widgets",
                )
            }
        }
    }
}

@Composable
private fun LazyGridItemScope.SkeletonCell(
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
private fun LazyGridItemScope.FailureCell(
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
private fun LazyGridItemScope.EmptyDropZone(
    message: String,
    isDragActive: Boolean,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dropTarget = rememberDropTarget(onHover, onDrop, onEnded)
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
            )
            .dragAndDropTarget(
                shouldStartDragAndDrop = ::acceptPlainText,
                target = dropTarget,
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
    is WidgetState.Loaded -> state.size
    is WidgetState.Skeleton -> state.size
    is WidgetState.Failure -> state.size
}

private fun debugLabel(state: WidgetState.Loaded): String = when (state) {
    is WidgetState.Loaded.InvestmentEntryPoint -> "Investment · ${state.id}"
    is WidgetState.Loaded.Pfm -> "PFM · ${state.id}"
    is WidgetState.Loaded.Tile.Monizze -> "Monizze · ${state.id}"
    is WidgetState.Loaded.Tile.Cashback -> "Cashback · ${state.id}"
    is WidgetState.Loaded.Tile.Pluxee -> "Pluxee · ${state.id}"
}

private suspend fun AwaitPointerEventScope.detectShortLongPress(
    pointerId: PointerId,
    timeoutMs: Long,
): Boolean {
    return try {
        withTimeout(timeoutMs) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId }
                if (change != null && !change.pressed) {
                    return@withTimeout false
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        true
    }
}
