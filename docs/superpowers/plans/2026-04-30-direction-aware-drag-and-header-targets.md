# Direction-aware Drag + Header Drop Targets Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make widgets droppable into every position the user expects — including after the last item of a section and adjacent to a full-span widget — by switching `onDragHover` to direction-aware insert and adding `dragAndDropTarget` to header cells.

**Architecture:** Two coordinated edits. (1) `WidgetsViewModel.onDragHover` resolves to the target's *original* `entries` index (not the workingList index), making remove+insert produce direction-aware UX out of the math. (2) `HeaderCell` becomes a drop target wired with the same callback shape as `WidgetCard` and `EmptyDropZone`, with `YOURS_HEADER_KEY` resolving to "start of Yours" and `AVAILABLE_HEADER_KEY` resolving to the header's own index (direction-aware rule then handles end-of-Yours vs start-of-Other).

**Tech Stack:** Jetpack Compose Foundation drag-and-drop, Kotlin.

**Project caveat:** Not a git repo. "Save the file" replaces "git commit." Verification is build success plus on-device manual testing — no automated UI tests in this codebase.

**Spec:** `docs/superpowers/specs/2026-04-30-direction-aware-drag-and-header-targets-design.md`

---

## File Structure

| File | Change |
|------|--------|
| `app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt` | Replace the body of `onDragHover` with direction-aware logic (no workingList copy). |
| `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` | Convert `HeaderCell` into a drop-target composable (new params + `dragAndDropTarget` modifier). Wire its instance in `WidgetsContent`. |
| `CLAUDE.md` | Update the drag-lifecycle Architecture bullet; add a pointer to the new design doc. |

---

## Task 1: Direction-aware `onDragHover` in the ViewModel

**Files:**
- Modify: `app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt` — replace the `onDragHover` function body.

- [ ] **Step 1: Replace the function body**

Locate `fun onDragHover(targetKey: String)` in `WidgetsViewModel.kt`. Replace its entire body with the version below. The signature does not change.

```kotlin
fun onDragHover(targetKey: String) {
    val state = _dragState.value ?: return
    val widgetId = state.draggedWidget.id
    if (targetKey == widgetId) return

    val currentIdx = indexOfWidget(widgetId)
    if (currentIdx < 0) return

    val targetIdxInEntries = when (targetKey) {
        YOURS_HEADER_KEY -> {
            val h = entries.indexOfFirst { it is GridEntry.Header && it.key == YOURS_HEADER_KEY }
            if (h < 0) return
            h + 1
        }
        AVAILABLE_HEADER_KEY -> entries.indexOfFirst {
            it is GridEntry.Header && it.key == AVAILABLE_HEADER_KEY
        }
        YOURS_EMPTY_KEY, AVAILABLE_EMPTY_KEY -> entries.indexOfFirst { it.key == targetKey }
        else -> entries.indexOfFirst { it.key == targetKey }
    }
    if (targetIdxInEntries < 0) return
    if (targetIdxInEntries == currentIdx) return

    entries.removeAt(currentIdx)
    val safeIdx = targetIdxInEntries.coerceIn(0, entries.size)
    entries.add(safeIdx, GridEntry.Item(state.draggedWidget))
    reconcileIsYoursForDraggedWidget(widgetId)
    reconcileEmptyPlaceholders()
}
```

Why this is direction-aware:
- `currentIdx < targetIdxInEntries` (drag down): `removeAt(currentIdx)` shifts target up by 1 — adding at `targetIdxInEntries` lands the widget *after* target.
- `currentIdx > targetIdxInEntries` (drag up): `removeAt(currentIdx)` doesn't affect target — adding at `targetIdxInEntries` lands the widget *before* target.

- [ ] **Step 2: Compile**

```bash
./gradlew compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`. No new warnings.

---

## Task 2: `HeaderCell` becomes a drop target

**Files:**
- Modify: `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` — replace the `HeaderCell` composable, then update its call site in `WidgetsContent`.

- [ ] **Step 1: Replace `HeaderCell`**

