# WidgetState architecture: typed sealed hierarchy + use-case-driven loading

**Date:** 2026-05-04
**Status:** approved
**Affected files:**
- `app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt` (heavy edit)
- `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` (medium edit)
- `app/src/main/java/com/arthlem/dragdrop/MainActivity.kt` (factory wiring)
- New: `app/src/main/java/com/arthlem/dragdrop/WidgetState.kt` (sealed hierarchy)
- New: `app/src/main/java/com/arthlem/dragdrop/WidgetsUseCase.kt` (interface + fake impl)
- `gradle/libs.versions.toml`, `app/build.gradle.kts` (Arrow dependency)
- `CLAUDE.md` (architecture summary update)

## Problem

The current POC models a widget as a single `Widget(id, name, isFullSpan, isYours)` data class with `INITIAL_WIDGETS` hardcoded inside `WidgetsViewModel`. The real product has:

1. **Typed widget variants.** Real widgets have distinct presentation surfaces — `InvestmentEntryPoint`, `Pfm`, and three `Tile` types (`Monizze`, `Cashback`, `Pluxee`). A single flat `Widget` record can't carry their type-specific data and can't be rendered correctly without runtime type tags.
2. **Three loading-time states per cell.** Each grid cell can be a skeleton (loading), a successfully-loaded widget, or a per-cell failure. The current model represents only the success state.
3. **An eligibility API call** runs *before* the widget stream exists. If it fails, no widget data is ever produced and the UI must show an error screen rather than skeletons forever.

The redesign replaces `Widget` with a sealed `WidgetState` hierarchy, introduces a `WidgetsUseCase` that returns the widget stream wrapped in `Either<Throwable, Flow<List<WidgetState>>>`, and threads loading/error/loaded states through the same grid composable.

## Decision

- **Typed sealed hierarchy.** `WidgetState` is the cell's state (`Skeleton` / `Failure` / `Loaded`). `Loaded` is itself a sealed interface whose concrete subtypes — `InvestmentEntryPoint`, `Pfm`, and `Tile` (with `Monizze`/`Cashback`/`Pluxee` subclasses) — *are* the widgets. There is no separate `GenericWidget` type; `Loaded` carries the common contract (`id`, `size`, `isInYourWidgets`, `toggleIsInYourWidgets`) directly.
- **Eligibility errors are terminal one-shots.** The use case returns `Either<Throwable, Flow<List<WidgetState>>>` — `Left` means the eligibility call failed and there is no flow to subscribe to; `Right` is the live widget stream. Per-widget `Failure` is a separate concern carried inside the flow itself.
- **One unified grid composable** handles all three `WidgetState` variants. During `Loading`, the grid contains six `Skeleton` cells with no headers; during `Loaded`, headers and section logic are present. `Skeleton` and `Failure` cells render but are not drag sources or targets.
- **Single-module, no DI framework.** `WidgetsUseCase` is constructor-injected via Compose's `viewModelFactory`. Hilt/multi-module split is deferred to when the real use case lands.
- **Arrow `Either`** is the chosen wrapper. Arrow is already in the real app, so this matches the production shape and avoids reinvention with `kotlin.Result` or a custom sealed type.

## Architecture

### Type hierarchy (`WidgetState.kt`)

```kotlin
enum class WidgetSize { SMALL, FULL }

sealed interface WidgetState {

    data class Skeleton(val key: String, val size: WidgetSize) : WidgetState
    data class Failure(val key: String, val size: WidgetSize) : WidgetState

    sealed interface Loaded : WidgetState {
        val id: String
        val size: WidgetSize
        val isInYourWidgets: Boolean
        fun toggleIsInYourWidgets(shouldBeInYourWidgets: Boolean): Loaded

        data class InvestmentEntryPoint(
            override val id: String,
            override val size: WidgetSize,
            override val isInYourWidgets: Boolean,
        ) : Loaded {
            override fun toggleIsInYourWidgets(shouldBeInYourWidgets: Boolean) =
                copy(isInYourWidgets = shouldBeInYourWidgets)
        }

        data class Pfm(
            override val id: String,
            override val size: WidgetSize,
            override val isInYourWidgets: Boolean,
        ) : Loaded {
            override fun toggleIsInYourWidgets(shouldBeInYourWidgets: Boolean) =
                copy(isInYourWidgets = shouldBeInYourWidgets)
        }

        sealed interface Tile : Loaded {
            data class Monizze(
                override val id: String,
                override val size: WidgetSize,
                override val isInYourWidgets: Boolean,
            ) : Tile {
                override fun toggleIsInYourWidgets(shouldBeInYourWidgets: Boolean) =
                    copy(isInYourWidgets = shouldBeInYourWidgets)
            }
            data class Cashback(
                override val id: String,
                override val size: WidgetSize,
                override val isInYourWidgets: Boolean,
            ) : Tile {
                override fun toggleIsInYourWidgets(shouldBeInYourWidgets: Boolean) =
                    copy(isInYourWidgets = shouldBeInYourWidgets)
            }
            data class Pluxee(
                override val id: String,
                override val size: WidgetSize,
                override val isInYourWidgets: Boolean,
            ) : Tile {
                override fun toggleIsInYourWidgets(shouldBeInYourWidgets: Boolean) =
                    copy(isInYourWidgets = shouldBeInYourWidgets)
            }
        }
    }
}
```

