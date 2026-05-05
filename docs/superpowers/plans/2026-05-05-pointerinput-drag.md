# pointerInput drag-and-drop replacement

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the native `dragAndDropSource` / `dragAndDropTarget` pipeline with a from-scratch `pointerInput`-based reorderer that owns gesture detection, hit-testing, drag visualization, and edge auto-scroll — restoring custom long-press timing, touch-center fidelity, animation visibility during drag, and continuous source tracking.

**Architecture:** A per-`WidgetCard` `Modifier.pointerInput` runs a long-press detector (200 ms via `withTimeoutOrNull`), captures press offsets, then hit-tests against a screen-level `dragBounds: SnapshotStateMap<String, Rect>` populated by `Modifier.onGloballyPositioned` on every drop-target composable. The source slot stays at its `_entries` index with `Modifier.alpha(0f)` while a sibling `Box` overlay renders a `FloatingWidgetCard` that follows the finger using window-coord math. An `EdgeAutoScroll` helper drives `LazyGridState.scrollBy` while the finger sits within an 80 dp band at the grid's vertical edges. The ViewModel surface (`onDragStart` / `onDragHover` / `onDragCommit` / `onDragCancel` / `onTransfer`) and all section/reorder logic are unchanged.

**Tech Stack:** Kotlin 2.1.20, Compose BOM 2026.04.01 (Foundation/UI 1.11.0, Material3 1.4.0), Arrow Core 1.2.4. AGP 9.0.0-beta03 with built-in Kotlin support.

**Source spec:** `docs/superpowers/specs/2026-05-05-pointerinput-drag-design.md`

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` | rewrite (heavy) | Gesture detection (`pointerInput`), bounds-map hit-testing, floating overlay, edge auto-scroll, all composables |
| `CLAUDE.md` | modify | Architecture, Drop semantics, Drop targets, Drag visualization, Long-press detection, Composable code organization, Files sections |

No other files change. ViewModel, WidgetState, GenericWidget, WidgetsUseCase, MainActivity, gradle files all stay as-is.

---

## Notes for the implementing engineer

- The project uses AGP 9.0.0-beta03 with **built-in Kotlin support**. Do not apply `org.jetbrains.kotlin.android` — only `org.jetbrains.kotlin.plugin.compose` is in `plugins {}`.
- Tasks 3 and 4 each leave the project compilable but with degraded drag behavior between commits. Specifically, Task 3 removes `dragAndDropTarget` (so existing native D&D no longer fires hover/drop callbacks) but the source still uses `dragAndDropSource`; drag visually appears but doesn't reorder. Task 4 then swaps source to `pointerInput` and adds the floating overlay; drag becomes fully functional. The build passes after every task.
- Build verification: `./gradlew assembleDebug` after every task. Tasks 1–2 have no behavior change to verify; Task 5 is the final functional gate before docs.
- Repo is a git repository on branch `main`. Commits are pushed at the end of Task 7 (after on-device verification).

Current HEAD is `b360dda` (the spec commit). Baseline before this migration is `89f32a3` (the Compose 1.11 / dragAndDropSource API migration).

---

## Task 1: Add EdgeAutoScroll helper + LONG_PRESS_TIMEOUT_MS + hitTest

**Files:**
- Modify: `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt`

This task adds three independent helpers used by later tasks. All additions, no removals. The file compiles after this task with no behavior change.

- [ ] **Step 1: Add new imports at the top of `WidgetsScreen.kt`**

Insert these imports alphabetically into the existing import block (after `androidx.compose.foundation.systemGestureExclusion` and before the layout imports). The import order in the file is loose — alphabetical within each `androidx.*` group is sufficient:

```kotlin
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
```

Then add these in the `androidx.compose.runtime.*` group:

```kotlin
import androidx.compose.runtime.rememberCoroutineScope
```

Then in the `androidx.compose.ui.*` group:

```kotlin
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.lerp
```

Then `kotlinx.coroutines.*`:

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withFrameNanos
```

- [ ] **Step 2: Add `LONG_PRESS_TIMEOUT_MS` constant at file level**

Insert this private constant just before the existing `acceptPlainText` function (around line 58):

```kotlin
private const val LONG_PRESS_TIMEOUT_MS = 200L
```

- [ ] **Step 3: Add `hitTest` helper at file level**

Insert this private function just after `cellSize` (around line 386) and before `debugLabel`:

```kotlin
private fun hitTest(
    finger: Offset,
    bounds: Map<String, Rect>,
    draggedKey: String,
): String? = bounds.entries.firstOrNull { (key, rect) ->
    key != draggedKey && rect.contains(finger)
}?.key
```

The order of `bounds` iteration is the insertion order (`SnapshotStateMap` preserves it). Cells register first (during `items {}` rendering), then headers if they precede other items, then empty zones — that ordering works for "drop on the most specific target" because cells are inserted into the map in their visual order.

- [ ] **Step 4: Add `EdgeAutoScroll` class and `rememberEdgeAutoScroll` helper at file level**