Find the existing `HeaderCell` (currently a one-liner that just renders `Text`). Replace the entire composable with the version below. New parameters: `isDragActive`, `onHover`, `onDrop`, `onEnded`. The same `rememberUpdatedState` + anonymous `DragAndDropTarget` pattern as `WidgetCard`/`EmptyDropZone` keeps the target stable across recompositions.

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyGridItemScope.HeaderCell(
    title: String,
    headerKey: String,
    isDragActive: Boolean,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnHover by rememberUpdatedState(onHover)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnEnded by rememberUpdatedState(onEnded)

    val dropTarget = remember(headerKey) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { currentOnHover() }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentOnDrop()
                return true
            }
            override fun onEnded(event: DragAndDropEvent) { currentOnEnded() }
        }
    }

    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)
                },
                target = dropTarget,
            ),
    )
}
```

Note: `isDragActive` is currently unused — included so the signature matches the other targets and so we can later add an active-drop visual treatment without re-threading args. Keeping it now is one parameter of dead surface; reflows are uglier than dead params for this kind of POC iteration.

- [ ] **Step 2: Update the `HeaderCell` call site in `WidgetsContent`**

Find the `is GridEntry.Header -> HeaderCell(...)` branch in the `items { ... }` block of `WidgetsContent`. Replace it with:

```kotlin
is GridEntry.Header -> HeaderCell(
    title = entry.title,
    headerKey = entry.key,
    isDragActive = dragState != null,
    onHover = { viewModel.onDragHover(entry.key) },
    onDrop = { viewModel.onDragCommit() },
    onEnded = {
        if (viewModel.dragState.value != null) viewModel.onDragCommit()
    },
    modifier = Modifier.animateItem(),
)
```

This wires the header to the same callbacks as the item and empty-placeholder targets, including the commit-on-release `onEnded` from the previous fix.

- [ ] **Step 3: Compile**

```bash
./gradlew compileDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

---

## Task 3: Update `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md` — Architecture section.

- [ ] **Step 1: Update the drag-lifecycle bullet**

In the Architecture section, find the bullet starting `Drag lifecycle: source slot stays...` and replace it with:

```markdown
- Drag lifecycle: source slot stays in the list during drag, rendered at `alpha 0`. Drop targets are attached to **items, empty placeholders, and headers**. `onDragHover` resolves the target to the target's original index in `entries` and applies a single `removeAt` + `add` — the math gives direction-aware UX (drag down → land *after* target; drag up → land *before* target). `onEnded` with `dragState != null` calls `onDragCommit` (commit-on-release). `WidgetsViewModel.onDragCancel` is retained as unused surface area for a possible future explicit-cancel gesture. See `docs/superpowers/specs/2026-04-30-direction-aware-drag-and-header-targets-design.md`.
```

- [ ] **Step 2: Save the file**

(No git commit — repo not initialized.)

---

## Task 4: Build and on-device verification

- [ ] **Step 1: Assemble**

```bash
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Fresh APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Install**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 3: Walk the spec verification list**

Run the eight checks from the spec § Verification:

1. Drag Widget 2 from Other-widgets onto/across the Other-widgets header. *Drag-up* lands just below the header (start of Other). *Drag-down* (from Yours) lands just before the header (end of Yours).
2. Drag Widget 2 down onto Widget 0 → Widget 2 lands *after* Widget 0; Widget 2 and Widget 3 share a row.
3. Drag Widget 2 down past Widget 0 onto Widget 3 → Widget 2 lands *after* Widget 3.
4. Drag Widget 2 (from Other) up onto Analyse de budget → Widget 2 lands *before* Analyse de budget.
5. Drag Widget 2 from Other onto the available-widgets header (drag-up) → start of Other.
6. Drag a widget onto the Yours header → start of Yours.
7. Tap `+`/`−` on any widget → moves to top of other section. (`onTransfer` regression check.)
8. Long-press, no movement, release → stays at origin.

Report any failure with: which step, what happened, what was expected.

---

## Self-Review

- **Spec coverage:** § Decision item A → Task 1. § Decision item B → Task 2. § Implementation → Tasks 1 & 2. § What stays → enforced by omission (no task touches commit-on-release, `onDragCancel`, reconciliation). § Verification → Task 4. ✓
- **Placeholder scan:** No "TBD"/"TODO"; full code blocks for every code change. ✓
- **Type consistency:** New `HeaderCell` parameters are all referenced by the call site at the exact names declared. The `entry.key` value passed as `headerKey` is `GridEntry.Header.key: String`, matching the parameter type. ✓
- **Scope:** One coherent behavior change in two files. Single plan correct. ✓
- **Ambiguity:** Insert math is explicit; the "no-op skip" remains for `targetIdxInEntries == currentIdx` (rare with new logic). ✓
