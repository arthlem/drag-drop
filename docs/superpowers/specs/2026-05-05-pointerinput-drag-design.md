# pointerInput drag-and-drop replacement

**Date:** 2026-05-05
**Status:** approved
**Affected files:**
- `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` (heavy edit)
- `CLAUDE.md` (architecture / drop targets / drag visualization / long-press / composable organization sections)
- No changes: `WidgetsViewModel.kt`, `WidgetState.kt`, `GenericWidget.kt`, `WidgetsUseCase.kt`, `MainActivity.kt`, gradle files

## Problem

Compose Foundation 1.11's `Modifier.dragAndDropSource` removed the trailing-block API, leaving only a synchronous `transferData: (Offset) -> DragAndDropTransferData?` lambda whose long-press detection is internal. Custom long-press timing (the previous 200 ms `detectShortLongPress`) is gone; the platform default (~500 ms) is the only option until androidx exposes the `detectDragStart` parameter publicly. Several existing limitations also remain:

1. **Touch-center glitch.** The system shadow's touch anchor is hard-coded to the source's center; the shadow visually jumps from the press point at drag start.
2. **Snapshot timing.** The drag shadow is captured at `startTransfer` time, before `animateDpAsState` / `animateFloatAsState` have progressed — scale and elevation lift never show during drag.
3. **No continuous source tracking.** The system owns input once the drag begins; the source's pointerInput coroutine doesn't see further pointer events.

These are inherent to the native D&D path, not Compose 1.11 specifically. The hard constraint in CLAUDE.md was "no third-party reorder libraries" (because of a known mixed-span flicker bug in `Calvin-LL/Reorderable`); rolling our own pointerInput-based reorderer is not that library, and we own the geometry math.

This spec replaces the native `dragAndDropSource` / `dragAndDropTarget` plumbing with a `pointerInput`-based gesture pipeline. The ViewModel surface (`onDragStart` / `onDragHover` / `onDragCommit` / `onDragCancel` / `onTransfer`) and all section/reorder/reconciliation logic stay unchanged — this is a gesture-and-visualization-layer change.

## Decision

- **Custom long-press timing** — restored to 200 ms via `withTimeoutOrNull(LONG_PRESS_TIMEOUT_MS) { ... }` inside `awaitEachGesture`.
- **Per-cell `pointerInput` source** — the gesture detector lives on each `WidgetCard` (Loaded cells only). Once long-press succeeds the same coroutine drives the rest of the drag through release.
- **Bounds-map hit-testing** — every drop-target composable registers its `boundsInWindow()` in a `SnapshotStateMap<String, Rect>` named `dragBounds` shared across `WidgetsContent`. The dragging cell's pointerInput hit-tests against this map on each pointer event.
- **Inline floating overlay** — the source slot stays in `_entries` at its current position with `Modifier.alpha(0f)`; a sibling `Box` overlay renders a `FloatingWidgetCard` that tracks the finger using window-coord math (`floatingPos = finger - pressOffsetWithinCell - boxOriginInWindow`).
- **Edge auto-scroll** — a small `EdgeAutoScroll` helper drives `LazyGridState.scrollBy` while the finger is inside an 80 dp band at the top or bottom of the grid.
- **No third-party libraries** — pure Compose Foundation primitives (`pointerInput`, `awaitEachGesture`, `awaitFirstDown`, `awaitPointerEvent`, `withTimeoutOrNull`, `onGloballyPositioned`, `LazyGridState.scrollBy`).

## Architecture

### Gesture state machine (per `WidgetCard`)

A single `Modifier.pointerInput(widget.id) { awaitEachGesture { ... } }` owns the entire drag lifecycle for a Loaded cell:

```kotlin
Modifier.pointerInput(widget.id) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)

        val longPressed = withTimeoutOrNull(LONG_PRESS_TIMEOUT_MS) {
            while (true) {
                val ev = awaitPointerEvent()
                val change = ev.changes.firstOrNull { it.id == down.id }
                if (change != null && !change.pressed) return@withTimeoutOrNull false
            }
            @Suppress("UNREACHABLE_CODE") true
        } ?: true   // null = withTimeout fired = press held long enough

        if (!longPressed) return@awaitEachGesture

        viewModel.onDragStart(widget.id)
        pressOffsetWithinCell.value = down.position
        cellOriginInWindow.value = layoutCoordinates?.boundsInWindow()?.topLeft ?: Offset.Zero
        draggingWidget.value = widget

        try {
            while (true) {
                val ev = awaitPointerEvent()
                val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    viewModel.onDragCommit()
                    break
                }
                fingerInWindow.value = cellOriginInWindow.value + change.position
                hitTest(fingerInWindow.value!!, dragBounds, draggedKey = widget.id)?.let { key ->
                    viewModel.onDragHover(key)
                }
                edgeAutoScroll.update(fingerInWindow.value!!.y)
            }
        } finally {
            fingerInWindow.value = null
            draggingWidget.value = null
            edgeAutoScroll.stop()
        }
    }
}
```

