# Commit-on-release drag behavior

**Date:** 2026-04-30
**Status:** approved
**Affected files:** `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt`, `CLAUDE.md`

## Problem

When the user releases their finger in the gap *between* a section header and the first widget below it (e.g., between the "Other widgets" header and a full-span widget like "Analyse de budget"), the drop is canceled and the widget snaps back to its original position. This happens even though the user's prior hover on the full-span widget already moved the dragged item into the visually correct slot.

Root cause: the gap between header and first item isn't covered by any drop target. Headers have no `dragAndDropTarget` modifier; the first item's bounds end before the gap begins. When the user releases there:

1. No `onDrop` fires (release wasn't on a target).
2. `onEnded` fires on every drop target the drag interacted with.
3. The current `onEnded` handler treats "still in drag state when ended" as cancellation, calling `onDragCancel`, which restores the widget to `originalIndex`.

The same dead-zone problem exists at row gaps, grid edges, and outside the grid — anywhere not directly on an item or empty placeholder.

## Decision

Adopt commit-on-release semantics (option B): wherever the dragged widget visually sits at release time *is* where it lands. This matches the WYSIWYG model that reorderable libraries use and was explicitly the user's preference.

This deviates from the original POC spec, which said "release outside any valid target → drop is cancelled." The deviation is intentional: the spec's behavior produces a poor UX in practice because dead zones are common and users don't know to avoid them.

Trade-off accepted: no built-in "release outside to cancel" gesture. To abort a drag mid-flight, the user drags back toward origin and releases.

## Implementation

Replace `onDragCancel()` with `onDragCommit()` inside the two `onEnded` callbacks in `WidgetsScreen.kt` — one for `WidgetCard`'s drop target, one for `EmptyDropZone`'s drop target. The `if (viewModel.dragState.value != null)` guard stays unchanged.

```kotlin
// before
onEnded = {
    if (viewModel.dragState.value != null) viewModel.onDragCancel()
}

// after
onEnded = {
    if (viewModel.dragState.value != null) viewModel.onDragCommit()
}
```

### Why the guard stays

Compose fires `onEnded` on every drop target the drag interacted with. The guard ensures only the first one commits; `onDragCommit` clears `dragState`, so subsequent `onEnded` calls see null and become no-ops. Idempotent.

### Why `onDragCommit` already does the right thing

Throughout the drag, the dragged widget remains in `entries` at its current visual position (rendered with `alpha 0` to make the source slot appear empty while the system drag shadow shows the captured graphics layer). `onDragHover` calls move it across positions during drag. `onDragCommit` just clears `dragState` and runs `reconcileEmptyPlaceholders`. No widget repositioning happens at commit time — it lands wherever it visually sits.

### What stays

`WidgetsViewModel.onDragCancel` is not removed. It becomes unused-but-cheap surface area, kept in case we later add an explicit cancel mechanism (drag-out-of-grid zone, back gesture, etc.). Removing now is YAGNI cleanup that creates churn if we want it back.

`CLAUDE.md` gets one line noting the deviation from the original spec.

## Verification

Manual on-device testing after install:

- Drag a widget across to the other section, release in the gap between header and first item → widget stays in the section it was dragged into. (The reported bug.)
- Drag a widget directly onto another widget and release → that target's `onDrop` wins, widget lands there. (Existing happy path still works.)
- Long-press but don't move the finger; release → widget stays at origin. No movement during drag means current position equals origin, so commit is a visual no-op.
- Drag, then release outside the grid entirely → widget stays at last hovered position.

## Out of scope

- Adding headers as drop targets. Commit-on-release alone fixes the reported bug; header drop targets are a separate UX improvement (snap-to-section-top on header hover) that can be added later if needed.
- Removing `onDragCancel` from the ViewModel API.
- Changing the `dragState != null` guard semantics.
