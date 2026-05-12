@file:OptIn(ExperimentalFoundationApi::class)

package com.arthlem.dragdrop

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

private val OVERHANG_DP = 12.dp
private val BADGE_SIZE_DP = 28.dp
private const val SCRIM_ALPHA = 0.55f

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

    var reorderMode by remember { mutableStateOf(false) }
    var boxCoords: LayoutCoordinates? by remember { mutableStateOf(null) }
    val lazyGridState = rememberLazyGridState()
    val controller = rememberDragController(lazyGridState)

    BackHandler(enabled = reorderMode) { reorderMode = false }

    // Visible widget-section bounds, derived from LazyGridState's layout info. The widget section
    // is identified by entry keys; banner / footer items have auto-keys that won't match. Bounds
    // are in viewport coords (= local coords inside the parent Box, since the grid fills it).
    val sectionBoundsLocal: Pair<Int, Int>? by remember(lazyGridState, entries) {
        derivedStateOf {
            val sectionKeys = entries.map { it.key }.toSet()
            val infos = lazyGridState.layoutInfo.visibleItemsInfo.filter { it.key in sectionKeys }
            if (infos.isEmpty()) null
            else infos.minOf { it.offset.y } to infos.maxOf { it.offset.y + it.size.height }
        }
    }

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
            // Non-widget items demonstrate the "general list with arbitrary items" structure —
            // widget reordering must coexist with everything else in the same parent grid.
            item(span = { GridItemSpan(maxLineSpan) }, key = "banner") {
                BannerCard()
            }

            widgetSection(
                entries = entries,
                dragState = dragState,
                controller = controller,
                reorderMode = reorderMode,
                onDragStart = { viewModel.onDragStart(it) },
                onDragHover = { viewModel.onDragHover(it) },
                onDragCommit = { viewModel.onDragCommit() },
                onTransfer = { viewModel.onTransfer(it) },
                onMenuAction = { action, _ ->
                    when (action) {
                        WidgetMenuAction.Reorder -> reorderMode = true
                        // ManageWidgets and per-widget actions are stubs in the POC.
                        else -> Unit
                    }
                },
            )

            item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                FooterCard()
            }
        }

        // Reorder-mode visuals: scrim cutout (top + bottom strips around the widget section)
        // and a centered Done button overlaid above the scrim.
        if (reorderMode) {
            ReorderScrim(sectionBoundsLocal = sectionBoundsLocal)
            DoneButton(onClick = { reorderMode = false })
        }

        // Floating drag overlay (highest z so it paints above the scrim too).
        val finger = controller.fingerInWindow.value
        val widget = controller.draggingWidget.value
        if (finger != null && widget != null) {
            val boxOriginInWindow = boxCoords?.positionInWindow() ?: Offset.Zero
            val overhangPx = with(LocalDensity.current) { OVERHANG_DP.toPx() }
            val floatingTopLeft = finger - controller.pressOffsetWithinCell.value -
                boxOriginInWindow - Offset(overhangPx, overhangPx)
            Box(
                modifier = Modifier
                    .offset { IntOffset(floatingTopLeft.x.roundToInt(), floatingTopLeft.y.roundToInt()) }
                    .zIndex(2f),
            ) {
                FloatingWidgetCard(widget = widget)
            }
        }
    }
}

/**
 * Emits the widget entries (headers, cells, fillers, empty placeholders) into the parent
 * [LazyVerticalGrid] via [LazyGridScope.items]. The drag pipeline (`bindBounds`, `dragSource`)
 * only attaches when [reorderMode] is true; otherwise long-press opens a [DropdownMenu] of
 * [WidgetMenuAction]s.
 */
