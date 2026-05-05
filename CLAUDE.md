# DragDrop — Widget Dashboard POC

Proof-of-concept Android app: a draggable widget dashboard with two sections ("Your widgets" / "Other widgets") supporting reorder, cross-section transfer (drag or button), and mixed-span items inside a single `LazyVerticalGrid`.

## Hard constraint: no third-party reorder libraries

**Do NOT introduce `Calvin-LL/Reorderable` or any other third-party reordering library.** A confirmed library bug ([Calvin-LL/Reorderable#93](https://github.com/Calvin-LL/Reorderable/issues/93)) causes flicker on mixed-span items in `LazyVerticalGrid`.

The drag pipeline is built directly on Compose Foundation primitives — `Modifier.pointerInput`, `awaitEachGesture`, `awaitFirstDown`, `awaitPointerEvent`, `withTimeoutOrNull`, `Modifier.onGloballyPositioned`, and `LazyGridState.scrollBy`. Native `Modifier.dragAndDropSource` / `Modifier.dragAndDropTarget` were used previously but were replaced because Compose Foundation 1.11 does not yet expose a public custom long-press detector (`detectDragStart` is `internal` with a `TODO: Expose this as public argument`), and because animations / touch-center fidelity / continuous source tracking are easier to control directly. Either path is acceptable as long as we own the geometry math.

## Stack

- AGP 9.0.0-beta03, Gradle 9.1.0 (built-in Kotlin support — do **not** apply `org.jetbrains.kotlin.android` plugin, it conflicts with the AGP-provided `kotlin` extension; only `org.jetbrains.kotlin.plugin.compose` is applied).
- Compose BOM 2026.04.01 (Compose Foundation/UI 1.11.0, Material 3 1.4.0), lifecycle 2.8.7, activity-compose 1.9.3.
- Arrow Core 1.2.4 (`Either<Throwable, Flow<List<WidgetState>>>` for eligibility-aware data fetching).
- minSdk 26, targetSdk/compileSdk 36, JVM target 11.

## Architecture

- **MVVM with strict separation.** `WidgetsViewModel` owns all mutation logic. `WidgetsScreen.kt` composables are pure renderers — they observe state and forward user input via callbacks.
- **`WidgetsUseCase` is constructor-injected** into the ViewModel via Compose's `viewModelFactory` in `MainActivity`. The interface returns `Either<Throwable, Flow<List<WidgetState>>>` — `Left` is a terminal eligibility-failure state (no flow exists to subscribe to); `Right` is the live widget stream. `FakeWidgetsUseCase` flips a `FAIL_ELIGIBILITY` constant to test the error path.
- **Sealed `GenericWidget` hierarchy carries the typed widget data.** Concrete data classes (`InvestmentEntryPoint`, `Pfm`, `Tile.{Monizze, Cashback, Pluxee}`) each carry `id` / `size` / `isInYourWidgets` and a polymorphic `toggleIsInYourWidgets(b)` that returns the same concrete subtype — reorder/transfer logic preserves widget type without `when`-branching. Per-type data fields (presentation payloads, balances, etc.) live on each impl.
- **`WidgetState` is the cell's lifecycle state.** Three variants: `Skeleton(key, size)` and `Failure(key, size)` (placeholders with no widget data), and `Loaded(widget: GenericWidget)` — a thin wrapper marking "this slot has finished loading". The `widget` field is the only payload `Loaded` carries.
- **`SnapshotStateList<GridEntry>` as source of truth.** Backed by a private `_entries`; the public view is `List<GridEntry>` (read-only). Compose still observes mutations because the underlying list is `SnapshotStateList`.
- **Sealed `GridEntry` interface.** Three variants: `Header(key, title)`, `Cell(state: WidgetState)`, `Empty(key, message)`. `GridEntry.Cell.key` resolves to `state.widget.id` for `Loaded` and to `state.key` for `Skeleton`/`Failure`.
- **Three-state UI.** `UiState.Loading` (6 skeletons in `_entries`, no headers, no drag), `UiState.Error(cause)` (empty `_entries`, `ErrorScreen` rendered instead of the grid), `UiState.Loaded` (full grid with headers, section partition, drag/drop). `UiState.Loading` and `UiState.Loaded` both render through the same `WidgetsContent` — only the `_entries` contents change.
- **Single source of truth for `isInYourWidgets`.** Derived from list position relative to the Available header — never stored independently after initial load. `reconcileIsYoursForDraggedWidget` runs after each drag move and uses `current.toggleIsInYourWidgets(...)` to preserve the subtype.
- **Deferred emissions during drag.** A `pendingEntries: List<WidgetState>?` field stores any flow emission that arrives mid-drag; `onDragCommit` / `onDragCancel` replay it via `flushPendingEntriesIfAny()` so a remote update cannot stomp on an active drag.
- **Drag pipeline is `pointerInput`-based.** A per-`WidgetCard` `Modifier.pointerInput` runs a custom 200 ms long-press detector (`withTimeoutOrNull(LONG_PRESS_TIMEOUT_MS)`), captures the press offset within the cell, then drives the drag through release. Drop targets register `boundsInWindow()` into a screen-level `dragBounds: SnapshotStateMap<String, Rect>` via `Modifier.bindBounds(key, dragBounds)`. The dragging cell hit-tests against this map on every pointer event and calls `viewModel.onDragHover(targetKey)`. The source slot stays at its `_entries` index with `Modifier.alpha(0f)`; a sibling `Box` overlay renders a `FloatingWidgetCard` that follows the finger using window-coord math (`floatingPos = finger − pressOffsetWithinCell − boxOrigin`). **Once the long-press succeeds and the drag loop starts, every pointer change is `change.consume()`'d** — without this, `LazyVerticalGrid`'s built-in scroll gesture detector also processes the drag motion and scrolls the grid under the finger. Pre-long-press events are deliberately *not* consumed so a normal short swipe still scrolls the grid.
- **Edge auto-scroll.** An `EdgeAutoScroll` helper drives `LazyGridState.scrollBy(velocity)` only while the dragging finger is **past** the grid's vertical edges (outside `gridTopInWindow` / `gridBottomInWindow`). Velocity ramps linearly from 0 at the edge itself to ~6 px/frame (~360 px/sec at 60 fps) once the finger is 24 dp beyond the edge. Stops on release / coroutine cancel. Triggering only past the edge means accidental scroll during mid-screen drags is impossible — the user has to deliberately pull off the grid.
- **Dynamic empty placeholders.** `reconcileEmptyPlaceholders()` strips all `GridEntry.Empty` entries and re-adds them based on section emptiness. Tail-first insertion order keeps the available header's index stable for the second insert without a re-lookup.
- **Constants for section anchors.** `YOURS_HEADER_KEY` / `AVAILABLE_HEADER_KEY` / `YOURS_EMPTY_KEY` / `AVAILABLE_EMPTY_KEY`.

## Drop semantics

### Commit-on-release everywhere

Releasing outside any drop target keeps the widget at its current visual position — it does not snap back to origin. Deviates from the original POC spec ("release outside target = cancel") to eliminate dead-zone gaps between section headers and the first item below them. See `docs/superpowers/specs/2026-04-30-commit-on-release-drag-design.md`.

`WidgetsViewModel.onDragCancel` implements the Other→Other revert branch invoked from `onDragCommit` (revert to `originalIndex`). It also stands as public surface area for a possible future explicit-cancel gesture.

### Section-aware reorder rules

`onDragCommit` branches based on origin and final position:

| Origin (`originalIsInYourWidgets`) | Final position | Result |
|---|---|---|
| Yours | Yours | Reorder in place |
| Other | Yours | Transfer to that specific Yours position |
| Yours | Other | **Snap to top of Other** (whole-section drop zone — exact drop position in Other is ignored) |
| Other | Other | Revert to `originalIndex` (no in-section reorder) |

The `+`/`−` button (`onTransfer`) is the only way to programmatically send a widget *to* Other from Yours; drag is the same fallback via the snap-to-top branch.

### Direction-aware insert during hover

`onDragHover` resolves the target to its **original index in `entries`** (not workingList), then does a single `removeAt(currentIdx)` + `add(targetIdx)`. The math gives standard reorder UX out of the box:

- Drag down (`currentIdx < targetIdx`): widget lands *after* target; target shifts up.
- Drag up (`currentIdx > targetIdx`): widget takes target's slot; target shifts down.

`YOURS_HEADER_KEY` resolves to `yoursHeaderIdx + 1` (start of Yours — there's nothing meaningful "before" the top of the grid). All other keys (including `AVAILABLE_HEADER_KEY`, empty placeholders, item keys) resolve to their own index and let the direction-aware rule handle the rest. See `docs/superpowers/specs/2026-04-30-direction-aware-drag-and-header-targets-design.md`.

## Drop targets

| Composable | Bounds key | Resolution |
|---|---|---|
| `WidgetCard` | `widget.id` | Direction-aware insert at target's `entries` index |
| `HeaderCell` (Yours) | `YOURS_HEADER_KEY` | Insert just after the Yours header (start of Yours) |
| `HeaderCell` (Other) | `AVAILABLE_HEADER_KEY` | Insert at the available header's own index — direction-aware then takes over (drag-down lands end-of-Yours, drag-up lands start-of-Other) |
| `EmptyDropZone` | `YOURS_EMPTY_KEY` / `AVAILABLE_EMPTY_KEY` | Insert at the placeholder's own index (the placeholder is then stripped by reconcile) |

Each target attaches `Modifier.bindBounds(key, dragBounds)` — a `@Composable` `Modifier` extension that combines `onGloballyPositioned` (writes `coords.boundsInWindow()` into the `dragBounds` map) with `DisposableEffect` cleanup (`onDispose { dragBounds.remove(key) }`). The dragging cell's `pointerInput` calls `hitTest(finger, dragBounds, draggedKey)` on every pointer event and invokes `viewModel.onDragHover(matchedKey)` when a non-self target is under the finger. `SkeletonCell` and `FailureCell` deliberately do not register — they're not drop participants.

## Drag visualization

Inline floating overlay — no system drag shadow, no `dragAndDropSource` / `drawDragDecoration`.

- **Source slot invisible.** While `isBeingDragged`, `WidgetCard` applies `Modifier.alpha(0f)`. The slot still occupies layout space so adjacent cells reflow correctly via `Modifier.animateItem()`.
- **Floating overlay.** Inside `WidgetsContent`'s outer `Box`, a sibling renders `FloatingWidgetCard(widget = draggingWidget.value)` when `fingerInWindow.value != null`. Its position is computed in window coords: `floatingTopLeft = fingerInWindow − pressOffsetWithinCell − boxOriginInWindow`. The result is converted to local coords by subtracting the outer Box's `positionInWindow()` and applied via `Modifier.offset { IntOffset(x, y) }`. `Modifier.zIndex(1f)` ensures it paints above the grid.
- **Lift animation.** `FloatingWidgetCard` uses `LaunchedEffect(Unit) { lifted = true }` to flip `lifted` from false to true on first composition. `animateDpAsState` and `animateFloatAsState` then animate elevation from 0.dp to 4.dp and scale from 1f to 1.05f over the standard tween. Because the floating cell mounts fresh when `draggingWidget` becomes non-null, the lift is actually visible during drag — no snapshot-time freeze.

The `WidgetCard` itself also runs `animateDpAsState` / `animateFloatAsState` on its source slot, but those are mostly cosmetic now that the slot is alpha-0 during drag. They animate the slot back to 1f / 0.dp on release (a brief flicker as the cell reappears at its final position).

### What's no longer needed

- `cardLayer = rememberGraphicsLayer()`, `Modifier.drawWithContent { record + skip }`, `dragAndDropSource(drawDragDecoration = { drawLayer(cardLayer) })` — all gone. The `drawDragDecoration` lambda was the only mechanism the system drag shadow had to render the source's content; with the floating overlay drawing fresh, no snapshot is needed.

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

## Edge gesture handling

`Modifier.systemGestureExclusion()` on the `LazyVerticalGrid` so Android's gesture-nav back doesn't steal the touch sequence and trigger predictive-back animation when the user drags near the screen edge. Android caps the exclusion at 200 dp from each edge, but on a typical phone (~360–400 dp wide) those bands meet in the middle, so the *whole* grid is excluded. Edge-swipe back won't fire while inside the grid; users still have the system-bar back button.

## ViewModel encapsulation

Encapsulation rules to keep the public surface clean:

- `_entries: SnapshotStateList<GridEntry>` (private) → `entries: List<GridEntry>` (public).
- `_dragState: MutableState<DragState?>` (private) → `dragState: DragState?` (public; the property getter delegates to `_dragState.value`, so Compose still observes the read).
- `uiState: StateFlow<UiState>` exposed read-only via `asStateFlow()`.

### Helpers (private)

- `indexOfKey(key: String): Int` — replaces six former `entries.indexOfFirst { it is GridEntry.Header && it.key == X }` sites. The `is GridEntry.Header` guard was redundant since keys are globally unique across the list.
- `indexOfLoaded(widgetId: String): Int` — type-narrowed lookup for `Cell` entries where `state.widget.id == widgetId`.
- `loadedAt(index: Int): GenericWidget?` — safe accessor that folds `getOrNull` + `as? GridEntry.Cell` + `(state as? WidgetState.Loaded)?.widget`.
- `cellOf(widget: GenericWidget): GridEntry.Cell` — wraps a `GenericWidget` in `WidgetState.Loaded(widget)` then in `GridEntry.Cell`. Used at the five mutation sites that re-insert into `_entries` (`onDragHover`, `onDragCommit` Yours→Other branch, `onDragCancel`, `onTransfer`, `reconcileIsYoursForDraggedWidget`) — eliminates the repeated triple-wrapping pattern.

### Mutation atomicity

Multi-step mutations (`onDragHover`, `onDragCancel`, `onTransfer`, the init block) are wrapped in `Snapshot.withMutableSnapshot { ... }` so all `removeAt` / `add` / element-swap operations apply atomically and Compose schedules one recomposition instead of N.

### Other simplifications

- `FakeWidgetsUseCase.INITIAL_WIDGETS.partition { it.isInYourWidgets }` (single traversal) instead of `filter { … }` + `filterNot { … }`.
- `_entries.removeAll { it is GridEntry.Empty }` instead of a manual `while (i < size) { if (...) removeAt(i) else i++ }` loop.
- `onTransfer` collapses both branches into one block: `current.toggleIsInYourWidgets(!current.isInYourWidgets)`, anchor key picked from the new `isInYourWidgets`, single `removeAt` + `add`.

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

## Files

- `app/src/main/java/com/arthlem/dragdrop/MainActivity.kt` — entry point; constructs `WidgetsViewModel` via `viewModelFactory` with `FakeWidgetsUseCase`, hosts `WidgetsScreen` under `MaterialTheme`.
- `app/src/main/java/com/arthlem/dragdrop/GenericWidget.kt` — sealed `GenericWidget` data hierarchy with `InvestmentEntryPoint`/`Pfm`/`Tile.{Monizze,Cashback,Pluxee}` impls, each with polymorphic `toggleIsInYourWidgets`.
- `app/src/main/java/com/arthlem/dragdrop/WidgetState.kt` — `enum WidgetSize`, sealed `WidgetState` (`Skeleton`/`Failure`/`Loaded(widget: GenericWidget)`).
- `app/src/main/java/com/arthlem/dragdrop/WidgetsUseCase.kt` — `WidgetsUseCase` interface + `FakeWidgetsUseCase` returning `Either<Throwable, Flow<List<WidgetState>>>`.
- `app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt` — state machine, mutation functions, reconciliation helpers, deferred-emission logic, skeleton seed.
- `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` — `WidgetsScreen` / `WidgetsContent` / `HeaderCell` / `WidgetCard` / `FloatingWidgetCard` / `SkeletonCell` / `FailureCell` / `EmptyDropZone` / `ErrorScreen` / `bindBounds` / `cellSize` / `debugLabel` / `hitTest` / `rememberEdgeAutoScroll` / `EdgeAutoScroll`.

## Initial test data

Six widgets configured in `FakeWidgetsUseCase.INITIAL_WIDGETS` cover one of each `Loaded` subtype with mixed spans across both sections, sufficient to exercise all four drag rules:

```
m1   Tile.Monizze            small    Yours
c1   Tile.Cashback           small    Yours
iep1 InvestmentEntryPoint    full     Yours
pfm1 Pfm                     full     Other
p1   Tile.Pluxee             small    Other
m2   Tile.Monizze            small    Other
```

A duplicate `Monizze` (`m1` vs `m2`) verifies same-type uniqueness via `id`. Initial render shows 6 skeletons (4 small + 2 full, mirroring the eventual loaded layout) for ~500 ms while the fake eligibility delay completes; the grid then transitions to `UiState.Loaded` with the full data above.

To test the error screen, flip `FakeWidgetsUseCase.FAIL_ELIGIBILITY` to `true` and reinstall.

## Build

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Design docs

Iteration history is captured in:

- `docs/superpowers/specs/2026-04-30-commit-on-release-drag-design.md` — why drops outside targets commit instead of canceling.
- `docs/superpowers/specs/2026-04-30-direction-aware-drag-and-header-targets-design.md` — direction-aware insert math + headers as drop targets.
- `docs/superpowers/plans/2026-04-30-commit-on-release-drag.md` — implementation plan for commit-on-release.
- `docs/superpowers/plans/2026-04-30-direction-aware-drag-and-header-targets.md` — implementation plan for direction-aware insert + headers.