`toggleIsInYourWidgets` is overridden by every concrete `Loaded` data class so the return type is preserved through the polymorphic call: `Monizze.toggleIsInYourWidgets(...)` returns a `Monizze`, never a generic `Loaded`. The ViewModel's `onTransfer` and `reconcileIsYoursForDraggedWidget` rely on this — neither needs `when`-branching or `copy()` calls of its own.

The old `Widget` data class is deleted.

### `GridEntry` change (in `WidgetsViewModel.kt`)

`GridEntry.Item(widget: Widget)` is renamed to `GridEntry.Cell(state: WidgetState)`. Header and Empty variants are unchanged.

```kotlin
sealed interface GridEntry {
    val key: String
    data class Header(override val key: String, val title: String) : GridEntry
    data class Cell(val state: WidgetState) : GridEntry {
        override val key: String get() = when (state) {
            is WidgetState.Loaded   -> state.id
            is WidgetState.Skeleton -> state.key
            is WidgetState.Failure  -> state.key
        }
    }
    data class Empty(override val key: String, val message: String) : GridEntry
}
```

### Use case + DI wiring (`WidgetsUseCase.kt`)

```kotlin
interface WidgetsUseCase {
    suspend fun fetchWidgets(): Either<Throwable, Flow<List<WidgetState>>>
}

class FakeWidgetsUseCase : WidgetsUseCase {
    companion object {
        const val FAIL_ELIGIBILITY = false       // flip to test error screen
        private const val ELIGIBILITY_DELAY_MS = 500L

        private val INITIAL_WIDGETS: List<WidgetState> = listOf(
            WidgetState.Loaded.Tile.Monizze (id = "m1",   size = WidgetSize.SMALL, isInYourWidgets = true),
            WidgetState.Loaded.Tile.Cashback(id = "c1",   size = WidgetSize.SMALL, isInYourWidgets = true),
            WidgetState.Loaded.InvestmentEntryPoint(id = "iep1", size = WidgetSize.FULL,  isInYourWidgets = true),
            WidgetState.Loaded.Pfm           (id = "pfm1", size = WidgetSize.FULL,  isInYourWidgets = false),
            WidgetState.Loaded.Tile.Pluxee   (id = "p1",   size = WidgetSize.SMALL, isInYourWidgets = false),
            WidgetState.Loaded.Tile.Monizze  (id = "m2",   size = WidgetSize.SMALL, isInYourWidgets = false),
        )
    }

    override suspend fun fetchWidgets(): Either<Throwable, Flow<List<WidgetState>>> {
        delay(ELIGIBILITY_DELAY_MS)
        return if (FAIL_ELIGIBILITY) {
            IllegalStateException("Eligibility check failed").left()
        } else {
            flowOf(INITIAL_WIDGETS).right()
        }
    }
}
```

Six widgets — one of each `Loaded` subtype plus a duplicate `Monizze` to verify same-type uniqueness via `id`. Mixed-span coverage in both sections so all four drag rules (Yours→Yours reorder, Other→Yours transfer, Yours→Other snap, Other→Other revert) are exercisable on launch.

`WidgetsViewModel` becomes `class WidgetsViewModel(private val useCase: WidgetsUseCase) : ViewModel()`. `MainActivity` (or wherever `WidgetsScreen` is hoisted) wires the factory:

```kotlin
val viewModel: WidgetsViewModel = viewModel(
    factory = viewModelFactory {
        initializer { WidgetsViewModel(FakeWidgetsUseCase()) }
    }
)
```

When the real use case arrives, only this factory line changes.

