@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
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

    var reorderMode by remember { mutableStateOf(true) }
    val lazyGridState = rememberLazyGridState()
    val controller = rememberDragController(lazyGridState)


    // TopAppBar scroll behaviour swap: out of reorder mode the bar collapses normally; in
    // reorder mode it stays pinned so it doesn't animate in/out while the user drags. The
    // state isn't shared between the two behaviours — entering reorder mode resets the bar
    // to its expanded height, which is a known POC limitation.
    val collapsibleScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scrollBehavior = collapsibleScrollBehavior

    // Window-coord positions captured from the LazyGrid and TopAppBar slots; consumed by the
    // root-level scrim, Done bar, and the reorder overlay that mirrors widget positions.
    var gridLeftInWindow by remember { mutableStateOf(0) }
    var gridTopInWindow by remember { mutableStateOf(0) }
    var topBarBottomInWindow by remember { mutableStateOf(0) }

    // Root-level Box wraps the Scaffold; scrim, Done bar, and the floating-widget Popup are
    // siblings of the Scaffold so they can paint above the TopAppBar / NavigationBar.
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                MediumTopAppBar(
                    title = { Text("Dashboard") },
                    scrollBehavior = scrollBehavior,
                    modifier = Modifier.onGloballyPositioned { coords ->
                        topBarBottomInWindow = coords.boundsInWindow().bottom.toInt()
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = true,
                        onClick = {},
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text("Home") },
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = {},
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("Search") },
                    )
                    NavigationBarItem(
                        selected = false,
                        onClick = {},
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            },
        ) { innerPadding ->
            LazyVerticalGrid(
                state = lazyGridState,
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .systemGestureExclusion()
                    .onGloballyPositioned { coords ->
                        controller.edgeAutoScroll.bindGridBounds(coords)
                        val rect = coords.boundsInWindow()
                        gridTopInWindow = rect.top.toInt()
                        gridLeftInWindow = rect.left.toInt()
                    },
            ) {
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
                            //WidgetMenuAction.Reorder -> reorderMode = true
                            else -> Unit
                        }
                    },
                )

                item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
                    FooterCard()
                }
            }
        }

        // Edge-to-edge scrim (no cutout), then a duplicate of the widget section rendered above
        // it. The duplicate mirrors each visible item's position from `lazyGridState.layoutInfo`,
        // so it lines up pixel-perfectly with the originals underneath the scrim. The duplicate's
        // items are interactive (they sit above the scrim in z-order); the originals are inert
        // because the scrim swallows touches in the regions between duplicate items.

        // Floating drag overlay as a Popup so it paints above the TopAppBar / NavigationBar /
        // scrim. Popup positions in window coords directly (no boxOrigin subtraction needed —
        // the Popup's anchor is the screen-root Box at (0, 0)).
        val finger = controller.fingerInWindow.value
        val draggingWidget = controller.draggingWidget.value
        if (finger != null && draggingWidget != null) {
            val overhangPx = with(LocalDensity.current) { OVERHANG_DP.toPx() }
            val floatingWindowPos = finger - controller.pressOffsetWithinCell.value -
                Offset(overhangPx, overhangPx)
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(
                    floatingWindowPos.x.roundToInt(),
                    floatingWindowPos.y.roundToInt(),
                ),
            ) {
                FloatingWidgetCard(widget = draggingWidget)
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
private fun BoxScope.ReorderScrim() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = SCRIM_ALPHA))
            .pointerInput(Unit) { detectTapGestures(onPress = { /* swallow */ }) }
            .zIndex(1f),
    )
}

/**
 * Duplicate of the widget section rendered above the scrim. Iterates
 * `lazyGridState.layoutInfo.visibleItemsInfo`, filters to section entries, and renders each
 * one as a Box absolutely positioned at the original's window coords. The duplicate items
 * carry the drag pipeline (bindBounds + dragSource) just like the originals; both layers'
 * `bindBounds` write to the same `dragBounds` map but at the same position, so hit-testing
 * is consistent. Touches reach the duplicate (higher z-index than the scrim); the original
 * underneath is shielded by the scrim.
 */
@Composable
private fun BoxScope.ReorderOverlay(
    entries: List<GridEntry>,
    dragState: DragState?,
    lazyGridState: LazyGridState,
    controller: DragController,
    gridLeftInWindow: Int,
    gridTopInWindow: Int,
    onDragStart: (String) -> Unit,
    onDragHover: (String) -> Unit,
    onDragCommit: () -> Unit,
    onTransfer: (String) -> Unit,
) {
    val density = LocalDensity.current
    val sectionKeys = remember(entries) { entries.map { it.key }.toSet() }
    val visibleSection by remember(lazyGridState, sectionKeys) {
        derivedStateOf {
            lazyGridState.layoutInfo.visibleItemsInfo.filter { it.key in sectionKeys }
        }
    }

    visibleSection.forEach { info ->
        val entry = entries.firstOrNull { it.key == info.key } ?: return@forEach
        val widthDp = with(density) { info.size.width.toDp() }
        val heightDp = with(density) { info.size.height.toDp() }
        val x = info.offset.x + gridLeftInWindow
        val y = info.offset.y + gridTopInWindow

        androidx.compose.runtime.key(entry.key) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(x, y) }
                    .size(width = widthDp, height = heightDp)
                    .zIndex(2f),
            ) {
                when (entry) {
                    is GridEntry.Header -> HeaderCell(
                        title = entry.title,
                        modifier = Modifier.bindBounds(entry.key, controller.dragBounds),
                    )
                    is GridEntry.Empty -> EmptyDropZone(
                        message = entry.message,
                        isDragActive = dragState != null,
                        modifier = Modifier.bindBounds(entry.key, controller.dragBounds),
                    )
                    is GridEntry.RowFiller -> RowFillerCell(
                        targetKey = entry.key,
                        controller = controller,
                    )
                    is GridEntry.Cell -> when (val s = entry.state) {
                        is WidgetState.Loaded -> WidgetCard(
                            widget = s.widget,
                            isBeingDragged = dragState?.draggedWidget?.id == s.widget.id,
                            controller = controller,
                            reorderMode = true,
                            onDragStart = onDragStart,
                            onDragHover = onDragHover,
                            onDragCommit = onDragCommit,
                            onTransfer = { onTransfer(s.widget.id) },
                            onMenuAction = { /* unused: menu can't open in reorder mode */ },
                            modifier = Modifier.fillMaxSize(),
                        )
                        is WidgetState.Skeleton -> SkeletonCell(
                            size = s.size,
                            modifier = Modifier.fillMaxSize(),
                        )
                        is WidgetState.Failure -> FailureCell(
                            size = s.size,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Solid `Surface` strip pinned directly below the TopAppBar. Holds the "Done" button that
 * exits reorder mode. Rendered above scrim + overlay ([zIndex] 3f) so it stays readable
 * even if a duplicate item would otherwise sit under the same Y range. [topOffsetPx] is
 * the TopAppBar's bottom in window coords, captured via `onGloballyPositioned`.
 */
@Composable
private fun BoxScope.DoneBar(topOffsetPx: Int, onClick: () -> Unit) {
    val topOffsetDp = with(LocalDensity.current) { topOffsetPx.toDp() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = topOffsetDp)
            .zIndex(3f),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            TextButton(onClick = onClick) {
                Text("Done")
            }
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
				setMenuExpanded = { menuExpanded = it}
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