private fun LazyGridScope.widgetSection(
    entries: List<GridEntry>,
    dragState: DragState?,
    controller: DragController,
    reorderMode: Boolean,
    onDragStart: (String) -> Unit,
    onDragHover: (String) -> Unit,
    onDragCommit: () -> Unit,
    onTransfer: (String) -> Unit,
    onMenuAction: (WidgetMenuAction, GenericWidget) -> Unit,
) {
    items(
        items = entries,
        key = { it.key },
        span = { entry ->
            when (entry) {
                is GridEntry.Header, is GridEntry.Empty -> GridItemSpan(maxLineSpan)
                is GridEntry.RowFiller -> GridItemSpan(1)
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
            is GridEntry.RowFiller -> RowFillerCell(
                targetKey = entry.key,
                controller = controller,
                modifier = Modifier.animateItem(),
            )
            is GridEntry.Cell -> when (val s = entry.state) {
                is WidgetState.Loaded -> WidgetCard(
                    widget = s.widget,
                    isBeingDragged = dragState?.draggedWidget?.id == s.widget.id,
                    controller = controller,
                    reorderMode = reorderMode,
                    onDragStart = onDragStart,
                    onDragHover = onDragHover,
                    onDragCommit = onDragCommit,
                    onTransfer = { onTransfer(s.widget.id) },
                    onMenuAction = { action -> onMenuAction(action, s.widget) },
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

@Composable
private fun BoxScope.ReorderScrim(sectionBoundsLocal: Pair<Int, Int>?) {
    val density = LocalDensity.current
    val (topPx, bottomPx) = sectionBoundsLocal ?: (0 to 0)
    val topDp = with(density) { topPx.coerceAtLeast(0).toDp() }
    val bottomDp = with(density) { bottomPx.coerceAtLeast(0).toDp() }

    // Top strip: from screen top to widget section's top.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(topDp)
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .pointerInput(Unit) { detectTapGestures(onPress = { /* swallow */ }) }
            .zIndex(1f),
    )
    // Bottom strip: from widget section's bottom to screen bottom. fillMaxSize + padding-top
    // gives us "everything below this Y," which is what we want.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = bottomDp)
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .pointerInput(Unit) { detectTapGestures(onPress = { /* swallow */ }) }
            .zIndex(1f),
    )
}

@Composable
private fun BoxScope.DoneButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp)
            .zIndex(1.5f),
        contentAlignment = Alignment.TopCenter,
    ) {
        Button(onClick = onClick) {
            Text("Done")
        }
    }
}

@Composable
private fun BannerCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "Welcome to your dashboard",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun FooterCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "End of dashboard",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
    reorderMode: Boolean,
    onDragStart: (String) -> Unit,
    onDragHover: (String) -> Unit,
    onDragCommit: () -> Unit,
    onTransfer: () -> Unit,
    onMenuAction: (WidgetMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val elevation by animateDpAsState(if (isBeingDragged) 4.dp else 0.dp, label = "drag-elevation")
    val scale by animateFloatAsState(if (isBeingDragged) 1.05f else 1f, label = "drag-scale")
    var menuExpanded by remember { mutableStateOf(false) }

    // The behaviour switch: long-press → drag (reorder mode) vs. long-press → menu (default).
    // Both modifiers are pointerInput-based; swapping the entire chain is fine because Compose
    // disposes the old layout-node modifiers and instantiates the new ones, restarting the
    // pointerInput coroutine.
    val behaviorModifier: Modifier = if (reorderMode) {
        Modifier
            .bindBounds(widget.id, controller.dragBounds)
            .dragSource(
                widget = widget,
                controller = controller,
                onDragStart = onDragStart,
                onDragHover = onDragHover,
                onDragCommit = onDragCommit,
            )
    } else {
        Modifier.pointerInput(widget.id) {
            detectTapGestures(onLongPress = { menuExpanded = true })
        }
    }

    Box(modifier = modifier) {
        WidgetCardShell(
            widget = widget,
            elevation = elevation,
            scale = scale,
            alpha = if (isBeingDragged) 0f else 1f,
            onTransfer = onTransfer,
            isBeingDragged = isBeingDragged,
            showBadge = reorderMode,
            dragModifier = behaviorModifier,
        )

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            widget.menuActions().forEach { action ->
                DropdownMenuItem(
                    text = { Text(actionLabel(action)) },
                    onClick = {
                        menuExpanded = false
                        onMenuAction(action)
                    },
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
    WidgetCardShell(
        widget = widget,
        elevation = elevation,
        scale = scale,
        alpha = 1f,
        onTransfer = null,
        isBeingDragged = false,
        showBadge = true,
        modifier = Modifier.fillMaxWidth(if (widget.size == WidgetSize.FULL) 1f else 0.5f),
    )
}

/**
 * Outer Box reserves layout space for the overhanging [TransferBadge] (top + start padding).
 * Inner Box holds the visible card; [dragModifier] is where callers attach `bindBounds` /
 * `dragSource` (or the long-press → menu pointerInput in default mode). Aligning those
 * on the inner Box means adjacent cells' rects don't overlap in the overhang region —
 * eliminating cross-cell hover flicker.
 */
@Composable
private fun WidgetCardShell(
    widget: GenericWidget,
    elevation: Dp,
    scale: Float,
    alpha: Float,
    onTransfer: (() -> Unit)?,
    isBeingDragged: Boolean,
    showBadge: Boolean,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
) {
    val height = if (widget.size == WidgetSize.FULL) 120.dp else 96.dp
    Box(modifier = modifier.padding(top = OVERHANG_DP, start = OVERHANG_DP)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .alpha(alpha)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .then(dragModifier),
        ) {
            WidgetCardContent(widget = widget, elevation = elevation)
        }
        if (showBadge) {
            TransferBadge(
                widget = widget,
                onClick = onTransfer,
                isBeingDragged = isBeingDragged,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = -OVERHANG_DP, y = -OVERHANG_DP),
            )
        }
    }
}

@Composable
private fun WidgetCardContent(
    widget: GenericWidget,
    elevation: Dp,
    modifier: Modifier = Modifier,
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        modifier = modifier
            .fillMaxSize()
            .shadow(elevation, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
    ) {
        when (widget) {
            is GenericWidget.Tile.Cashback -> CashbackBody(widget)
            else -> DefaultDebugBody(widget)
        }
    }
}

@Composable
private fun DefaultDebugBody(widget: GenericWidget) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = debugLabel(widget),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CashbackBody(widget: GenericWidget.Tile.Cashback) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Easy Cashback",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = "Your offers",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (widget.offers.isNotEmpty()) {
            OfferStrip(offers = widget.offers)
        }
    }
}

