# DragDrop — Widget Dashboard POC

Proof-of-concept Android app: a draggable widget dashboard with two sections ("Your widgets" / "Other widgets") supporting reorder, cross-section transfer (drag or button), and mixed-span items inside a single `LazyVerticalGrid`. The widget section coexists with non-widget items (banner, footer, etc.) in the parent grid via a `LazyGridScope.widgetSection(…)` extension function — no nested grids. Widgets are reorderable only after the user enters **reorder mode** via a long-press contextual menu; default long-press opens the menu, reorder-mode long-press starts a drag.

## Hard constraint: no third-party reorder libraries

**Do NOT introduce `Calvin-LL/Reorderable` or any other third-party reordering library.** A confirmed library bug ([Calvin-LL/Reorderable#93](https://github.com/Calvin-LL/Reorderable/issues/93)) causes flicker on mixed-span items in `LazyVerticalGrid`.

The drag pipeline is built directly on Compose Foundation primitives — `Modifier.pointerInput`, `awaitEachGesture`, `awaitFirstDown`, `awaitPointerEvent`, `withTimeoutOrNull`, `Modifier.onGloballyPositioned`, and `LazyGridState.scrollBy`. Native `Modifier.dragAndDropSource` / `Modifier.dragAndDropTarget` were used previously but were replaced because Compose Foundation 1.11 does not yet expose a public custom long-press detector (`detectDragStart` is `internal` with a `TODO: Expose this as public argument`), and because animations / touch-center fidelity / continuous source tracking are easier to control directly. Either path is acceptable as long as we own the geometry math.

## Stack

- AGP 9.0.0-beta03, Gradle 9.1.0 (built-in Kotlin support — do **not** apply `org.jetbrains.kotlin.android` plugin, it conflicts with the AGP-provided `kotlin` extension; only `org.jetbrains.kotlin.plugin.compose` is applied).
- Compose BOM 2026.04.01 (Compose Foundation/UI 1.11.0, Material 3 1.4.0), lifecycle 2.8.7, activity-compose 1.9.3.
- Arrow Core 1.2.4 (`Either<Throwable, Flow<List<WidgetState>>>` for eligibility-aware data fetching).
- AndroidX DataStore Preferences 1.1.1 (persists Yours-section ordering across launches).
- minSdk 26, targetSdk/compileSdk 36, JVM target 11.

## Architecture

- **MVVM with strict separation.** `WidgetsViewModel` owns all mutation logic. `WidgetsScreen.kt` composables are pure renderers — they observe state and forward user input via callbacks. Reorder mode is a UI affordance and lives as `var reorderMode by remember { mutableStateOf(false) }` at the screen level — the ViewModel knows nothing about it.
- **`WidgetsUseCase` is constructor-injected** into the ViewModel via Compose's `viewModelFactory` in `MainActivity`. The interface returns `Either<Throwable, Flow<List<WidgetState>>>` from `fetchWidgets()` — `Left` is a terminal eligibility-failure state (no flow exists to subscribe to); `Right` is the live widget stream. `FakeWidgetsUseCase` flips a `FAIL_ELIGIBILITY` constant to test the error path.
- **Yours-section ordering is persisted** to `DataStore<Preferences>` (key `"yours_order"`, comma-delimited list of widget IDs). The use case has a second method `saveYoursOrder(ids)`; the ViewModel calls it from `onDragCommit` / `onDragCancel` / `onTransfer` (committed states only — drag-time hover swaps aren't persisted). On `fetchWidgets()`, the use case reads the saved order *once*, sorts `INITIAL_WIDGETS` so saved IDs become Yours in saved order and unsaved IDs default to Other. Saves are fire-and-forget via `viewModelScope.launch` and don't loop back into the active flow — avoids "save → re-emit → mid-drag entries reset" races.
- **Sealed `GenericWidget` hierarchy carries the typed widget data.** Concrete data classes (`InvestmentEntryPoint`, `Pfm`, `Tile.{Monizze, Cashback, Pluxee}`) each carry `id` / `size` / `isInYourWidgets` and a polymorphic `toggleIsInYourWidgets(b)` that returns the same concrete subtype — reorder/transfer logic preserves widget type without `when`-branching. Per-type data fields (presentation payloads, balances, etc.) live on each impl.
- **`WidgetState` is the cell's lifecycle state.** Three variants: `Skeleton(key, size)` and `Failure(key, size)` (placeholders with no widget data), and `Loaded(widget: GenericWidget)` — a thin wrapper marking "this slot has finished loading". The `widget` field is the only payload `Loaded` carries.
- **`SnapshotStateList<GridEntry>` as source of truth.** Backed by a private `_entries`; the public view is `List<GridEntry>` (read-only). Compose still observes mutations because the underlying list is `SnapshotStateList`.
- **Sealed `GridEntry` interface.** Four variants: `Header(key, title)`, `Cell(state: WidgetState)`, `Empty(key, message)`, `RowFiller(key)`. `GridEntry.Cell.key` resolves to `state.widget.id` for `Loaded` and to `state.key` for `Skeleton`/`Failure`. `RowFiller` is a 1-column invisible slot that fills out partial rows and acts as a first-class drop target — see "Empty-slot drops" below.
- **Three-state UI.** `UiState.Loading` (6 skeletons in `_entries`, no headers, no drag), `UiState.Error(cause)` (empty `_entries`, `ErrorScreen` rendered instead of the grid), `UiState.Loaded` (full grid with headers, section partition, drag/drop). `UiState.Loading` and `UiState.Loaded` both render through the same `WidgetsContent` — only the `_entries` contents change.
- **Single source of truth for `isInYourWidgets`.** Derived from list position relative to the Available header — never stored independently after initial load. `reconcileIsYoursForDraggedWidget` runs after each drag move and uses `current.toggleIsInYourWidgets(...)` to preserve the subtype.
- **Deferred emissions during drag.** A `pendingEntries: List<WidgetState>?` field stores any flow emission that arrives mid-drag; `onDragCommit` / `onDragCancel` replay it via `flushPendingEntriesIfAny()` so a remote update cannot stomp on an active drag.
- **Drag pipeline is `pointerInput`-based.** A per-`WidgetCard` `Modifier.pointerInput` runs a custom long-press detector (`withTimeoutOrNull(LONG_PRESS_TIMEOUT_MS)`), captures the press offset within the cell, then drives the drag through release. Drop targets register `boundsInWindow()` into a screen-level `dragBounds: SnapshotStateMap<String, Rect>` via `Modifier.bindBounds(key, dragBounds)`. The dragging cell hit-tests against this map on every pointer event and calls `viewModel.onDragHover(targetKey)`. The source slot stays at its `_entries` index with `Modifier.alpha(0f)`; a sibling `Box` overlay renders a `FloatingWidgetCard` that follows the finger using window-coord math (`floatingPos = finger − pressOffsetWithinCell − boxOrigin`). **Once the long-press succeeds and the drag loop starts, every pointer change is `change.consume()`'d** — without this, `LazyVerticalGrid`'s built-in scroll gesture detector also processes the drag motion and scrolls the grid under the finger. Pre-long-press events are deliberately *not* consumed so a normal short swipe still scrolls the grid.
- **`bindBounds` and `dragSource` live on the inner Box of the cell, not the outer cell slot.** Each `WidgetCard` is a nested `Box`-in-`Box`: the outer Box reserves layout space for an overhanging top-left badge (the +/− `TransferBadge`), the inner Box holds the visible Card. Both `bindBounds(widget.id, ...)` and `dragSource(...)` are attached to the inner Box. This matters because attaching them to the outer Box makes adjacent cells' rects overlap in the overhang region, which causes `hitTest` to flip-flop between the two cells frame-to-frame as the finger crosses the overlap — visible as cross-cell hover flicker. Keeping the rect tight to the visible card fixes that and also means tapping the badge never accidentally starts a drag (the badge is a sibling of the inner Box, not a child of `dragSource`).
- **Edge auto-scroll.** An `EdgeAutoScroll` helper drives `LazyGridState.scrollBy(velocity)` while the dragging finger sits within a 40 dp band at the top or bottom of the grid (inside the grid). Velocity ramps linearly from ~12 px/frame at the edge itself to 0 at 40 dp inside the grid. Stops on release / coroutine cancel. Mid-screen drags don't accidentally scroll the grid because the drag loop `change.consume()`s every pointer event during drag — `LazyVerticalGrid`'s built-in scroll detector never sees them, so this is the only path that can scroll during drag.
- **Dynamic empty placeholders.** `reconcileEmptyPlaceholders()` strips all `GridEntry.Empty` entries and re-adds them based on section emptiness. Tail-first insertion order keeps the available header's index stable for the second insert without a re-lookup.
- **Constants for section anchors.** `YOURS_HEADER_KEY` / `AVAILABLE_HEADER_KEY` / `YOURS_EMPTY_KEY` / `AVAILABLE_EMPTY_KEY`.

## Reorder mode

Default mode: long-press on a widget opens a `DropdownMenu` anchored to the cell. The menu lists per-widget actions (e.g. Cashback's "View transactions") plus common actions (`Reorder`, `Manage widgets`). Tapping `Reorder` flips `reorderMode = true`, which:

- Unlocks the drag pipeline (long-press now starts a drag instead of opening the menu — `WidgetCard` swaps its `dragModifier` chain between `bindBounds + dragSource` and a `pointerInput { detectTapGestures(onLongPress = …) }`).
- Shows the +/− `TransferBadge`s (driven by `WidgetCardShell`'s `showBadge` flag).
- Renders a scrim cutout over everything *except* the widget section (top + bottom strips around the section's bounds; left/right not needed since the section spans full grid width).
- Overlays a "Done" button at the top center.
- `BackHandler(enabled = reorderMode)` exits the mode on system back.

Widget-section bounds for the scrim are derived from `lazyGridState.layoutInfo.visibleItemsInfo` — items whose key matches a `GridEntry.key` belong to the section. The min/max Y of those visible items is wrapped in a `derivedStateOf` and consumed by `ReorderScrim`. If the section is fully scrolled off-screen, bounds is null and the scrim renders nothing (rare edge case in practice).

### `WidgetMenuAction` typed hierarchy

`WidgetMenuAction` is a sealed interface — common actions (`Reorder`, `ManageWidgets`) are top-level data objects, per-widget extras nest under per-type sealed interfaces (`WidgetMenuAction.Cashback.ViewTransactions`, `WidgetMenuAction.Pluxee.Settings`, etc.). `fun GenericWidget.menuActions(): List<WidgetMenuAction>` is an exhaustive `when` over the sealed `GenericWidget` hierarchy — adding a new widget type is a compile error until you add its menu config. `actionLabel(action)` is the parallel exhaustive `when` for display strings; localization-friendly without putting strings into the action hierarchy. The screen owns a single dispatcher: `(WidgetMenuAction, GenericWidget) -> Unit`. Per-widget actions in the POC are stubs (no-op) — add real handlers as needed.

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

### Empty-slot drops via `RowFiller`

Mixed-span layouts leave gaps — e.g. `[m1(small), c1(small), p1(small), iep1(full)]` packs as `[m1, c1]` / `[p1, ⌧]` / `[iep1]`, with `⌧` an unfilled slot. To make `⌧` a real drop target (not a fallback heuristic), we represent it explicitly as a `GridEntry.RowFiller` in the entries list. The filler takes 1 grid column, renders invisibly, and registers `bindBounds` like any other cell.

`onDragHover` resolves filler targets the same way as cells — `targetIndex = indexOfKey(fillerKey)` followed by the standard `removeAt + add` reorder. After every reorder, `reconcileRowFillers()` strips all fillers in each section and re-adds them at canonical positions (just before any full-span widget that follows a partial row, plus end-of-section if the last row is incomplete). So the *visible* drop position can shift as auto-pack repacks the section — the precision tradeoff is intentional.

**Why always re-canonicalize (no skip-on-match):**
- Preserving user-placed filler positions sounded right at first (drop-here-land-here precision), but a few sequential swaps could leave fillers in awkward spots — immediately after a section header (`[_, X] / …`), stranded between a full-span widget and a single trailing small widget, etc.
- Always re-canonicalizing makes those states impossible by construction. Filler positions are deterministic given the (small, full) widget sequence per section.

`reconcileRowFillers()` is called from: `rebuildEntriesFromWidgets`, `onTransfer`, `onDragHover`, `onDragCommit` (Yours→Other branch), `onDragCancel`. Filler keys are sequential strings (`filler_yours_0`, `filler_other_0`, …) regenerated on each pass. Stable enough for `animateItem()` because fillers are invisible.

`RowFiller` is not a drag source (no `dragSource` modifier) — only a drop target.

### Hover hit-testing uses the floating widget's center, not the finger

The drag loop hit-tests `dragBounds` against `floatingCenter = finger − pressOffsetWithinCell + Offset(cellWidth/2, cellHeight/2)` — i.e. the visual center of the floating overlay, not the raw finger position. This gives the natural hysteresis a reorder list needs: when a swap fires, the floating widget visually claims the new slot, so the next swap requires the user to drag the floating widget into yet another slot. Hit-testing against the finger directly causes flip-flop, because after a swap the finger is still over the cell that just moved (now in the dragged widget's old slot), and the same cell keeps re-firing `onDragHover`.

The drag loop also caches `lastHoverKey` and only invokes `currentOnDragHover(key)` when the resolved key actually changes — so even if multiple frames resolve to the same target, the ViewModel sees one call per boundary crossing, not one per pointer event.

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
- **Floating overlay.** Inside `WidgetsContent`'s outer `Box`, a sibling renders `FloatingWidgetCard(widget = draggingWidget.value)` when `fingerInWindow.value != null`. Its position is `floatingTopLeft = fingerInWindow − pressOffsetWithinCell − boxOriginInWindow − Offset(OVERHANG_PX, OVERHANG_PX)`. The OVERHANG subtraction is critical: `pressOffsetWithinCell` is captured by `dragSource` on the source cell's *inner* Box, but `floatingTopLeft` positions the floating widget's *outer* Box. Because both source and floating wrap their visible card in an outer Box with `OVERHANG_DP` top+start padding, omitting the subtraction makes the floating card appear `OVERHANG_DP` down-right of the source at the moment of long-press → drag. `Modifier.zIndex(1f)` ensures it paints above the grid.
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
- File-level constants: `LONG_PRESS_TIMEOUT_MS`, `OVERHANG_DP` (12 dp), `BADGE_SIZE_DP` (28 dp).
- File-level helpers: `hitTest(finger, bounds, draggedKey)`, `cellSize(state)`, `debugLabel(widget)`, `bindBounds(key, dragBounds)` (a `@Composable Modifier` extension), `rememberEdgeAutoScroll(lazyGridState)`, plus the `EdgeAutoScroll` private class.
- `WidgetsContent` owns: `dragBounds`, `pressOffsetWithinCell`, `fingerInWindow`, `draggingWidget`, `boxCoords`, `lazyGridState`, `edgeAutoScroll`. It wraps the `LazyVerticalGrid` in an outer `Box` and renders the floating overlay as a sibling.
- `HeaderCell`, `EmptyDropZone` are pure renderers — bounds registration happens via the `bindBounds` modifier on the call site, not inside the cell.
- `WidgetCard` owns the gesture pipeline: a single `Modifier.pointerInput(widget.id)` runs the long-press detector, captures press state, drives drag through release, and stops auto-scroll in its `finally` block. Source-slot invisibility is `Modifier.alpha(if (isBeingDragged) 0f else 1f)`.
- `WidgetCardShell` owns the duplicated layout structure shared by `WidgetCard` and `FloatingWidgetCard`: outer `Box` (overhang padding) wrapping an inner `Box` (visible card) plus a sibling `TransferBadge` aligned to TopStart with negative offset. Callers pass animation values (`elevation`, `scale`, `alpha`) and a `dragModifier` slot — `WidgetCard` chains `bindBounds + dragSource` into it, `FloatingWidgetCard` passes `Modifier` (the floating overlay is non-interactive).
- `TransferBadge` is the circular +/− button overhanging the top-left corner. Wired to `onTransfer` in `WidgetCard`; passed `onClick = null` in `FloatingWidgetCard` to render as a non-interactive twin during drag.
- `FloatingWidgetCard` is a non-interactive renderer used only by the floating overlay.
- `SkeletonCell`, `FailureCell` are render-only (no drag, no bounds registration).
- `ErrorScreen` renders the `UiState.Error` branch.

## Files

- `app/src/main/java/com/arthlem/dragdrop/MainActivity.kt` — entry point; constructs `WidgetsViewModel` via `viewModelFactory` with `FakeWidgetsUseCase`, hosts `WidgetsScreen` under `MaterialTheme`.
- `app/src/main/java/com/arthlem/dragdrop/GenericWidget.kt` — sealed `GenericWidget` data hierarchy with `InvestmentEntryPoint`/`Pfm`/`Tile.{Monizze,Cashback,Pluxee}` impls, each with polymorphic `toggleIsInYourWidgets`.
- `app/src/main/java/com/arthlem/dragdrop/WidgetState.kt` — `enum WidgetSize`, sealed `WidgetState` (`Skeleton`/`Failure`/`Loaded(widget: GenericWidget)`).
- `app/src/main/java/com/arthlem/dragdrop/WidgetsUseCase.kt` — `WidgetsUseCase` interface + `FakeWidgetsUseCase` returning `Either<Throwable, Flow<List<WidgetState>>>`.
- `app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt` — state machine, mutation functions, reconciliation helpers, deferred-emission logic, skeleton seed.
- `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` — `WidgetsScreen` / `WidgetsContent` (parent screen with the LazyVerticalGrid + reorder-mode state + scrim + Done button + floating overlay) / `widgetSection` (the `LazyGridScope` extension that emits widget items) / `HeaderCell` / `WidgetCard` / `FloatingWidgetCard` / `WidgetCardShell` / `WidgetCardContent` / `TransferBadge` / `RowFillerCell` / `SkeletonCell` / `FailureCell` / `EmptyDropZone` / `ErrorScreen` / `BannerCard` / `FooterCard` / `ReorderScrim` / `DoneButton` / `cellSize` / `debugLabel`. Drag-pipeline helpers (`bindBounds`, `dragSource`, `DragController`, `rememberDragController`, `hitTest`, `rememberEdgeAutoScroll`, `EdgeAutoScroll`) live in `DragSupport.kt`.
- `app/src/main/java/com/arthlem/dragdrop/WidgetMenuAction.kt` — `WidgetMenuAction` sealed hierarchy + `GenericWidget.menuActions()` extension + `actionLabel(action)`.

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
