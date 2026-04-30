# Direction-aware drag insert + header drop targets

**Date:** 2026-04-30
**Status:** approved
**Affected files:** `app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt`, `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt`, `CLAUDE.md`

## Problem

After the commit-on-release change shipped, two related limitations in the insert logic surfaced:

1. **Cannot drop a widget *after* the last item of a section when the next entry is a header.** The Other-widgets header has no `dragAndDropTarget`, and the only way to insert "after Analyse de budget" was via something below it — there's nothing.
2. **Cannot easily move a half-span past an adjacent full-span.** Hovering Widget 2 onto Widget 0 was a no-op because my insert logic always lands the dragged widget *before* the target; "before Widget 0" was the same position Widget 2 already occupied.

The two are the same underlying issue surfacing differently:
- `onDragHover` always inserts at the target's index in the workingList (= "insert before target").
- Headers, where they would matter most as drop targets, weren't drop targets at all.

## Decision

**A. Direction-aware insert.** `onDragHover` resolves to the target's *original* index in `entries`, not its index after the dragged widget is removed. The math gives standard reorder UX:
- Drag down (`currentIdx < targetIdx`): widget lands *at* target's old position; target shifts up.
- Drag up (`currentIdx > targetIdx`): widget takes target's slot; target shifts down.

**B. Headers as drop targets.** `HeaderCell` gets `dragAndDropTarget`. Insert resolution:
- `YOURS_HEADER_KEY` → insert at `yours_header_idx + 1` (start of Yours). The yours header is the top of the grid; "before it" is meaningless.
- `AVAILABLE_HEADER_KEY` → insert at the available header's own index. Direction-aware rule then takes over: dragging *down* toward the available header lands the widget at end-of-Yours (just before the header); dragging *up* across it lands just below.

These two changes together resolve both reported scenarios.

## Implementation

**`WidgetsViewModel.onDragHover`** — replace the workingList computation with a direct lookup of the target's index in `entries`. New rule:

```kotlin
val targetIdxInEntries = when (targetKey) {
    YOURS_HEADER_KEY -> {
        val h = entries.indexOfFirst { it is GridEntry.Header && it.key == YOURS_HEADER_KEY }
        if (h < 0) return else h + 1
    }
    AVAILABLE_HEADER_KEY -> entries.indexOfFirst {
        it is GridEntry.Header && it.key == AVAILABLE_HEADER_KEY
    }
    YOURS_EMPTY_KEY, AVAILABLE_EMPTY_KEY -> entries.indexOfFirst { it.key == targetKey }
    else -> entries.indexOfFirst { it.key == targetKey }
}
if (targetIdxInEntries < 0) return
if (targetIdxInEntries == currentIdx) return  // no-op (rare)

entries.removeAt(currentIdx)
val safeIdx = targetIdxInEntries.coerceIn(0, entries.size)
entries.add(safeIdx, GridEntry.Item(state.draggedWidget))
```

The trick that makes this direction-aware: when `currentIdx < targetIdxInEntries`, removing the widget shifts target up by 1. Adding at `targetIdxInEntries` (target's *original* idx) places the widget *after* the now-shifted target. When `currentIdx > targetIdxInEntries`, removal doesn't affect target. Adding at `targetIdxInEntries` places the widget *before* target. Both cases handled by one line.

**`WidgetsScreen.HeaderCell`** — accept `headerKey: String`, `isDragActive: Boolean`, and four callbacks (`onHover`, `onDrop`, `onEnded`). Wrap the existing `Text` in a `Box` with `Modifier.dragAndDropTarget(...)`. Use the same `rememberUpdatedState` + anonymous `DragAndDropTarget` pattern already used for `WidgetCard` and `EmptyDropZone`.

**`WidgetsContent`** — wire each `HeaderCell` instance with the appropriate key and callbacks (`viewModel.onDragHover(entry.key)`, `viewModel.onDragCommit()`, `if (dragState != null) viewModel.onDragCommit()`).

**`CLAUDE.md`** — update the Architecture section's drag-lifecycle bullet to say drop targets include both items and headers, and note the direction-aware insert rule. Add a one-line pointer to this design doc.

## What stays

- Commit-on-release semantics (previous spec) are unchanged. Both `onEnded` callbacks still call `onDragCommit`.
- `onDragCancel` remains in the ViewModel as unused surface area.
- Empty placeholder reconciliation is untouched.
- `isYours` reconciliation is untouched.

## Verification (manual, on-device)

After install:
1. Drag Widget 2 from Other-widgets onto the Other-widgets header (or upward into the gap above it). With Widget 2 dragged from below the avail-header, this is a drag-up: widget lands *just below* avail-header (start of Other). With Widget 2 dragged from Yours: drag-down, widget lands *just above* avail-header (end of Yours). Both directions work.
2. Drag Widget 2 down onto Widget 0. Expected: Widget 2 lands *after* Widget 0 (between Widget 0 and Widget 3); Widget 2 and Widget 3 share a row.
3. Drag Widget 2 down past Widget 0 onto Widget 3. Expected: Widget 2 lands *after* Widget 3 (Widget 2 col 1, Widget 3 col 0 on same row).
4. Drag Widget 2 from Other onto Analyse de budget (drag-up). Expected: Widget 2 lands *before* Analyse de budget (above it in Yours, sharing a row with Widget 1).
5. Drag Widget 2 from Other onto the available-widgets header (drag-up). Expected: lands at start of Other (just below the header) — this is the "drop at top of section" path.
6. Drag a widget onto the Yours header. Expected: lands at start of Yours.
7. Tap `+`/`−` on any widget: still moves to top of other section (regression on `onTransfer`).
8. Long-press without moving; release: stays at origin.

## Out of scope

- Computing screen-position-relative inserts (e.g., "before vs after based on which half of the target the finger is on"). The drag direction is a sufficient proxy for POC.
- Adding a "trailing drop zone" below the last item of the last section. Not needed — direction-aware drag onto the last item lands the widget *after* it.