@Composable
private fun OfferStrip(offers: List<GenericWidget.Offer>) {
    val visible = offers.take(3)
    val overflow = (offers.size - visible.size).coerceAtLeast(0)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        visible.forEach { offer ->
            OfferTile(offer = offer, modifier = Modifier.weight(1f))
        }
        if (overflow > 0) {
            OverflowTile(count = overflow, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun OfferTile(offer: GenericWidget.Offer, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = offer.initial,
            color = brandColor(offer.palette),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun OverflowTile(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+$count",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun brandColor(palette: GenericWidget.BrandPalette): Color = when (palette) {
    GenericWidget.BrandPalette.Nike -> Color.Black
    GenericWidget.BrandPalette.Levis -> Color(0xFFCC0000)
    GenericWidget.BrandPalette.Diesel -> Color(0xFFE60012)
    GenericWidget.BrandPalette.Adidas -> Color.Black
    GenericWidget.BrandPalette.Zara -> Color.Black
    GenericWidget.BrandPalette.HM -> Color(0xFFE50010)
    GenericWidget.BrandPalette.Apple -> Color.Black
    GenericWidget.BrandPalette.Samsung -> Color(0xFF1428A0)
    GenericWidget.BrandPalette.Ikea -> Color(0xFF0058A3)
    GenericWidget.BrandPalette.Generic -> Color.DarkGray
}

@Composable
private fun TransferBadge(
    widget: GenericWidget,
    onClick: (() -> Unit)?,
    isBeingDragged: Boolean,
    modifier: Modifier = Modifier,
) {
    val icon = if (widget.isInYourWidgets) Icons.Default.Remove else Icons.Default.Add
    val description = if (widget.isInYourWidgets) "Move to Other widgets" else "Move to Your widgets"
    val badgeModifier = modifier
        .size(BADGE_SIZE_DP)
        .alpha(if (isBeingDragged) 0f else 1f)

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = badgeModifier,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.padding(6.dp),
            )
        }
    } else {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = badgeModifier,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}

@Composable
private fun RowFillerCell(
    targetKey: String,
    controller: DragController,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.padding(top = OVERHANG_DP, start = OVERHANG_DP)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .bindBounds(targetKey, controller.dragBounds),
        )
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
