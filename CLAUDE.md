# DragDrop — Widget Dashboard POC

Proof-of-concept Android app: a draggable widget dashboard with two sections ("Your widgets" / "Other widgets") supporting reorder, cross-section transfer (drag or button), and mixed-span items inside a single `LazyVerticalGrid`.

## Hard constraint: native Compose drag-and-drop only

Use `Modifier.dragAndDropSource` / `Modifier.dragAndDropTarget` (`androidx.compose.foundation.draganddrop.*`).

**Do NOT introduce `Calvin-LL/Reorderable` or any other third-party reordering library.** A confirmed library bug ([Calvin-LL/Reorderable#93](https://github.com/Calvin-LL/Reorderable/issues/93)) causes flicker on mixed-span items in `LazyVerticalGrid`. Native Compose D&D avoids it because target detection is independent of item geometry.

## Stack

- AGP 9.0.0-beta03, Gradle 9.1.0 (built-in Kotlin support — do **not** apply `org.jetbrains.kotlin.android` plugin, it conflicts with the AGP-provided `kotlin` extension; only `org.jetbrains.kotlin.plugin.compose` is applied).
- Compose BOM 2025.01.00 (Compose Foundation/UI 1.7.x), Material 3, lifecycle 2.8.7, activity-compose 1.9.3.
- Arrow Core 1.2.4 (`Either<Throwable, Flow<List<WidgetState>>>` for eligibility-aware data fetching).
- minSdk 26, targetSdk/compileSdk 36, JVM target 11.

## Architecture

- **MVVM with strict separation.** `WidgetsViewModel` owns all mutation logic. `WidgetsScreen.kt` composables are pure renderers — they observe state and forward user input via callbacks.
- **`WidgetsUseCase` is constructor-injected** into the ViewModel via Compose's `viewModelFactory` in `MainActivity`. The interface returns `Either<Throwable, Flow<List<WidgetState>>>` — `Left` is a terminal eligibility-failure state (no flow exists to subscribe to); `Right` is the live widget stream. `FakeWidgetsUseCase` flips a `FAIL_ELIGIBILITY` constant to test the error path.
- **Sealed `WidgetState` hierarchy.** Three top-level variants: `Skeleton(key, size)`, `Failure(key, size)`, and `Loaded` (a sealed sub-interface with `InvestmentEntryPoint`, `Pfm`, and `Tile.{Monizze, Cashback, Pluxee}`). `Loaded` carries `id` / `size` / `isInYourWidgets` and a polymorphic `toggleIsInYourWidgets(b)` that returns the same concrete subtype — reorder/transfer logic preserves widget type without `when`-branching.
- **`SnapshotStateList<GridEntry>` as source of truth.** Backed by a private `_entries`; the public view is `List<GridEntry>` (read-only). Compose still observes mutations because the underlying list is `SnapshotStateList`.
- **Sealed `GridEntry` interface.** Three variants: `Header(key, title)`, `Cell(state: WidgetState)`, `Empty(key, message)`. `GridEntry.Cell.key` resolves to `state.id` for `Loaded` and to `state.key` for `Skeleton`/`Failure`.
- **Three-state UI.** `UiState.Loading` (6 skeletons in `_entries`, no headers, no drag), `UiState.Error(cause)` (empty `_entries`, `ErrorScreen` rendered instead of the grid), `UiState.Loaded` (full grid with headers, section partition, drag/drop). `UiState.Loading` and `UiState.Loaded` both render through the same `WidgetsContent` — only the `_entries` contents change.
- **Single source of truth for `isInYourWidgets`.** Derived from list position relative to the Available header — never stored independently after initial load. `reconcileIsYoursForDraggedWidget` runs after each drag move and uses `current.toggleIsInYourWidgets(...)` to preserve the subtype.
- **Deferred emissions during drag.** A `pendingEntries: List<WidgetState>?` field stores any flow emission that arrives mid-drag; `onDragCommit` / `onDragCancel` replay it via `flushPendingEntriesIfAny()` so a remote update cannot stomp on an active drag.
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

| Composable | Drop key | Resolution |
|---|---|---|
| `WidgetCard` | `state.id` | Direction-aware insert at target's `entries` index |
| `HeaderCell` (Yours) | `YOURS_HEADER_KEY` | Insert just after the Yours header (start of Yours) |
| `HeaderCell` (Other) | `AVAILABLE_HEADER_KEY` | Insert at the available header's own index — direction-aware then takes over (drag-down lands end-of-Yours, drag-up lands start-of-Other) |
| `EmptyDropZone` | `YOURS_EMPTY_KEY` / `AVAILABLE_EMPTY_KEY` | Insert at the placeholder's own index (the placeholder is then stripped by reconcile) |

All four target types share the same drop-handler shape via the `rememberDropTarget(onHover, onDrop, onEnded): DragAndDropTarget` helper at the top of `WidgetsScreen.kt`. `shouldStartDragAndDrop` is `::acceptPlainText` everywhere — the predicate is hoisted to a private file-level function.

## Drag visualization

Currently uses the **system drag shadow** approach via a graphics-layer snapshot:

- `cardLayer = rememberGraphicsLayer()` per `WidgetCard`.
- `Modifier.drawWithContent { cardLayer.record { drawContent() }; if (!isBeingDragged) drawLayer(cardLayer) }` records the card's drawing into the layer every frame and skips drawing it to the canvas when the card is being dragged → source slot is invisible during drag.
- `dragAndDropSource(drawDragDecoration = { drawLayer(cardLayer) })` — the system's drag shadow renders the recorded layer, smoothly following the finger.
- `Modifier.graphicsLayer { scaleX/Y = scale }` and `Card(elevation = animateDpAsState(...))` provide the lift animation on the source slot before it goes invisible / after it reappears.

### Known limitations of this approach (Compose D&D, not us)

Documented because they keep coming up:

1. **Touch-center glitch.** Android's `View.DragShadowBuilder.onProvideShadowMetrics(outShadowSize, outShadowTouchPoint)` controls where the touch anchors on the shadow bitmap. Compose's `dragAndDropSource` constructs the builder internally with the touch point hardcoded to the source's center; neither `DragAndDropTransferData` nor the `drawDragDecoration` lambda exposes an override. So the shadow visually jumps from the press point to centered-on-finger when the drag starts.
2. **Snapshot is captured at `startTransfer` time.** `animateDpAsState` / `animateFloatAsState` haven't progressed by the time the snapshot is taken, so animated `scale` and `elevation` won't show up in the drag shadow itself unless applied at draw-time inside `drawDragDecoration` (e.g., `scale(1.05f) { drawLayer(cardLayer) }`) or the source is forced to redraw with the new state via a `withFrameNanos { }` between `onDragStart` and `startTransfer`.
3. **Source can't track the finger continuously after `startTransfer`.** The system owns input once the drag begins; `dragAndDropSource`'s gesture coroutine doesn't see further pointer events on the source. The only way to get continuous pointer position during a system drag is via a wrapping `dragAndDropTarget.onMoved` over the grid (which works but adds complexity — see below).

### Reorderable comparison (informational)

Reorderable bypasses native D&D entirely — pure `Modifier.pointerInput` from `down` through `up`, applying `Modifier.offset` to the source by `pointerCurrent - pointerDown` so the press point stays under the finger. That's why it has none of these three limitations, and also why it can't do cross-app/cross-window drops. The Reorderable bug we're avoiding is in its mixed-span geometry math, not its touch-tracking approach — a from-scratch `pointerInput` reorderer in this project could in principle have both, at the cost of abandoning the spec's native-D&D constraint.

### What was tried and reverted

- **Source-visible-only mode** (no `cardLayer`, source slot stays drawn during drag, no system shadow): user feedback "feels limited" — slot only moves at target boundaries, not continuously with the finger.
- **Wrapping `dragAndDropTarget` + offset translation on the source** (recover smooth follow on top of native D&D): user feedback "now it lags a lot" because every `WidgetCard` was reading `pointerInRoot` as a regular parameter, recomposing on every `onMoved` tick.
- **Same as above, plus `State<Offset?>` deferred-read parameter** so only `Modifier.graphicsLayer { … }` re-evaluates per pointer tick (no recomposition cascade): user reverted this and the previous attempts; we kept only the simplify-skill refactors.

## Long-press detection

Custom `detectShortLongPress(pointerId, timeoutMs)` extension on `AwaitPointerEventScope` (`LONG_PRESS_TIMEOUT_MS = 200L`). Replaces `awaitLongPressOrCancellation` because that function uses the platform default (`viewConfiguration.longPressTimeoutMillis` ≈ 500 ms) with no override.

**Important gotcha:** the function uses `withTimeout(...)` which inside an `AwaitPointerEventScope` resolves to the scope's *own* `withTimeout` member (not `kotlinx.coroutines.withTimeout`) and throws `PointerEventTimeoutCancellationException`, **not** `kotlinx.coroutines.TimeoutCancellationException`. They're sibling subclasses of `CancellationException`. Catching the wrong one lets the exception propagate, cancels the `dragAndDropSource` coroutine, and `startTransfer` never fires.

## Edge gesture handling

`Modifier.systemGestureExclusion()` on the `LazyVerticalGrid` so Android's gesture-nav back doesn't steal the touch sequence and trigger predictive-back animation when the user drags near the screen edge. Android caps the exclusion at 200 dp from each edge, but on a typical phone (~360–400 dp wide) those bands meet in the middle, so the *whole* grid is excluded. Edge-swipe back won't fire while inside the grid; users still have the system-bar back button.

## ViewModel encapsulation

Encapsulation rules to keep the public surface clean:

- `_entries: SnapshotStateList<GridEntry>` (private) → `entries: List<GridEntry>` (public).
- `_dragState: MutableState<DragState?>` (private) → `dragState: DragState?` (public; the property getter delegates to `_dragState.value`, so Compose still observes the read).
- `uiState: StateFlow<UiState>` exposed read-only via `asStateFlow()`.

### Helpers (private)

- `indexOfKey(key: String): Int` — replaces six former `entries.indexOfFirst { it is GridEntry.Header && it.key == X }` sites. The `is GridEntry.Header` guard was redundant since keys are globally unique across the list.
- `indexOfLoaded(widgetId: String): Int` — type-narrowed lookup for `Cell` entries holding a `WidgetState.Loaded`.
- `loadedAt(index: Int): WidgetState.Loaded?` — safe accessor that folds `getOrNull` + `as? GridEntry.Cell` + `state as? WidgetState.Loaded`.

### Mutation atomicity

Multi-step mutations (`onDragHover`, `onDragCancel`, `onTransfer`, the init block) are wrapped in `Snapshot.withMutableSnapshot { ... }` so all `removeAt` / `add` / element-swap operations apply atomically and Compose schedules one recomposition instead of N.

### Other simplifications

- `FakeWidgetsUseCase.INITIAL_WIDGETS.partition { it.isInYourWidgets }` (single traversal) instead of `filter { … }` + `filterNot { … }`.
- `_entries.removeAll { it is GridEntry.Empty }` instead of a manual `while (i < size) { if (...) removeAt(i) else i++ }` loop.
- `onTransfer` collapses both branches into one block: `current.toggleIsInYourWidgets(!current.isInYourWidgets)`, anchor key picked from the new `isInYourWidgets`, single `removeAt` + `add`.

## Composable code organization (`WidgetsScreen.kt`)

- `@file:OptIn(ExperimentalFoundationApi::class)` at the top — promotes the per-function annotations to file-level.
- Private file-level helpers near the top: `LONG_PRESS_TIMEOUT_MS`, `acceptPlainText(event)`, `rememberDropTarget(onHover, onDrop, onEnded)`.
- `WidgetsContent` hoists one `commitIfDragging: () -> Unit` lambda, passed to all three `onEnded` call sites.
- `HeaderCell`, `WidgetCard`, `EmptyDropZone` each call `rememberDropTarget(...)` on one line and pass `::acceptPlainText` to `dragAndDropTarget.shouldStartDragAndDrop`. `SkeletonCell` and `FailureCell` render only — no drag modifiers.
- `detectShortLongPress` lives at the bottom as a private file-level extension on `AwaitPointerEventScope`.

## Files

- `app/src/main/java/com/arthlem/dragdrop/MainActivity.kt` — entry point; constructs `WidgetsViewModel` via `viewModelFactory` with `FakeWidgetsUseCase`, hosts `WidgetsScreen` under `MaterialTheme`.
- `app/src/main/java/com/arthlem/dragdrop/WidgetState.kt` — `enum WidgetSize`, sealed `WidgetState` hierarchy (`Skeleton`/`Failure`/`Loaded` with `InvestmentEntryPoint`/`Pfm`/`Tile.{Monizze,Cashback,Pluxee}`).
- `app/src/main/java/com/arthlem/dragdrop/WidgetsUseCase.kt` — `WidgetsUseCase` interface + `FakeWidgetsUseCase` returning `Either<Throwable, Flow<List<WidgetState>>>`.
- `app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt` — state machine, mutation functions, reconciliation helpers, deferred-emission logic, skeleton seed.
- `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` — `WidgetsScreen` / `WidgetsContent` / `HeaderCell` / `WidgetCard` / `SkeletonCell` / `FailureCell` / `EmptyDropZone` / `ErrorScreen` / `rememberDropTarget` / `acceptPlainText` / `cellSize` / `debugLabel` / `detectShortLongPress`.

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