### `UiState` and the loading→loaded transition

```kotlin
sealed interface UiState {
    data object Loading : UiState
    data class Error(val cause: Throwable) : UiState
    data object Loaded : UiState
}
```

The grid renders from `viewModel.entries` regardless of `UiState`:

| `UiState`  | `_entries` contents | What renders |
|---|---|---|
| `Loading`  | 6 × `GridEntry.Cell(Skeleton)` — no headers, no empty placeholders | Skeleton grid |
| `Error`    | empty | Error composable replaces the grid |
| `Loaded`   | `Header(Yours)` + Yours cells + `Header(Other)` + Other cells (+ empty placeholders) | Full grid with drag/drop |

**Skeleton seed** (mixed sizes mirror the eventual `Loaded` layout — 4 SMALL + 2 FULL = 6 cells):

```kotlin
private val SKELETON_SEED: List<WidgetState.Skeleton> = listOf(
    WidgetState.Skeleton("skeleton_0", WidgetSize.SMALL),
    WidgetState.Skeleton("skeleton_1", WidgetSize.SMALL),
    WidgetState.Skeleton("skeleton_2", WidgetSize.FULL),
    WidgetState.Skeleton("skeleton_3", WidgetSize.FULL),
    WidgetState.Skeleton("skeleton_4", WidgetSize.SMALL),
    WidgetState.Skeleton("skeleton_5", WidgetSize.SMALL),
)
```

**Init sequence in `WidgetsViewModel.init`:**

1. `Snapshot.withMutableSnapshot { _entries.addAll(SKELETON_SEED.map { GridEntry.Cell(it) }) }`. `_uiState = UiState.Loading`. The grid renders skeletons immediately.
2. Launch `viewModelScope { useCase.fetchWidgets().fold(onLeft = ::handleError, onRight = ::collectLoadedFlow) }`.
3. `handleError(throwable)` — clear `_entries`, set `_uiState = UiState.Error(throwable)`.
4. `collectLoadedFlow(flow)` — `flow.collect { widgets -> rebuildEntriesFromWidgets(widgets); if (uiState != Loaded) _uiState.value = UiState.Loaded }`.

`rebuildEntriesFromWidgets` partitions by `isInYourWidgets`, wraps in `GridEntry.Cell`, inserts both headers, runs `reconcileEmptyPlaceholders()`. Wrapped in `Snapshot.withMutableSnapshot { ... }`.

Each flow emission is treated as a full snapshot replacement — matches the current single-load model with the door open for live updates later.

### ViewModel mutation logic

**`DragState` references `WidgetState.Loaded`:**

```kotlin
data class DragState(
    val draggedWidget: WidgetState.Loaded,
    val originalIndex: Int,
    val originalIsInYourWidgets: Boolean,
)
```

**`onTransfer` becomes type-preserving via the polymorphic toggle:**

```kotlin
fun onTransfer(widgetId: String) {
    val cellIndex = indexOfLoaded(widgetId)
    val current = loadedAt(cellIndex) ?: return
    val moved = current.toggleIsInYourWidgets(!current.isInYourWidgets)
    val anchorKey = if (moved.isInYourWidgets) YOURS_HEADER_KEY else AVAILABLE_HEADER_KEY
    Snapshot.withMutableSnapshot {
        _entries.removeAt(cellIndex)
        val anchor = indexOfKey(anchorKey)
        val target = (if (anchor < 0) _entries.size else anchor + 1).coerceIn(0, _entries.size)
        _entries.add(target, GridEntry.Cell(moved))
        reconcileEmptyPlaceholders()
    }
}
```

`reconcileIsYoursForDraggedWidget` uses the same toggle in place of `widget.copy(isYours = ...)`.

**Helpers (rename + retype):**

```kotlin
private fun indexOfLoaded(widgetId: String): Int =
    _entries.indexOfFirst {
        it is GridEntry.Cell && it.state is WidgetState.Loaded && it.state.id == widgetId
    }

private fun loadedAt(index: Int): WidgetState.Loaded? =
    ((_entries.getOrNull(index) as? GridEntry.Cell)?.state as? WidgetState.Loaded)
```

**Two distinct gating layers, do not conflate:**

1. **Compose-level (new):** `SkeletonCell` and `FailureCell` composables do not attach `Modifier.dragAndDropSource` or `Modifier.dragAndDropTarget` at all. There is no drag surface on those cells. The `is WidgetState.Loaded` check at the `items { }` branch decides which composable renders, and only `WidgetCard` carries drag modifiers.
2. **Runtime predicate (unchanged):** `acceptPlainText(event)` still gates `shouldStartDragAndDrop` on `WidgetCard`'s drag target by MIME type — its job is to filter unrelated drag events (e.g., text dropped from another app), not to gate which cells participate.