Insert these at the very bottom of the file, after `debugLabel`:

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
                -lerp(MAX_PX_PER_FRAME, 0f, ((fingerY - gridTopInWindow) / bandPx).coerceIn(0f, 1f))
            fingerY > gridBottomInWindow - bandPx ->
                lerp(0f, MAX_PX_PER_FRAME, ((fingerY - (gridBottomInWindow - bandPx)) / bandPx).coerceIn(0f, 1f))
            else -> 0f
        }
        if (velocity == 0f) {
            stop()
            return
        }
        if (job?.isActive == true) {
            currentVelocity = velocity
            return
        }
        currentVelocity = velocity
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

    private var currentVelocity: Float = 0f

    companion object {
        private const val MAX_PX_PER_FRAME = 12f
    }
}
```

The `currentVelocity` state lets the running scroll job pick up new velocity values when the finger moves within the band (otherwise the job would be stuck at its initial velocity until restart).

- [ ] **Step 5: Verify build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. No call sites consume the new helpers yet, so this only validates that the code parses and the imports resolve.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt
git commit -m "$(cat <<'EOF'
feat: add EdgeAutoScroll helper, hitTest, and 200ms long-press constant

Pure additions in preparation for the pointerInput migration. The
EdgeAutoScroll class drives LazyGridState.scrollBy while the dragging
finger sits within a configurable band (default 80dp) at the grid's
vertical edges, with linear velocity ramp.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Wrap WidgetsContent grid in outer Box and add screen-level drag state

**Files:**
- Modify: `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt`

This task adds the screen-level state plumbing and the outer `Box` that will host both the grid and the floating overlay. No consumers wired yet.

- [ ] **Step 1: Add new imports at the top of `WidgetsScreen.kt`**

Insert into the existing import block:

```kotlin
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
```

(`getValue` is already imported.)

- [ ] **Step 2: Replace the body of `WidgetsContent`**

Find the current `WidgetsContent` composable (lines 91–167):

```kotlin
@Composable
private fun WidgetsContent(viewModel: WidgetsViewModel) {
    val entries: List<GridEntry> = viewModel.entries
    val dragState = viewModel.dragState
    val commitIfDragging: () -> Unit = remember(viewModel) {
        { if (viewModel.dragState != null) viewModel.onDragCommit() }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        ...
    ) {
        items(...) { entry ->
            ...
        }
    }
}
```

Replace with this body. Keep the `items {}` block contents EXACTLY as they currently are — the only changes here are: wrap the grid in an outer `Box`, add state declarations, attach `onGloballyPositioned` to the outer Box and the grid, attach `rememberLazyGridState()` to the grid:

```kotlin
@Composable
private fun WidgetsContent(viewModel: WidgetsViewModel) {
    val entries: List<GridEntry> = viewModel.entries
    val dragState = viewModel.dragState
    val commitIfDragging: () -> Unit = remember(viewModel) {
        { if (viewModel.dragState != null) viewModel.onDragCommit() }
    }

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
                            widget = s.widget,
                            isBeingDragged = dragState?.draggedWidget?.id == s.widget.id,
                            onDragStart = { viewModel.onDragStart(s.widget.id) },
                            onHover = { viewModel.onDragHover(entry.key) },
                            onDrop = { viewModel.onDragCommit() },
                            onEnded = commitIfDragging,
                            onTransfer = { viewModel.onTransfer(s.widget.id) },
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
}
```

Key additions:
- 7 new state declarations (`dragBounds`, `pressOffsetWithinCell`, `fingerInWindow`, `draggingWidget`, `boxCoords`, `lazyGridState`, `edgeAutoScroll`).
- Outer `Box` wraps the grid; the box captures its own `LayoutCoordinates` via `onGloballyPositioned`.
- `LazyVerticalGrid` gets the `state = lazyGridState` parameter and an `onGloballyPositioned` that binds grid bounds to `edgeAutoScroll`.

The `items {}` block contents are unchanged — same calls to HeaderCell/EmptyDropZone/WidgetCard/SkeletonCell/FailureCell with the same arguments.

`@Suppress("unused")` is unnecessary — these state values WILL be consumed in Tasks 3 / 4 / 5.

- [ ] **Step 3: Verify build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. The new state is declared but unused; the Kotlin compiler may emit "unused variable" warnings — those are expected and resolve in later tasks.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt
git commit -m "$(cat <<'EOF'
feat: add screen-level drag state and outer Box wrapper to WidgetsContent

Adds dragBounds map, pressOffsetWithinCell / fingerInWindow / draggingWidget
state, the outer Box that will host the floating overlay, lazyGridState
binding, and edge auto-scroll grid-bounds binding. No consumers yet —
later tasks wire the source pointerInput, drop-target registration, and
floating overlay rendering.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Migrate drop targets to onGloballyPositioned + bounds map

**Files:**
- Modify: `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt`

This task replaces `Modifier.dragAndDropTarget(...)` on `HeaderCell`, `EmptyDropZone`, and `WidgetCard` with `Modifier.onGloballyPositioned` registration into `dragBounds` plus a `DisposableEffect` that removes the key on unmount. The `acceptPlainText` predicate and `rememberDropTarget` helper become unused — they're removed in Task 5.

The intermediate state after this commit: `dragAndDropSource` still drives drag visually (the system shadow still appears), but no `onEntered` / `onDrop` callbacks fire because no `dragAndDropTarget` instances remain. Reorder is broken until Task 4 lands. Build still passes.

- [ ] **Step 1: Add new imports at the top of `WidgetsScreen.kt`**

Insert into the existing import block:

```kotlin
import androidx.compose.runtime.DisposableEffect
```

- [ ] **Step 2: Replace `HeaderCell` body**

Find the existing `HeaderCell` (lines 169–190) and replace it with:

```kotlin
@Composable
private fun HeaderCell(
    title: String,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
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
```

The `onHover` / `onDrop` / `onEnded` parameters are kept on the signature for now (still passed by `WidgetsContent`) but unused — they're removed in Task 5 along with the call-site arguments. The `Modifier.dragAndDropTarget(...)` call and the `dropTarget = rememberDropTarget(...)` line are gone.

- [ ] **Step 3: Replace `EmptyDropZone` body**

Find the existing `EmptyDropZone` (lines 311–350) and replace with:

```kotlin
@Composable
private fun EmptyDropZone(
    message: String,
    isDragActive: Boolean,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
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
```

Same shape as before — only the `Modifier.dragAndDropTarget(...)` and `rememberDropTarget` lines are removed. Parameters kept for interim signature compatibility.

- [ ] **Step 4: Replace the modifier chain on `WidgetCard`**

Find `WidgetCard` (lines 192–262). Currently:

```kotlin
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
            .dragAndDropSource(
                drawDragDecoration = { drawLayer(cardLayer) },
                transferData = { _ ->
                    currentOnDragStart()
                    DragAndDropTransferData(
                        ClipData.newPlainText("widgetId", widgetId),
                    )
                },
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
```

Replace with (drop the `.dragAndDropTarget(...)` block; keep everything else for now — the `dragAndDropSource` and `drawWithContent`/`cardLayer` will be replaced in Task 4):

```kotlin
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
            .dragAndDropSource(
                drawDragDecoration = { drawLayer(cardLayer) },
                transferData = { _ ->
                    currentOnDragStart()
                    DragAndDropTransferData(
                        ClipData.newPlainText("widgetId", widgetId),
                    )
                },
            ),
        shape = RoundedCornerShape(12.dp),
    ) {
```

Also remove the `val dropTarget = rememberDropTarget(onHover, onDrop, onEnded)` line near the top of the function body. The function's parameters still receive `onHover`/`onDrop`/`onEnded`, but they're now unused.

- [ ] **Step 5: Wire bounds-map registration into all three drop-target call sites**

In `WidgetsContent`'s `items {}` block, modify the `Modifier.animateItem()` argument passed to each cell to chain in an `onGloballyPositioned` callback. The pattern: every cell that should be a drop target registers its bounds.

Replace the three call sites (Header, Empty, and Loaded WidgetCard inside the `is GridEntry.Cell` branch). Note that the pattern uses a short `Modifier.bindBounds(key)` extension we'll declare in Step 6 to avoid repeating the disposable effect three times.

Until that extension is declared, hold this step. Skip to Step 6 first.

- [ ] **Step 6: Declare a `bindBounds` modifier helper**

Add this at the file level just before `cellSize` (around line 382):

```kotlin
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
```

This is a `@Composable Modifier` extension — Compose-aware modifiers like this are the idiomatic place for both the layout callback and the disposal cleanup.

- [ ] **Step 7: Wire `bindBounds` at the three call sites**

In `WidgetsContent`'s `items {}` block, change each cell's `modifier` argument:

```kotlin
                is GridEntry.Header -> HeaderCell(
                    title = entry.title,
                    onHover = { viewModel.onDragHover(entry.key) },
                    onDrop = { viewModel.onDragCommit() },
                    onEnded = commitIfDragging,
                    modifier = Modifier
                        .animateItem()
                        .bindBounds(entry.key, dragBounds),
                )
                is GridEntry.Empty -> EmptyDropZone(
                    message = entry.message,
                    isDragActive = dragState != null,
                    onHover = { viewModel.onDragHover(entry.key) },
                    onDrop = { viewModel.onDragCommit() },
                    onEnded = commitIfDragging,
                    modifier = Modifier
                        .animateItem()
                        .bindBounds(entry.key, dragBounds),
                )
                is GridEntry.Cell -> when (val s = entry.state) {
                    is WidgetState.Loaded -> WidgetCard(
                        widget = s.widget,
                        isBeingDragged = dragState?.draggedWidget?.id == s.widget.id,
                        onDragStart = { viewModel.onDragStart(s.widget.id) },
                        onHover = { viewModel.onDragHover(entry.key) },
                        onDrop = { viewModel.onDragCommit() },
                        onEnded = commitIfDragging,
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
```

Note: `SkeletonCell` and `FailureCell` do NOT register bounds — they're not drop targets. Only Loaded `WidgetCard`, `HeaderCell`, and `EmptyDropZone` register.

- [ ] **Step 8: Verify build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. There may be unused-parameter warnings on the cell composables (`onHover`, `onDrop`, `onEnded`) — expected, removed in Task 5.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt
git commit -m "$(cat <<'EOF'
feat: replace dragAndDropTarget with bounds-map registration

HeaderCell, EmptyDropZone, and WidgetCard now register their boundsInWindow
into a shared dragBounds map via Modifier.bindBounds (a @Composable Modifier
extension wrapping onGloballyPositioned + DisposableEffect cleanup). The
dragAndDropTarget modifier and the rememberDropTarget helper's call sites
are removed. Native dragAndDropSource is still in place; drag visually
starts but no longer reorders until the source is migrated to pointerInput
in the next commit.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Migrate WidgetCard source to pointerInput + add FloatingWidgetCard + render floating overlay

**Files:**
- Modify: `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt`

The big task. Replaces `dragAndDropSource` on `WidgetCard` with a `Modifier.pointerInput` coroutine. Adds the `FloatingWidgetCard` composable and the floating-overlay rendering inside `WidgetsContent`'s outer Box. Wires edge auto-scroll into the pointerInput coroutine. After this task, drag works end-to-end.

- [ ] **Step 1: Add new imports at the top of `WidgetsScreen.kt`**

Insert into the existing import block:

```kotlin
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
```

- [ ] **Step 2: Rewrite `WidgetCard`**

Find the existing `WidgetCard` (lines 192–262) and replace its entire body with the version below. The new function takes additional state-bag parameters: `dragBounds`, `pressOffsetWithinCell`, `fingerInWindow`, `draggingWidget`, `edgeAutoScroll`.

The signature change is intentional — the migration moves the gesture pipeline from native D&D modifiers to the screen-level state holders introduced in Task 2.

```kotlin
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
```

Note the elvis on the long-press check: `withTimeoutOrNull(...) { ... } ?: true`. When the timeout fires without an early return, `withTimeoutOrNull` returns null → `?: true` → drag begins. When the user lifts before timeout, the inner block returns `false` → outer becomes `false` → `longPressed != true` → return without dragging.

The `cellOrigin` captured at drag start is recomputed inside the loop on every event (`val origin = cellCoords?.boundsInWindow()?.topLeft ?: cellOrigin`) so finger-in-window stays accurate as the slot reorders.

The `try/finally` ensures `fingerInWindow`, `draggingWidget`, and the auto-scroll job are all cleaned up on every exit path — release, cancel, coroutine cancellation.

- [ ] **Step 3: Add `FloatingWidgetCard` composable**

Insert this just after `WidgetCard` (around line 263 in the new file layout). It's a non-interactive renderer used only by the floating overlay:

```kotlin
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
```

The `LaunchedEffect(Unit) { lifted = true }` flips `lifted` from false to true after first composition, which causes the `animateDpAsState` and `animateFloatAsState` to animate from their initial 0/1 values to 4.dp/1.05f. This is the "lift on mount" pattern that gives visible scale/elevation animation during drag (the snapshot-timing limitation of native D&D goes away).

`fillMaxWidth(0.5f)` for SMALL and `fillMaxWidth(1f)` for FULL roughly matches the column geometry of a 2-column grid. The cell's exact pixel size is dictated by the grid's column width, but we don't have direct access to that here — the proportional approximation is close enough for the floating preview, and the user's eye reads it as "this is the dragged cell".

The Icon shows the `Remove`/`Add` icon but is non-interactive (no `IconButton`) — the floating cell isn't a drop target or a transfer source.

- [ ] **Step 4: Update the `is GridEntry.Cell -> WidgetCard(...)` call site to pass the new arguments**

In `WidgetsContent`'s `items {}` block, the `WidgetCard` invocation needs the additional state-bag arguments. Replace:

```kotlin
                    is WidgetState.Loaded -> WidgetCard(
                        widget = s.widget,
                        isBeingDragged = dragState?.draggedWidget?.id == s.widget.id,
                        onDragStart = { viewModel.onDragStart(s.widget.id) },
                        onHover = { viewModel.onDragHover(entry.key) },
                        onDrop = { viewModel.onDragCommit() },
                        onEnded = commitIfDragging,
                        onTransfer = { viewModel.onTransfer(s.widget.id) },
                        modifier = Modifier
                            .animateItem()
                            .bindBounds(s.widget.id, dragBounds),
                    )
```

with:

```kotlin
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
```

The `onHover` / `onDrop` / `onEnded` parameters disappear — `WidgetCard`'s new signature uses `onDragStart` / `onDragHover` / `onDragCommit`. The lambdas now take the widget id as `it` since the new signature's `onDragStart` and `onDragHover` are `(String) -> Unit`.

`commitIfDragging` is no longer used by `WidgetCard`; the `HeaderCell` and `EmptyDropZone` call sites still receive it for now (their signatures still have `onEnded`). Task 5 cleans those up.

- [ ] **Step 5: Render the floating overlay inside `WidgetsContent`'s outer Box**

The outer Box was added in Task 2 with just the LazyVerticalGrid inside. Now add the floating overlay as a sibling (rendered after the grid so it stacks on top):

Inside `WidgetsContent`'s outer `Box { ... }` block, after the closing brace of `LazyVerticalGrid { ... }`, add:

```kotlin
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
```

The `boxOriginInWindow` is the outer Box's top-left in window coords. Subtracting it from the absolute floating position converts to local coords — what `Modifier.offset { IntOffset(...) }` expects.

`zIndex(1f)` ensures the floating cell paints on top of the grid even though it's a sibling rather than nested.

- [ ] **Step 6: Verify build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. There may be unused-parameter warnings for `onHover`/`onDrop`/`onEnded` on `HeaderCell` and `EmptyDropZone`, and unused `commitIfDragging` — all expected and removed in Task 5.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt
git commit -m "$(cat <<'EOF'
feat: migrate WidgetCard source to pointerInput; add floating overlay

- WidgetCard's gesture pipeline replaced by Modifier.pointerInput with
  custom 200ms long-press detection, finger-following floating overlay
  state, and bounds-map hit-testing on each pointer event.
- Source slot uses Modifier.alpha(0f) when isBeingDragged; the cardLayer
  graphics-layer snapshot machinery is gone (will be removed in cleanup).
- New FloatingWidgetCard composable mounts when draggingWidget is non-null,
  with LaunchedEffect-driven scale/elevation lift that actually animates
  visibly (no more snapshot-time freeze).
- WidgetsContent's outer Box now hosts the floating overlay as a sibling
  to the grid, positioned via window-coord math.
- Edge auto-scroll wired into the pointerInput coroutine via update(finger.y)
  per pointer event and stop() in the finally block.

Drag works end-to-end after this commit. Native dragAndDropSource and the
old onHover/onDrop/onEnded surface are now dead code; cleanup follows.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Cleanup dead code and unused parameters

**Files:**
- Modify: `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt`

Removes everything that's now dead: `acceptPlainText`, `rememberDropTarget`, the cardLayer/drawWithContent stack on `WidgetCard`, `commitIfDragging`, the `onHover`/`onDrop`/`onEnded` parameters on `HeaderCell` and `EmptyDropZone`, and unused imports.

- [ ] **Step 1: Remove `acceptPlainText` function**

Find and delete lines containing:

```kotlin
private fun acceptPlainText(event: DragAndDropEvent): Boolean =
    event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)
```

- [ ] **Step 2: Remove `rememberDropTarget` function**

Find and delete the entire `rememberDropTarget` composable block (was lines 61–80 originally):

```kotlin
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
```

- [ ] **Step 3: Remove the `dragAndDropSource` block from `WidgetCard`**

In the `WidgetCard` `Card(...)` modifier chain, remove the `.dragAndDropSource(...)` block. Currently:

```kotlin
            .onGloballyPositioned { coords -> cellCoords = coords }
            .pointerInput(widgetId) { ... }
            .dragAndDropSource(
                drawDragDecoration = { drawLayer(cardLayer) },
                transferData = { _ ->
                    currentOnDragStart()
                    DragAndDropTransferData(
                        ClipData.newPlainText("widgetId", widgetId),
                    )
                },
            ),
```

Becomes:

```kotlin
            .onGloballyPositioned { coords -> cellCoords = coords }
            .pointerInput(widgetId) { ... },
```

(Drop the `.dragAndDropSource(...)` clause entirely; the trailing comma after `pointerInput(...)` becomes `,` to terminate the modifier chain.)

- [ ] **Step 4: Remove the `cardLayer` graphics-layer machinery from `WidgetCard`**

In the `WidgetCard` body, remove the `cardLayer` declaration:

```kotlin
    val cardLayer = rememberGraphicsLayer()
```

And remove the `.drawWithContent { ... }` modifier from the chain:

```kotlin
            .drawWithContent {
                cardLayer.record { this@drawWithContent.drawContent() }
                if (!isBeingDragged) drawLayer(cardLayer)
            }
```

(The `Modifier.alpha(if (isBeingDragged) 0f else 1f)` already handles source-invisible.)

Also remove the `currentOnDragStart` declaration if it's no longer referenced (the dragAndDropSource block was its only consumer; `pointerInput` uses `currentOnDragStart` too — keep this declaration, it's still used).

Wait — re-check. The `currentOnDragStart by rememberUpdatedState(onDragStart)` line is used by the pointerInput coroutine. KEEP it.

- [ ] **Step 5: Remove `onHover`/`onDrop`/`onEnded` parameters from `HeaderCell`**

Replace the function signature:

```kotlin
@Composable
private fun HeaderCell(
    title: String,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
)
```

With:

```kotlin
@Composable
private fun HeaderCell(
    title: String,
    modifier: Modifier = Modifier,
)
```

And update the `is GridEntry.Header -> HeaderCell(...)` call site in `WidgetsContent` to drop the now-removed arguments:

```kotlin
                is GridEntry.Header -> HeaderCell(
                    title = entry.title,
                    modifier = Modifier
                        .animateItem()
                        .bindBounds(entry.key, dragBounds),
                )
```

- [ ] **Step 6: Remove `onHover`/`onDrop`/`onEnded` parameters from `EmptyDropZone`**

Replace the signature:

```kotlin
@Composable
private fun EmptyDropZone(
    message: String,
    isDragActive: Boolean,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
)
```

With:

```kotlin
@Composable
private fun EmptyDropZone(
    message: String,
    isDragActive: Boolean,
    modifier: Modifier = Modifier,
)
```

And update the call site:

```kotlin
                is GridEntry.Empty -> EmptyDropZone(
                    message = entry.message,
                    isDragActive = dragState != null,
                    modifier = Modifier
                        .animateItem()
                        .bindBounds(entry.key, dragBounds),
                )
```

- [ ] **Step 7: Remove `commitIfDragging` from `WidgetsContent`**

Find and delete:

```kotlin
    val commitIfDragging: () -> Unit = remember(viewModel) {
        { if (viewModel.dragState != null) viewModel.onDragCommit() }
    }
```

There are no remaining call sites — all three previous consumers (HeaderCell, EmptyDropZone, WidgetCard) lost their `onEnded` parameters in Steps 5/6 and Task 4 respectively.

- [ ] **Step 8: Remove unused imports**

Delete these import lines from the top of the file:

```kotlin
import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
```

After removal, run a sanity grep to confirm none of these symbols remain in the file:

```bash
grep -nE "ClipData|ClipDescription|dragAndDropSource|dragAndDropTarget|DragAndDropEvent|DragAndDropTarget|DragAndDropTransferData|mimeTypes|drawWithContent|drawLayer|rememberGraphicsLayer" app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt
```

Expected: no matches (or only matches in comments — there shouldn't be any).

- [ ] **Step 9: Verify build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. Full APK build, not just compile, since this is the final code state.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt
git commit -m "$(cat <<'EOF'
refactor: remove dead native-D&D surface from WidgetsScreen

- acceptPlainText, rememberDropTarget — gone (no DragAndDropTarget instances)
- dragAndDropSource block on WidgetCard — replaced by pointerInput in prior commit
- cardLayer / drawWithContent stack — replaced by Modifier.alpha(0f) on the source
- commitIfDragging lambda hoisted in WidgetsContent — no longer needed; pointerInput
  coroutine handles release directly
- onHover / onDrop / onEnded parameters on HeaderCell and EmptyDropZone — cells
  are pure renderers now; bounds registration happens via Modifier.bindBounds
- 11 unused imports (ClipData, dragAndDrop*, drawWithContent, etc.)

Build: assembleDebug succeeds end-to-end.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Update CLAUDE.md for the pointerInput architecture

**Files:**
- Modify: `CLAUDE.md`

Six sections need updates: hard-constraint section, Architecture, Drop semantics, Drop targets, Drag visualization, Long-press detection, Composable code organization, Files.

- [ ] **Step 1: Rewrite the "Hard constraint" section body**

Find:

```markdown
## Hard constraint: native Compose drag-and-drop only

Use `Modifier.dragAndDropSource` / `Modifier.dragAndDropTarget` (`androidx.compose.foundation.draganddrop.*`).

**Do NOT introduce `Calvin-LL/Reorderable` or any other third-party reordering library.** A confirmed library bug ([Calvin-LL/Reorderable#93](https://github.com/Calvin-LL/Reorderable/issues/93)) causes flicker on mixed-span items in `LazyVerticalGrid`. Native Compose D&D avoids it because target detection is independent of item geometry.
```

Replace with:

```markdown
## Hard constraint: no third-party reorder libraries

**Do NOT introduce `Calvin-LL/Reorderable` or any other third-party reordering library.** A confirmed library bug ([Calvin-LL/Reorderable#93](https://github.com/Calvin-LL/Reorderable/issues/93)) causes flicker on mixed-span items in `LazyVerticalGrid`.

The drag pipeline is built directly on Compose Foundation primitives — `Modifier.pointerInput`, `awaitEachGesture`, `awaitFirstDown`, `awaitPointerEvent`, `withTimeoutOrNull`, `Modifier.onGloballyPositioned`, and `LazyGridState.scrollBy`. Native `Modifier.dragAndDropSource` / `Modifier.dragAndDropTarget` were used previously but were replaced because Compose Foundation 1.11 does not yet expose a public custom long-press detector (`detectDragStart` is `internal` with a `TODO: Expose this as public argument`), and because animations / touch-center fidelity / continuous source tracking are easier to control directly. Either path is acceptable as long as we own the geometry math.
```

- [ ] **Step 2: Replace the "Architecture" section's drag-related bullets**

Find the "Architecture" section. Locate these existing bullets (the exact wording):

> - **Single source of truth for `isInYourWidgets`.** Derived from list position relative to the Available header — never stored independently after initial load. `reconcileIsYoursForDraggedWidget` runs after each drag move and uses `current.toggleIsInYourWidgets(...)` to preserve the subtype.
> - **Deferred emissions during drag.** A `pendingEntries: List<WidgetState>?` field stores any flow emission that arrives mid-drag; `onDragCommit` / `onDragCancel` replay it via `flushPendingEntriesIfAny()` so a remote update cannot stomp on an active drag.

Insert these two new bullets immediately after them:

```markdown
- **Drag pipeline is `pointerInput`-based.** A per-`WidgetCard` `Modifier.pointerInput` runs a custom 200 ms long-press detector (`withTimeoutOrNull(LONG_PRESS_TIMEOUT_MS)`), captures the press offset within the cell, then drives the drag through release. Drop targets register `boundsInWindow()` into a screen-level `dragBounds: SnapshotStateMap<String, Rect>` via `Modifier.bindBounds(key, dragBounds)`. The dragging cell hit-tests against this map on every pointer event and calls `viewModel.onDragHover(targetKey)`. The source slot stays at its `_entries` index with `Modifier.alpha(0f)`; a sibling `Box` overlay renders a `FloatingWidgetCard` that follows the finger using window-coord math (`floatingPos = finger − pressOffsetWithinCell − boxOrigin`).
- **Edge auto-scroll.** An `EdgeAutoScroll` helper drives `LazyGridState.scrollBy(velocity)` while the dragging finger sits within an 80 dp band at the top or bottom of the grid. Velocity ramps linearly from 0 at the band's outer edge to ~12 px/frame (~720 px/sec at 60 fps) at the screen edge. Stops on release / coroutine cancel.
```

- [ ] **Step 3: Rewrite the "Drop targets" section**

Find:

```markdown
## Drop targets

| Composable | Drop key | Resolution |
|---|---|---|
| `WidgetCard` | `state.widget.id` | Direction-aware insert at target's `entries` index |
| `HeaderCell` (Yours) | `YOURS_HEADER_KEY` | Insert just after the Yours header (start of Yours) |
| `HeaderCell` (Other) | `AVAILABLE_HEADER_KEY` | Insert at the available header's own index — direction-aware then takes over (drag-down lands end-of-Yours, drag-up lands start-of-Other) |
| `EmptyDropZone` | `YOURS_EMPTY_KEY` / `AVAILABLE_EMPTY_KEY` | Insert at the placeholder's own index (the placeholder is then stripped by reconcile) |

All four target types share the same drop-handler shape via the `rememberDropTarget(onHover, onDrop, onEnded): DragAndDropTarget` helper at the top of `WidgetsScreen.kt`. `shouldStartDragAndDrop` is `::acceptPlainText` everywhere — the predicate is hoisted to a private file-level function.
```

Replace with:

```markdown
## Drop targets

| Composable | Bounds key | Resolution |
|---|---|---|
| `WidgetCard` | `widget.id` | Direction-aware insert at target's `entries` index |
| `HeaderCell` (Yours) | `YOURS_HEADER_KEY` | Insert just after the Yours header (start of Yours) |
| `HeaderCell` (Other) | `AVAILABLE_HEADER_KEY` | Insert at the available header's own index — direction-aware then takes over (drag-down lands end-of-Yours, drag-up lands start-of-Other) |
| `EmptyDropZone` | `YOURS_EMPTY_KEY` / `AVAILABLE_EMPTY_KEY` | Insert at the placeholder's own index (the placeholder is then stripped by reconcile) |

Each target attaches `Modifier.bindBounds(key, dragBounds)` — a `@Composable Modifier` extension that combines `onGloballyPositioned` (writes `coords.boundsInWindow()` into the `dragBounds` map) with `DisposableEffect` cleanup (`onDispose { dragBounds.remove(key) }`). The dragging cell's `pointerInput` calls `hitTest(finger, dragBounds, draggedKey)` on every pointer event and invokes `viewModel.onDragHover(matchedKey)` when a non-self target is under the finger. `SkeletonCell` and `FailureCell` deliberately do not register — they're not drop participants.
```

- [ ] **Step 4: Rewrite the "Drag visualization" section**

Find the "Drag visualization" section (likely starting with "Currently uses the **system drag shadow** approach via a graphics-layer snapshot:"). Replace its entire body with:

```markdown
## Drag visualization

Inline floating overlay — no system drag shadow, no `dragAndDropSource`/`drawDragDecoration`.

- **Source slot invisible.** While `isBeingDragged`, `WidgetCard` applies `Modifier.alpha(0f)`. The slot still occupies layout space so adjacent cells reflow correctly via `Modifier.animateItem()`.
- **Floating overlay.** Inside `WidgetsContent`'s outer `Box`, a sibling renders `FloatingWidgetCard(widget = draggingWidget.value)` when `fingerInWindow.value != null`. Its position is computed in window coords: `floatingTopLeft = fingerInWindow − pressOffsetWithinCell − boxOriginInWindow`. The result is converted to local coords by subtracting the outer Box's `positionInWindow()` and applied via `Modifier.offset { IntOffset(x, y) }`. `Modifier.zIndex(1f)` ensures it paints above the grid.
- **Lift animation.** `FloatingWidgetCard` uses `LaunchedEffect(Unit) { lifted = true }` to flip `lifted` from false to true on first composition. `animateDpAsState` and `animateFloatAsState` then animate elevation from 0.dp to 4.dp and scale from 1f to 1.05f over the standard tween. Because the floating cell mounts fresh when `draggingWidget` becomes non-null, the lift is actually visible during drag — no snapshot-time freeze.

The `WidgetCard` itself also runs `animateDpAsState`/`animateFloatAsState` on its source slot, but those are mostly cosmetic now that the slot is alpha-0 during drag. They animate the slot back to 1f/0.dp on release (a brief flicker as the cell reappears at its final position).

### What's no longer needed

- `cardLayer = rememberGraphicsLayer()`, `Modifier.drawWithContent { record + skip }`, `dragAndDropSource(drawDragDecoration = { drawLayer(cardLayer) })` — all gone. The `drawDragDecoration` lambda was the only mechanism the system drag shadow had to render the source's content; with the floating overlay drawing fresh, no snapshot is needed.
```

- [ ] **Step 5: Rewrite the "Long-press detection" section**

Find the "Long-press detection" section. Replace its body with:

```markdown
## Long-press detection

Custom 200 ms threshold via `withTimeoutOrNull(LONG_PRESS_TIMEOUT_MS)` inside the `WidgetCard` `pointerInput` coroutine:

```kotlin
val longPressed = withTimeoutOrNull(LONG_PRESS_TIMEOUT_MS) {
    while (true) {
        val ev = awaitPointerEvent()
        val change = ev.changes.firstOrNull { it.id == down.id }
        if (change != null && !change.pressed) return@withTimeoutOrNull false
    }
    @Suppress("UNREACHABLE_CODE") true
} ?: true
```

`withTimeoutOrNull` returns `null` when the timeout fires — that case `?: true` succeeds and the drag begins. If the user lifts before the timeout, the inner block returns `false`, the outer expression is `false`, and the drag never starts.

`LONG_PRESS_TIMEOUT_MS = 200L` is at file level. Tune freely.

This restores the snappier feel that Compose Foundation 1.11's `dragAndDropSource` does not offer — its `detectDragStart` is `internal` (with a `TODO: Expose this as public argument`), so the only timing was the platform default `viewConfiguration.longPressTimeoutMillis` (≈ 500 ms).
```

- [ ] **Step 6: Rewrite the "Composable code organization" section**

Find the section. Replace its body with:

```markdown
## Composable code organization (`WidgetsScreen.kt`)

- `@file:OptIn(ExperimentalFoundationApi::class)` at the top — promotes the per-function annotations to file-level.
- File-level constants: `LONG_PRESS_TIMEOUT_MS`.
- File-level helpers: `hitTest(finger, bounds, draggedKey)`, `cellSize(state)`, `debugLabel(widget)`, `bindBounds(key, dragBounds)` (a `@Composable Modifier` extension), `rememberEdgeAutoScroll(lazyGridState)`, plus the `EdgeAutoScroll` private class.
- `WidgetsContent` owns: `dragBounds`, `pressOffsetWithinCell`, `fingerInWindow`, `draggingWidget`, `boxCoords`, `lazyGridState`, `edgeAutoScroll`. It wraps the `LazyVerticalGrid` in an outer `Box` and renders the floating overlay as a sibling.
- `HeaderCell`, `EmptyDropZone` are pure renderers — bounds registration happens via the `bindBounds` modifier on the call site, not inside the cell.
- `WidgetCard` owns the gesture pipeline: a single `Modifier.pointerInput(widget.id)` runs the long-press detector, captures press state, drives drag through release, and stops auto-scroll in its `finally` block. Source-slot invisibility is `Modifier.alpha(if (isBeingDragged) 0f else 1f)`.
- `FloatingWidgetCard` is a non-interactive renderer used only by the floating overlay.
- `SkeletonCell`, `FailureCell` are render-only (no drag, no bounds registration).
- `ErrorScreen` renders the `UiState.Error` branch.
```

- [ ] **Step 7: Rewrite the "Files" section's `WidgetsScreen.kt` line**

Find the "Files" section. Locate the `WidgetsScreen.kt` entry (currently mentions `LONG_PRESS_TIMEOUT_MS` and `detectShortLongPress` may have been removed in earlier docs cleanup — check the actual current text). Replace it with:

```markdown
- `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` — `WidgetsScreen` / `WidgetsContent` / `HeaderCell` / `WidgetCard` / `FloatingWidgetCard` / `SkeletonCell` / `FailureCell` / `EmptyDropZone` / `ErrorScreen` / `bindBounds` / `cellSize` / `debugLabel` / `hitTest` / `rememberEdgeAutoScroll` / `EdgeAutoScroll`.
```

(Removes the obsolete entries — no more `rememberDropTarget`, `acceptPlainText`, `detectShortLongPress`. Adds `FloatingWidgetCard`, `bindBounds`, `hitTest`, `rememberEdgeAutoScroll`, `EdgeAutoScroll`.)

- [ ] **Step 8: Sweep for stale references**

Run a grep for any leftover mentions of the removed surface:

```bash
grep -nE "dragAndDropSource|dragAndDropTarget|rememberDropTarget|acceptPlainText|cardLayer|drawWithContent|drawDragDecoration|drawLayer|DragAndDropTransferData|ClipData|detectShortLongPress" CLAUDE.md
```

Expected: no matches. Any matches indicate sections that need follow-up edits.

- [ ] **Step 9: Commit**

```bash
git add CLAUDE.md
git commit -m "$(cat <<'EOF'
docs: update CLAUDE.md for pointerInput drag pipeline

- Hard-constraint section reframed: no third-party reorder libraries; the
  current implementation uses pointerInput because the native API does not
  yet expose a public custom long-press detector
- Architecture section adds bullets for pointerInput pipeline and edge
  auto-scroll
- Drop targets section rewritten to describe Modifier.bindBounds + dragBounds
  map + hitTest, replacing the old rememberDropTarget pattern
- Drag visualization section replaced — inline floating overlay, source-alpha,
  LaunchedEffect-driven lift animation
- Long-press detection section replaced — 200ms via withTimeoutOrNull
- Composable code organization and Files sections updated to list the new
  surface (FloatingWidgetCard, bindBounds, hitTest, rememberEdgeAutoScroll,
  EdgeAutoScroll) and remove the obsolete entries
- Sweep clean: no leftover references to dragAndDropSource, dragAndDropTarget,
  rememberDropTarget, acceptPlainText, cardLayer, drawWithContent, etc.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: On-device verification and push

**Files:** none (verification + push only).

- [ ] **Step 1: Build the debug APK**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Install on a connected device**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`.

- [ ] **Step 3: Verify long-press timing**

Long-press a Loaded cell for ~200 ms — drag begins (the cell becomes invisible at its slot, the floating overlay appears under the finger). Pressing for less than ~150 ms and releasing should not start a drag. The threshold should feel snappier than before (used to be platform default ~500 ms).

- [ ] **Step 4: Verify touch-center fidelity**

Long-press a cell at a corner (not the center) and drag. The floating overlay should stay anchored to the same point under the finger — i.e., if you press on the top-right corner, the overlay's top-right corner stays under your finger. No mid-drag jump from press point to centered.

- [ ] **Step 5: Verify lift animation visibility**

During drag, the floating cell should visibly animate from 1.0× to 1.05× scale and from 0.dp to 4.dp elevation in the first ~150–200 ms after the long-press completes. Reverses on release (the slot's source cell briefly animates back to its full state).

- [ ] **Step 6: Verify all four drag scenarios**

| Scenario | Setup | Action | Expected |
|---|---|---|---|
| Yours → Yours reorder | Two cells in Yours | Long-press one, drag, drop on another | Cells swap positions |
| Other → Yours transfer | A cell in Other | Long-press, drag into Yours | Commits at drop position; `−` icon shown |
| Yours → Other (snap) | A cell in Yours | Long-press, drag into Other | Snaps to top of Other; `+` icon shown |
| Other → Other (revert) | Two cells in Other | Long-press one, drag, drop on another | Reverts to original index |

- [ ] **Step 7: Verify type preservation through `+`/`−`**

- Press `−` on `Monizze · m1` — appears at top of Other, still labeled `Monizze · m1`.
- Press `+` on `PFM · pfm1` (full-span) — appears at top of Yours, still full-span, still labeled `PFM · pfm1`.

- [ ] **Step 8: Verify edge auto-scroll**

Temporarily increase `INITIAL_WIDGETS` in `FakeWidgetsUseCase.kt` to 20+ entries (so the grid scrolls). Rebuild and reinstall. Long-press a cell and drag toward the bottom edge of the visible grid — within the bottom ~80 dp the grid should auto-scroll downward. Faster scroll closer to the absolute edge. Release — scrolling stops immediately. Restore `INITIAL_WIDGETS` to the original 6.

- [ ] **Step 9: Verify Skeleton and Failure cells are not draggable**

Bump `ELIGIBILITY_DELAY_MS` to `5000L` in `WidgetsUseCase.kt`. Rebuild, reinstall. While skeletons are showing, long-press one — nothing happens (no source-invisible, no overlay). Restore `ELIGIBILITY_DELAY_MS` to `2000L`.

- [ ] **Step 10: Verify error screen still works**

Set `FAIL_ELIGIBILITY = true` in `WidgetsUseCase.kt`. Rebuild, reinstall. After the eligibility delay, the error screen (warning icon + "Couldn't load widgets" + "Eligibility check failed") renders. Restore `FAIL_ELIGIBILITY = false`.

- [ ] **Step 11: Verify predictive-back is not triggered during edge drag**

Drag a cell near the screen's left or right edge — Android's predictive-back animation should not fire (`Modifier.systemGestureExclusion()` is still applied to the grid).

- [ ] **Step 12: Push commits to origin**

```bash
git push origin main
```

Expected: a series of commits push successfully (Tasks 1–6 produced 6 commits on top of `b360dda`; the push includes those plus this verification work if any flag-toggle commits slipped in).

If any `WidgetsUseCase.kt` or other file has uncommitted changes from the verification steps (e.g., temporary flag flips), confirm they're reverted before pushing — `git status` should be clean.

- [ ] **Step 13: Final report**

Report any test scenario that didn't match expected behavior, plus the final `git log --oneline -8` showing the migration commits in order.

---

## Self-review

After writing this plan I checked it against the spec at `docs/superpowers/specs/2026-05-05-pointerinput-drag-design.md`:

1. **Spec coverage.** Every section maps to tasks: §1 gesture state machine → Task 4; §2 bounds map → Tasks 1 (hitTest helper), 2 (state declaration), 3 (registration); §3 floating overlay + source-invisible → Task 4; §4 edge auto-scroll → Task 1 (helper class) + Task 4 (wiring); §5 ViewModel surface unchanged → no task (verified by absence); §6 removed surface → Task 5; §7 stays-the-same → preserved verbatim across Tasks 4 / 5; §8 out of scope → respected (no semantics, no horizontal auto-scroll, no multi-finger).
2. **Type/name consistency.** `LONG_PRESS_TIMEOUT_MS`, `dragBounds`, `pressOffsetWithinCell`, `fingerInWindow`, `draggingWidget`, `boxCoords`, `lazyGridState`, `edgeAutoScroll`, `bindBounds`, `hitTest`, `cellCoords`, `EdgeAutoScroll`, `rememberEdgeAutoScroll`, `FloatingWidgetCard`, `currentOnDragStart`, `currentOnDragHover`, `currentOnDragCommit` are used consistently across tasks. The `onDragStart: (String) -> Unit` signature change on `WidgetCard` (parameter accepts `widgetId` rather than being closed over) is applied at every call site.
3. **Build sequencing.** Tasks 1–4 each end with `compileDebugKotlin`. Task 5 ends with `assembleDebug` (full APK). Task 6 has no build (docs-only). Task 7 builds the APK and runs on-device verification before pushing.
4. **No placeholders.** No "TBD", no "similar to Task N", no "implement appropriate error handling", no missing code blocks. Every step that says "edit this" has the exact before/after code.
