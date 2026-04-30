# DragDrop — Widget Dashboard POC

Proof-of-concept Android app: a draggable widget dashboard with two sections ("Your widgets" / "Other widgets") supporting reorder, cross-section transfer (drag or button), and mixed-span items inside a single `LazyVerticalGrid`.

## Hard constraint: native Compose drag-and-drop only

Use `Modifier.dragAndDropSource` / `Modifier.dragAndDropTarget` (`androidx.compose.foundation.draganddrop.*`).

**Do NOT introduce `Calvin-LL/Reorderable` or any other third-party reordering library.** A confirmed library bug ([Calvin-LL/Reorderable#93](https://github.com/Calvin-LL/Reorderable/issues/93)) causes flicker on mixed-span items in `LazyVerticalGrid`. Native Compose D&D avoids it because target detection is independent of item geometry.

## Architecture

- `WidgetsViewModel` owns all mutation logic. Composable is a pure renderer.
- Grid is a `SnapshotStateList<GridEntry>` with three variants: `Header`, `Item(widget)`, `Empty(message)`. Each has a stable `key`.
- `isYours` is **derived from list position relative to the Available header** — never stored independently. `reconcileIsYoursForDraggedWidget` runs after each drag move.
- Drop semantics: **commit-on-release everywhere** — releasing outside any drop target keeps the widget at its current visual position, it does not snap back to origin. This intentionally deviates from the original POC spec (which said "release outside target = cancel") to eliminate dead-zone gaps between section headers and the first item below them. See `docs/superpowers/specs/2026-04-30-commit-on-release-drag-design.md`.
- Empty placeholders are dynamic — `reconcileEmptyPlaceholders` strips and re-adds them after every mutation.
- Drag lifecycle: source slot stays in the list during drag, rendered at `alpha 0`. Drop targets are attached to **items, empty placeholders, and headers**. `onDragHover` resolves the target to the target's original index in `entries` and applies a single `removeAt` + `add` — the math gives direction-aware UX (drag down → land *after* target; drag up → land *before* target). `onEnded` with `dragState != null` calls `onDragCommit` (commit-on-release; see Drop semantics above). `WidgetsViewModel.onDragCancel` is retained as unused surface area for a possible future explicit-cancel gesture. See `docs/superpowers/specs/2026-04-30-direction-aware-drag-and-header-targets-design.md`.

## Stack

- AGP 9.0.0-beta03, Gradle 9.1.0 (built-in Kotlin support — do **not** apply `org.jetbrains.kotlin.android` plugin, it conflicts with the AGP-provided `kotlin` extension).
- Kotlin Compose Compiler plugin: `org.jetbrains.kotlin.plugin.compose` (matches embedded Kotlin).
- Compose BOM 2025.01.00, Material 3, lifecycle 2.8.7.
- minSdk 26, targetSdk/compileSdk 36, JVM target 11.

## Files

- `app/src/main/java/com/arthlem/dragdrop/MainActivity.kt` — entry point, sets `WidgetsScreen` as content under `MaterialTheme`.
- `app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt` — state machine, mutation functions, reconciliation helpers.
- `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` — `WidgetsScreen` / `WidgetsContent` / `HeaderCell` / `WidgetCard` / `EmptyDropZone`.

## Build

```sh
./gradlew assembleDebug
```