**Deferred emissions during drag.** A `flow.collect { ... }` mid-drag would stomp on the working list and break drag invariants. The collect block checks:

```kotlin
flow.collect { widgets ->
    if (_dragState.value == null) {
        rebuildEntriesFromWidgets(widgets)
    } else {
        pendingEntries = widgets
    }
    if (_uiState.value != UiState.Loaded) _uiState.value = UiState.Loaded
}
```

A `pendingEntries: List<WidgetState>?` field stores the latest emission; both `onDragCommit` and `onDragCancel` end with `pendingEntries?.let { rebuildEntriesFromWidgets(it); pendingEntries = null }`.

**`onDragCancel` is retained as unused surface area** per CLAUDE.md.

### `WidgetsScreen` changes

**Top-level branching adds the Error case; Loading is no longer a separate composable:**

```kotlin
when (val state = uiState) {
    UiState.Loading, UiState.Loaded -> WidgetsContent(viewModel)
    is UiState.Error -> ErrorScreen(cause = state.cause)
}
```

`WidgetsContent`'s `items { }` block branches on `entry.state` for `GridEntry.Cell`:

```kotlin
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
    is WidgetState.Skeleton -> SkeletonCell(modifier = Modifier.animateItem())
    is WidgetState.Failure  -> FailureCell(modifier = Modifier.animateItem())
}
```

**Span lookup** branches on the cell's state size:

```kotlin
span = { entry -> when (entry) {
    is GridEntry.Header, is GridEntry.Empty -> GridItemSpan(maxLineSpan)
    is GridEntry.Cell -> when (val s = entry.state) {
        is WidgetState.Loaded   -> if (s.size == WidgetSize.FULL) GridItemSpan(maxLineSpan) else GridItemSpan(1)
        is WidgetState.Skeleton -> if (s.size == WidgetSize.FULL) GridItemSpan(maxLineSpan) else GridItemSpan(1)
        is WidgetState.Failure  -> if (s.size == WidgetSize.FULL) GridItemSpan(maxLineSpan) else GridItemSpan(1)
    }
} }
```

**Drag/drop gating.** `WidgetCard` (Loaded only) keeps `dragAndDropSource` + `dragAndDropTarget`. `SkeletonCell` and `FailureCell` are render-only — no drag modifiers. Headers and empty placeholders don't render during `Loading`, so there is no D&D surface during loading at all.

**`WidgetCard` parameter type** becomes `state: WidgetState.Loaded`. Reads `state.id`, `state.size`, `state.isInYourWidgets`. The display label is derived from the type for the POC:

```kotlin
@Composable
private fun debugLabel(state: WidgetState.Loaded): String = when (state) {
    is WidgetState.Loaded.InvestmentEntryPoint -> "Investment · ${state.id}"
    is WidgetState.Loaded.Pfm                  -> "PFM · ${state.id}"
    is WidgetState.Loaded.Tile.Monizze         -> "Monizze · ${state.id}"
    is WidgetState.Loaded.Tile.Cashback        -> "Cashback · ${state.id}"
    is WidgetState.Loaded.Tile.Pluxee          -> "Pluxee · ${state.id}"
}
```

The `id` suffix makes duplicates (`m1` vs `m2`) visually distinguishable for drag verification. When real presentation fields land on each `Loaded` subtype (or real card composables ship in the production app), `debugLabel` is the one thing to delete.

**`SkeletonCell`** is a `Card` with a uniform `surfaceVariant` background fill and the same `min-height` rule as `WidgetCard` (96.dp small, 120.dp full). No shimmer for v1 — placeholder shape is enough.

**`FailureCell`** is a stub call site; the user provides the styled implementation on their side.

**`ErrorScreen`** is a centered column with an error icon, a short message, and the throwable's message in `bodySmall`. No retry button (deferred to v2).

### Renames and removed surface