`LONG_PRESS_TIMEOUT_MS = 200L` returns to `WidgetsScreen.kt` as a private file-level constant. Skeleton and Failure cells get no `pointerInput` — render-only as before.

### Bounds map (`dragBounds`)

A `SnapshotStateMap<String, Rect>` owned by `WidgetsContent`. Every drop target registers via `Modifier.onGloballyPositioned`:

```kotlin
// WidgetCard
Modifier.onGloballyPositioned { coords ->
    dragBounds[widget.id] = coords.boundsInWindow()
}

// HeaderCell — both Yours and Other variants
Modifier.onGloballyPositioned { coords ->
    dragBounds[headerKey] = coords.boundsInWindow()
}

// EmptyDropZone
Modifier.onGloballyPositioned { coords ->
    dragBounds[emptyKey] = coords.boundsInWindow()
}
```

Cleanup via `DisposableEffect(key) { onDispose { dragBounds.remove(key) } }` on each cell — entries leave the map when their composable unmounts (e.g., scroll-out from `LazyVerticalGrid`'s viewport).

**Hit-test resolution.** A `private fun hitTest(finger: Offset, bounds: Map<String, Rect>, draggedKey: String): String?` finds the first key (excluding `draggedKey`) whose rect contains `finger`. Iteration order: cells first, then headers, then empty zones — gives "drop on the most specific target" behavior when rects overlap during reflow.

**Drag-source exclusion.** The dragging cell's slot still occupies layout (alpha-0), so its bounds are in the map. The `draggedKey` parameter excludes it from hit-testing.

### Floating overlay and source-invisible mechanism

Three pieces of screen-level state owned by `WidgetsContent`:

```kotlin
val pressOffsetWithinCell = remember { mutableStateOf(Offset.Zero) }
val fingerInWindow = remember { mutableStateOf<Offset?>(null) }
val draggingWidget = remember { mutableStateOf<GenericWidget?>(null) }
```

**Source slot invisible.** `WidgetCard` reads `viewModel.dragState?.draggedWidget?.id == widget.id` and applies `Modifier.alpha(if (isBeingDragged) 0f else 1f)`. The `cardLayer = rememberGraphicsLayer()` and `Modifier.drawWithContent { record + skip }` machinery is removed. The slot still occupies layout space so adjacent cells reflow correctly via `animateItem`.

**Floating overlay.** A sibling `Box` rendered inside `WidgetsContent`'s root container (the same outer container that holds the `LazyVerticalGrid`):

```kotlin
Box(modifier = Modifier.fillMaxSize().onGloballyPositioned { boxCoords = it }) {
    LazyVerticalGrid(...) { ... }

    val finger = fingerInWindow.value
    val widget = draggingWidget.value
    if (finger != null && widget != null) {
        val boxOriginInWindow = boxCoords?.positionInWindow() ?: Offset.Zero
        val floatingTopLeft = finger - pressOffsetWithinCell.value - boxOriginInWindow
        Box(
            modifier = Modifier
                .offset { IntOffset(floatingTopLeft.x.roundToInt(), floatingTopLeft.y.roundToInt()) }
                .zIndex(1f)
        ) {
            FloatingWidgetCard(widget = widget)
        }
    }
}
```

`FloatingWidgetCard` is a thin renderer: same `Card` + `Row` + `Text(debugLabel(widget))` + `IconButton` body as `WidgetCard`, but no `pointerInput`, no `onGloballyPositioned`, no drop-target plumbing. It applies the lift animation (`scale = 1.05f`, `elevation = 4.dp`) via a `LaunchedEffect`-driven `animateFloatAsState` / `animateDpAsState` flip from 1f/0.dp to 1.05f/4.dp on first composition. Because it mounts fresh when `draggingWidget` becomes non-null, the lift actually animates visibly — the snapshot-timing limitation of native D&D goes away.

**Reorder math holds.** `pressOffsetWithinCell` is captured **once** at drag start (local to the original cell). `fingerInWindow` updates on every pointer event. The floating overlay's position is always `finger - pressOffsetWithinCell - boxOrigin`, expressed entirely in window coords up to the final local conversion. When `_entries` reorders during `onDragHover`, the source slot moves but the floating overlay doesn't — its position only depends on the finger.

### Edge auto-scroll

```kotlin
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

    fun bindGridBounds(coords: LayoutCoordinates) {
        val rect = coords.boundsInWindow()
        gridTopInWindow = rect.top
        gridBottomInWindow = rect.bottom
    }

    fun update(fingerY: Float) {
        val velocity = when {
            fingerY < gridTopInWindow + bandPx ->
                -lerp(MAX_PX_PER_FRAME, 0f, (fingerY - gridTopInWindow) / bandPx)
            fingerY > gridBottomInWindow - bandPx ->
                lerp(0f, MAX_PX_PER_FRAME, (fingerY - (gridBottomInWindow - bandPx)) / bandPx)
            else -> 0f
        }
        if (velocity == 0f) { stop(); return }
        if (job?.isActive == true) return

        job = scope.launch {
            while (isActive) {
                state.scrollBy(velocity)
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
```

**Integration.** `WidgetsContent` calls `rememberEdgeAutoScroll(gridState)` once. The `LazyVerticalGrid`'s container `Modifier.onGloballyPositioned { autoScroll.bindGridBounds(it) }` keeps the grid's window-coord bounds current. The drag pointerInput coroutine calls `autoScroll.update(finger.y)` on every pointer event. The source's `finally` block calls `autoScroll.stop()`.

**Velocity ramp.** Linear interpolation gives "deeper into the band → faster scroll". `MAX_PX_PER_FRAME = 12f` (~720 px/sec at 60 fps) is tunable.

## ViewModel surface (unchanged)

The ViewModel's public API stays exactly as it is today:
- `onDragStart(widgetId: String)` — capture origin index/section into `DragState`.
- `onDragHover(targetKey: String)` — direction-aware insert; `reconcileIsYoursForDraggedWidget`.
- `onDragCommit()` — three-branch section logic (Yours→Yours reorder, Yours→Other snap, Other→Other revert).
- `onDragCancel()` — restore to `originalIndex`.
- `onTransfer(widgetId: String)` — `+`/`−` button programmatic move.
- Reads: `dragState`, `entries`, `uiState`.

`DragState`, `GridEntry`, `WidgetState`, `GenericWidget`, `cellOf(widget)`, `pendingEntries` / `flushPendingEntriesIfAny`, `reconcileEmptyPlaceholders`, all `_entries` mutation logic — unchanged. The pointerInput rewrite is a gesture/visualization-layer change only; the state machine sits behind the same callback boundary.

## Removed surface

| Removed | Replacement |
|---|---|
| `acceptPlainText(event)` | n/a — no `dragAndDropTarget` to gate. |
| `rememberDropTarget(onHover, onDrop, onEnded)` | n/a — no `DragAndDropTarget` instances. |
| `Modifier.dragAndDropSource(drawDragDecoration, transferData)` | `Modifier.pointerInput(widget.id) { awaitEachGesture { ... } }`. |
| `Modifier.dragAndDropTarget(shouldStartDragAndDrop, target)` (×3 sites) | `Modifier.onGloballyPositioned { dragBounds[key] = coords.boundsInWindow() }` + `DisposableEffect(key) { onDispose { dragBounds.remove(key) } }`. |
| `cardLayer = rememberGraphicsLayer()`, `Modifier.drawWithContent { record + skip }` | `Modifier.alpha(if (isBeingDragged) 0f else 1f)` on the source slot; floating overlay draws fresh content. |
| `commitIfDragging` lambda hoisted in `WidgetsContent` | n/a — no shared `onEnded` callback path. The pointerInput coroutine handles its own release. |
| Imports: `dragAndDropSource`, `dragAndDropTarget`, `DragAndDropEvent`, `DragAndDropTarget`, `DragAndDropTransferData`, `mimeTypes`, `ClipData`, `ClipDescription`, `drawWithContent`, `rememberGraphicsLayer`, `drawLayer` | All unused after the rewrite. |

## What stays the same

- All four drag scenarios — Yours→Yours reorder, Other→Yours transfer, Yours→Other snap-to-top, Other→Other revert.
- Direction-aware insert during hover — `removeAt(currentIdx)` + `add(targetIdx)` math in the VM.
- `YOURS_HEADER_KEY` resolves to `+1` in `onDragHover`; all other keys resolve to their own index.
- Empty placeholder reconciliation — strip-and-rebuild, tail-first insertion order.
- Scale + elevation lift animation — same values (`1.05f`, `4.dp`), same `animateFloatAsState` / `animateDpAsState` APIs. Now visibly animates because the floating cell mounts fresh.
- `Modifier.systemGestureExclusion()` on the grid — predictive-back gesture protection unchanged.
- `Modifier.animateItem()` on non-dragging cells — handles slot reflow when `_entries` reorders during hover.
- Per-emission deferred-replay — `pendingEntries` + `flushPendingEntriesIfAny` semantics intact.
- `Snapshot.withMutableSnapshot` atomicity in mutation paths, `Snapshot.withoutReadObservation` on the drag-state read inside `collectLoadedFlow`.
- `UiState.Loading` / `Error` / `Loaded` rendering split.

## CLAUDE.md updates

The "Hard constraint" section is rewritten:

> **No third-party reorder libraries.** A confirmed bug in `Calvin-LL/Reorderable` causes flicker on mixed-span items in `LazyVerticalGrid`. Either Compose's native `dragAndDropSource` / `dragAndDropTarget` or a from-scratch `pointerInput`-based reorderer is acceptable as long as we own the geometry math. This project currently uses `pointerInput` because the native API does not yet expose a public custom long-press detector and because animations / touch-center fidelity / continuous source tracking are easier to control directly.

The "Drag visualization", "Drop targets", "Drop semantics", "Long-press detection", "Composable code organization", and "Files" sections are rewritten to reflect the new approach. Concrete edits land in the implementation plan.

## Verification

Manual on-device testing after install:

1. **Long-press timing.** Press-hold a Loaded cell for ~200 ms — drag begins. Pressing for less than ~150 ms and releasing should not start a drag (no source slot vanish, no floating overlay).
2. **Touch-center fidelity.** Long-press a cell at any point (corner, edge, center) and drag — the floating cell stays anchored to the same point under the finger. No mid-drag jump from press point to centered.
3. **Animation visibility.** During drag, the floating cell visibly animates from 1.0× to 1.05× scale and from 0.dp to 4.dp elevation in the first ~150–200 ms of drag. Reverses on release.
4. **All four drag scenarios** still work — Yours→Yours reorder, Other→Yours transfer, Yours→Other snap-to-top, Other→Other revert. Direction-aware insert math holds.
5. **Edge auto-scroll.** Add enough widgets that the grid scrolls (or temporarily seed 20+ in `FakeWidgetsUseCase.INITIAL_WIDGETS`). Drag to within ~80 dp of the top or bottom edge — grid auto-scrolls. Faster scroll closer to the edge. Stops on release or when finger leaves the band.
6. **Skeleton / Failure cells not draggable.** Long-press during the loading phase — nothing happens.
7. **Toggle button still type-preserving** — `+` / `−` on a `Monizze · m1` returns a `Monizze`, not a generic `Loaded`.
8. **Predictive-back not triggered** when dragging near the screen edge — `Modifier.systemGestureExclusion()` still applied.
9. **Loaded → Error → Loaded.** Flip `FAIL_ELIGIBILITY = true`, reinstall — error screen renders. Restore — loaded grid renders.

## Out of scope

- Cross-app or cross-window drops. Was not in scope before the migration; is not in scope now.
- Flinging / momentum after release. Cells animate to their final slot via `animateItem`; no extra physics.
- Multi-finger drag. Single pointer only — `awaitFirstDown` returns the first pointer; subsequent pointers are ignored.
- Drop-target highlight ring. Current behavior preserved — only `EmptyDropZone` changes appearance via `isDragActive`.
- Drag handle semantics. Long-press anywhere on the cell triggers drag.
- Accessibility / TalkBack. Native `dragAndDropSource` integrated with TalkBack via `semantics`; `pointerInput` does not. Production will need explicit `Modifier.semantics { customActions = ... }` for screen-reader users — deferred to v2.
- Auto-scroll horizontal axis. Only vertical edges trigger auto-scroll (the grid is vertical-scrolling).
