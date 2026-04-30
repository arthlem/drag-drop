# Commit-on-release Drag Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Switch the widget dashboard's drag-and-drop semantics from "release-outside-target = cancel" to "release-anywhere = commit at current visual position", fixing the dead-zone bug between section headers and full-span items.

**Architecture:** Two `onEnded` callbacks in `WidgetsScreen.kt` swap their terminal call from `viewModel.onDragCancel()` to `viewModel.onDragCommit()`. The existing `dragState != null` guard remains and continues to ensure idempotency across multiple `onEnded` events fired by Compose. No ViewModel changes; `onDragCancel` stays as unused-but-cheap surface area.

**Tech Stack:** Jetpack Compose Foundation drag-and-drop (`Modifier.dragAndDropTarget`), Compose runtime state (`mutableStateOf`).

**Project caveat:** This working directory is not a git repository (per session context). The standard plan format includes `git add` / `git commit` steps; here, replace those with "save the file" — verification is the build passing and on-device manual testing. No automated UI tests exist in this codebase; verification is manual.

**Spec:** `docs/superpowers/specs/2026-04-30-commit-on-release-drag-design.md`

---

## File Structure

| File | Change |
|------|--------|
| `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` | Modify two `onEnded` lambdas in `WidgetsContent` (the `EmptyDropZone` instance and the `WidgetCard` instance). |
| `CLAUDE.md` | Add one bullet noting the deviation from the original POC spec. |

No new files. No deletions. The `WidgetsViewModel.onDragCancel` function is intentionally retained.

---

## Task 1: Switch `onEnded` to commit-on-release

**Files:**
- Modify: `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` (the `WidgetsContent` composable, both `onEnded =` lambdas in the `items { ... }` block — one inside the `is GridEntry.Empty` branch and one inside the `is GridEntry.Item` branch)

- [ ] **Step 1: Locate the two `onEnded` lambdas**

Open `WidgetsScreen.kt`. Inside `WidgetsContent`, the `LazyVerticalGrid` `items` lambda has a `when (entry)` block. Find these two lambdas (current state — both occurrences are identical):

```kotlin
onEnded = {
    if (viewModel.dragState.value != null) viewModel.onDragCancel()
},
```

One sits inside the `is GridEntry.Empty -> EmptyDropZone(...)` argument list, the other inside `is GridEntry.Item -> WidgetCard(...)`.

- [ ] **Step 2: Apply the swap to both lambdas**

Replace `viewModel.onDragCancel()` with `viewModel.onDragCommit()` in both lambdas. After the change, both should read:

```kotlin
onEnded = {
    if (viewModel.dragState.value != null) viewModel.onDragCommit()
},
```

Use `replace_all = true` if the editor supports it, or apply the same edit twice. The `if` guard is preserved exactly — it ensures only the first `onEnded` call to fire actually commits; subsequent ones see `dragState == null` and become no-ops.

- [ ] **Step 3: Verify the file compiles**

Run from the project root:

```bash
./gradlew compileDebugKotlin
```

Expected output: `BUILD SUCCESSFUL`. No new warnings or errors.

- [ ] **Step 4: Save the file**

(No git commit — repo not initialized. The edit is the commit equivalent here.)

---

## Task 2: Verify the APK assembles

**Files:** none (build verification only)

- [ ] **Step 1: Run the full debug build**

```bash
./gradlew assembleDebug
```

Expected output: `BUILD SUCCESSFUL`. The APK should land at:

```
app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Confirm APK exists and is fresh**

```bash
ls -la app/build/outputs/apk/debug/app-debug.apk
```

Expected: file present, mtime within the last few minutes.

---

## Task 3: Update `CLAUDE.md` with the design note

**Files:**
- Modify: `CLAUDE.md` (the `## Architecture` section)

- [ ] **Step 1: Add the deviation note**

Find the "Architecture" section in `CLAUDE.md`. After the existing bullet about `isYours` derivation, add a new bullet describing the commit-on-release deviation. The complete bullet to add:

```markdown
- Drop semantics: **commit-on-release everywhere** — releasing outside any drop target keeps the widget at its current visual position, it does not snap back to origin. This intentionally deviates from the original POC spec (which said "release outside target = cancel") to eliminate dead-zone gaps between section headers and the first item below them. See `docs/superpowers/specs/2026-04-30-commit-on-release-drag-design.md`.
```

Place it immediately after the existing `isYours` bullet so the section reads in logical order: how state is structured → how drops are committed.

- [ ] **Step 2: Save the file**

(No git commit — repo not initialized.)

---

## Task 4: Manual on-device verification

**Files:** none (this is the user's testing checklist)

The change is a behavioral one in a Compose UI gesture flow. There is no automated UI test infrastructure in this project. Verification is manual on a device or emulator running the freshly assembled APK.

- [ ] **Step 1: Install the new APK on a device or emulator**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`.

- [ ] **Step 2: Launch and walk the testing checklist**

Open the app. Wait for the loading spinner to clear (~500 ms). Then:

1. **The reported bug:** drag a half-span widget from "Your widgets" to the gap between the "Other widgets" header and the full-span widget below it. Release in the gap. *Expected:* widget stays at the position it visually held during drag (top of "Other widgets", just above the full-span widget). *Old behavior (regression check):* widget would have snapped back to origin.

2. **Happy-path drop on a target:** drag a widget directly onto another widget and release. *Expected:* dragged widget lands at the position immediately before the target.

3. **No-movement release:** long-press a widget, then release without moving the finger. *Expected:* widget stays at origin (no movement during drag means no visible change). No crash, no flicker.

4. **Off-grid release:** drag a widget off the side of the grid (outside the grid bounds entirely) and release. *Expected:* widget stays at the last position the system reported a hover for. No snap-back.

5. **Button transfer regression:** tap the `+`/`−` icon on any widget. *Expected:* widget moves to the top of the other section. (Confirms the `onTransfer` path was untouched.)

- [ ] **Step 3: Report any failures**

If any step fails, capture: which step, what happened, what was expected. The most likely failure modes:

- Widget snaps back to origin on gap release → the `onEnded` swap didn't take. Re-check Task 1.
- Crash on drop → `onDragCommit` is being called twice somehow; check the guard.
- Widget vanishes → reconciliation is removing the widget; check `reconcileEmptyPlaceholders` wasn't accidentally edited.

---

## Self-Review

**Spec coverage:**
- Spec § "Decision" → Task 1 (the swap) and Task 3 (CLAUDE.md note). ✓
- Spec § "Implementation" → Task 1 covers both `onEnded` callbacks (`WidgetCard` and `EmptyDropZone`), guard preserved. ✓
- Spec § "What stays" (`onDragCancel` retained) → no task, no change required. The retention is enforced by *omission* — no task touches `WidgetsViewModel.kt`. ✓
- Spec § "Verification" → Task 4 covers all four checklist items from the spec, plus a button-transfer regression check. ✓

**Placeholder scan:** No "TBD"/"TODO"/"fill in later". All steps include exact code or exact commands. ✓

**Type consistency:** `viewModel.onDragCommit()` is the existing public function on `WidgetsViewModel` (defined in `WidgetsViewModel.kt`); signature `fun onDragCommit()`. The replacement matches. ✓

**Scope check:** Single behavior change, two-line code edit, one doc note. Single plan is correct. ✓

**Ambiguity:** "Both `onEnded` lambdas" is precise — there are exactly two in `WidgetsContent`. The replacement string is the same for both. ✓