- `Widget` data class → deleted.
- `Widget.isFullSpan: Boolean` → `WidgetState.Loaded.size: WidgetSize`.
- `Widget.isYours: Boolean` → `WidgetState.Loaded.isInYourWidgets: Boolean`.
- `Widget.name: String` → deleted; replaced by `debugLabel(state)`.
- `GridEntry.Item` → `GridEntry.Cell`.
- `DragState.originalIsYours` → `originalIsInYourWidgets`.
- `WidgetsViewModel.indexOfWidget` → `indexOfLoaded`.
- `WidgetsViewModel.widgetAt` → `loadedAt`.
- `WidgetsViewModel.reconcileIsYoursForDraggedWidget` keeps its name; its body uses `toggleIsInYourWidgets`.

### Build configuration

`gradle/libs.versions.toml`:

```toml
[versions]
arrow = "1.2.4"

[libraries]
arrow-core = { group = "io.arrow-kt", name = "arrow-core", version.ref = "arrow" }
```

`app/build.gradle.kts` adds `implementation(libs.arrow.core)`.

## What stays unchanged

- **Drag/drop semantics** — commit-on-release everywhere, section-aware reorder rules, direction-aware insert during hover.
- **Drop target shapes** — `WidgetCard` / `HeaderCell` (Yours) / `HeaderCell` (Other) / `EmptyDropZone`. All four use `rememberDropTarget(...)` + `::acceptPlainText`.
- **Drag visualization** — system drag shadow via `cardLayer = rememberGraphicsLayer()`, `drawWithContent { record + skip-when-dragging }`, `dragAndDropSource(drawDragDecoration = { drawLayer(cardLayer) })`. `animateDpAsState` elevation + `animateFloatAsState` scale on source slot.
- **Long-press detection** — `detectShortLongPress(pointerId, 200ms)` with `PointerEventTimeoutCancellationException` catch.
- **Edge gesture handling** — `Modifier.systemGestureExclusion()` on the `LazyVerticalGrid`.
- **Reconciliation helpers** — `reconcileEmptyPlaceholders()` and `reconcileIsYoursForDraggedWidget()` keep their structure; the latter swaps `widget.copy(isYours = ...)` for `loaded.toggleIsInYourWidgets(...)`.
- **Header/empty placeholder logic** — section anchor constants, tail-first insertion order in reconcile, `YOURS_HEADER_KEY` resolves to `+1` in `onDragHover`.
- **ViewModel encapsulation** — private backing fields, `Snapshot.withMutableSnapshot` for atomic mutations.
- **The `onDragCancel` no-op surface** — kept per CLAUDE.md.

## Verification

Manual on-device testing after install:

1. **Loading → Loaded happy path.** Launch the app with `FAIL_ELIGIBILITY = false`. The grid shows 6 skeletons (4 small + 2 full) for ~500ms, then transitions to the loaded grid with two section headers and 6 typed widgets. No layout reflow surprises.
2. **Eligibility failure path.** Flip `FAIL_ELIGIBILITY = true` and re-install. The error screen renders after the eligibility delay; no skeletons remain.
3. **Each `Loaded` subtype renders with its debug label.** Verify `Investment · iep1`, `PFM · pfm1`, `Monizze · m1`, `Monizze · m2`, `Cashback · c1`, `Pluxee · p1` all visible and visually distinguishable.
4. **All four drag scenarios still work** (regression check):
   - Yours → Yours reorder
   - Other → Yours transfer (commits at drop position)
   - Yours → Other (snap to top of Other)
   - Other → Other (reverts to original index)
5. **Toggle button (`+`/`−`) preserves type.** Press `−` on `Monizze · m1` (Yours) — it appears at the top of Other as a `Monizze`, not a generic `Loaded`. Press `+` on the `Pfm · pfm1` (Other) — it appears at the top of Yours as a `Pfm`.
6. **Skeletons and failure cells are not draggable.** With `FAIL_ELIGIBILITY = false` but a long enough simulated delay, attempt to long-press a skeleton — no drag starts. (Failure cells are not yet produced by the fake; manual injection of a `Failure` into `INITIAL_WIDGETS` validates the same.)

## Out of scope

- Retry button on the error screen (v2 — eligibility re-fetch is not yet specified).
- Real eligibility API call — fake-only for this milestone.
- Real per-tile presentation fields and styled rendering — `debugLabel` is the placeholder.
- Hilt or multi-module split — single module with `viewModelFactory`.
- Per-tile `Failure` cells appearing inside an otherwise-loaded grid — the `WidgetState.Failure` type exists for it, but no use case path produces it yet. Drag predicate already excludes it, so behavior is correct on day one.
- Animating loading→loaded transition (skeleton-to-card crossfade). Plain swap for now.
- Skeleton shimmer animation. Uniform fill is enough for v1.
