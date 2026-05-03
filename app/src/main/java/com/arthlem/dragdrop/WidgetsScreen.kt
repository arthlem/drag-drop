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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.viewmodel.compose.viewModel

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
fun WidgetsScreen(viewModel: WidgetsViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    when (uiState) {
        UiState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        UiState.Success -> WidgetsContent(viewModel)
    }
}

@Composable
private fun WidgetsContent(viewModel: WidgetsViewModel) {
    val entries: List<GridEntry> = viewModel.entries
    val dragState = viewModel.dragState
    val commitIfDragging: () -> Unit = {
        if (viewModel.dragState != null) viewModel.onDragCommit()
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
                    is GridEntry.Item ->
                        if (entry.widget.isFullSpan) GridItemSpan(maxLineSpan)
                        else GridItemSpan(1)
                }
            },
            contentType = { it::class },
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
                is GridEntry.Item -> WidgetCard(
                    widget = entry.widget,
                    isBeingDragged = dragState?.draggedWidget?.id == entry.widget.id,
                    onDragStart = { viewModel.onDragStart(entry.widget.id) },
                    onHover = { viewModel.onDragHover(entry.key) },
                    onDrop = { viewModel.onDragCommit() },
                    onEnded = commitIfDragging,
                    onTransfer = { viewModel.onTransfer(entry.widget.id) },
                    modifier = Modifier.animateItem(),
                )
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
    widget: Widget,
    isBeingDragged: Boolean,
    onDragStart: () -> Unit,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
    onTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val widgetId = widget.id
    val cardLayer = rememberGraphicsLayer()
    val dropTarget = rememberDropTarget(onHover, onDrop, onEnded)

    val minHeight = if (widget.isFullSpan) 120.dp else 96.dp
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
                text = widget.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onTransfer) {
                Icon(
                    imageVector = if (widget.isYours) Icons.Default.Remove else Icons.Default.Add,
                    contentDescription = if (widget.isYours)
                        "Move to Other widgets"
                    else
                        "Move to Your widgets",
                )
            }
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
