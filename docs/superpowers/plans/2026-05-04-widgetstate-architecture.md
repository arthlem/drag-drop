# WidgetState architecture migration

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat `Widget` data class with a sealed `WidgetState` hierarchy (`Skeleton` / `Failure` / `Loaded`), introduce a `WidgetsUseCase` returning `Either<Throwable, Flow<List<WidgetState>>>` for eligibility-aware loading, and route loading/error/loaded states through the same drag-and-drop grid.

**Architecture:** `WidgetsUseCase` (interface + `FakeWidgetsUseCase`) is constructor-injected into `WidgetsViewModel` via Compose's `viewModelFactory`. `_entries` is seeded with 6 skeletons immediately, then replaced atomically once the eligibility-checked flow emits. `Loaded` widgets carry `id`/`size`/`isInYourWidgets` and a polymorphic `toggleIsInYourWidgets` that returns the same concrete subtype, so reorder logic preserves widget type without `when`-branching.

**Tech Stack:** Kotlin 2.1.20, Compose BOM 2025.01.00, Material 3, lifecycle 2.8.7, Arrow Core 1.2.4, AGP 9.0.0-beta03.

**Source spec:** `docs/superpowers/specs/2026-05-04-widgetstate-architecture-design.md`

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `gradle/libs.versions.toml` | modify | Adds `arrow` version + `arrow-core` library entry |
| `app/build.gradle.kts` | modify | Adds `implementation(libs.arrow.core)` |
| `app/src/main/java/com/arthlem/dragdrop/WidgetState.kt` | **create** | `enum WidgetSize`, sealed `WidgetState` hierarchy with `Loaded` subtypes |
| `app/src/main/java/com/arthlem/dragdrop/WidgetsUseCase.kt` | **create** | `WidgetsUseCase` interface + `FakeWidgetsUseCase` returning `Either<Throwable, Flow<List<WidgetState>>>` |
| `app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt` | rewrite | `GridEntry.Cell(state)`, `UiState.{Loading, Error, Loaded}`, skeleton seed, flow collection, deferred-emission logic, `toggleIsInYourWidgets` polymorphism |
| `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` | rewrite | Skeleton/Failure rendering paths, `ErrorScreen`, span branching on `WidgetState`, `debugLabel`, drag modifiers only on `Loaded` cells |
| `app/src/main/java/com/arthlem/dragdrop/MainActivity.kt` | modify | `viewModelFactory` wiring `FakeWidgetsUseCase` into `WidgetsViewModel` |
| `CLAUDE.md` | modify | Architecture description updated to reflect WidgetState/UseCase/Loading paths |

The old `Widget` data class is deleted as part of the `WidgetsViewModel.kt` rewrite.

---

## Notes for the implementing engineer

- This project uses AGP 9.0.0-beta03 with **built-in Kotlin support**. Do not apply `org.jetbrains.kotlin.android` — only `org.jetbrains.kotlin.plugin.compose` is in `plugins {}`.
- Tasks 4–6 (VM + Screen + MainActivity migration) **must be done in a single commit**. Between them the project will not compile, because the `Widget` type is removed from the VM and re-added (as `WidgetState.Loaded`) for the Screen to consume — the intermediate state has unresolved references in both files. Skip the build step at the end of Task 4 and Task 5; build only at the end of Task 6.
- The repo is currently not a git repository per the environment header. If `git status` fails, skip the commit steps; otherwise run them as written.
- All builds in this plan use `./gradlew assembleDebug` (full APK build). For tighter inner-loop iteration during a step, `./gradlew :app:compileDebugKotlin` runs faster but does not validate resources or AndroidManifest.

---

## Task 1: Add Arrow dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add Arrow version and library entry to the version catalog**

Edit `gradle/libs.versions.toml`. Under `[versions]`, after the `lifecycle = "2.8.7"` line, add:

```toml
arrow = "1.2.4"
```

Under `[libraries]`, after the `androidx-compose-material-icons-extended = ...` line, add:

```toml
arrow-core = { group = "io.arrow-kt", name = "arrow-core", version.ref = "arrow" }
```

- [ ] **Step 2: Reference Arrow in the app module**

Edit `app/build.gradle.kts`. Inside the `dependencies { ... }` block, after the `debugImplementation(libs.androidx.compose.ui.tooling)` line, add:

```kotlin
    implementation(libs.arrow.core)
```

(Indentation matches the surrounding lines — single tab.)

- [ ] **Step 3: Run a build to verify Arrow resolves**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. The first run will download `arrow-core-1.2.4.jar` from Maven Central. No source changes yet, so this only validates dependency resolution.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "chore: add Arrow Core dependency"
```

---

## Task 2: Create `WidgetState.kt`

**Files:**
- Create: `app/src/main/java/com/arthlem/dragdrop/WidgetState.kt`

- [ ] **Step 1: Create the file with the full sealed hierarchy**

Create `app/src/main/java/com/arthlem/dragdrop/WidgetState.kt`:

```kotlin
package com.arthlem.dragdrop

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
            override fun toggleIsInYourWidgets(shouldBeInYourWidgets: Boolean): InvestmentEntryPoint =
                copy(isInYourWidgets = shouldBeInYourWidgets)
        }

        data class Pfm(
            override val id: String,
            override val size: WidgetSize,
            override val isInYourWidgets: Boolean,
        ) : Loaded {
            override fun toggleIsInYourWidgets(shouldBeInYourWidgets: Boolean): Pfm =
                copy(isInYourWidgets = shouldBeInYourWidgets)
        }

        sealed interface Tile : Loaded {
            data class Monizze(
                override val id: String,
                override val size: WidgetSize,
                override val isInYourWidgets: Boolean,
            ) : Tile {
                override fun toggleIsInYourWidgets(shouldBeInYourWidgets: Boolean): Monizze =
                    copy(isInYourWidgets = shouldBeInYourWidgets)
            }

            data class Cashback(
                override val id: String,
                override val size: WidgetSize,
                override val isInYourWidgets: Boolean,
            ) : Tile {
                override fun toggleIsInYourWidgets(shouldBeInYourWidgets: Boolean): Cashback =
                    copy(isInYourWidgets = shouldBeInYourWidgets)
            }

            data class Pluxee(
                override val id: String,
                override val size: WidgetSize,
                override val isInYourWidgets: Boolean,
            ) : Tile {
                override fun toggleIsInYourWidgets(shouldBeInYourWidgets: Boolean): Pluxee =
                    copy(isInYourWidgets = shouldBeInYourWidgets)
            }
        }
    }
}
```

Each concrete `Loaded` subtype overrides `toggleIsInYourWidgets` with its own concrete return type. This is what gives the ViewModel type-preserving toggling without casts.

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. No callers exist yet so this only confirms the type definitions are valid.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arthlem/dragdrop/WidgetState.kt
git commit -m "feat: add WidgetState sealed hierarchy"
```

---

## Task 3: Create `WidgetsUseCase.kt`

**Files:**
- Create: `app/src/main/java/com/arthlem/dragdrop/WidgetsUseCase.kt`

- [ ] **Step 1: Create the file with interface + fake impl**

Create `app/src/main/java/com/arthlem/dragdrop/WidgetsUseCase.kt`:

```kotlin
package com.arthlem.dragdrop

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface WidgetsUseCase {
    suspend fun fetchWidgets(): Either<Throwable, Flow<List<WidgetState>>>
}

class FakeWidgetsUseCase : WidgetsUseCase {

    companion object {
        /** Flip to `true` to verify the error screen on next install. */
        const val FAIL_ELIGIBILITY = false
        private const val ELIGIBILITY_DELAY_MS = 500L

        private val INITIAL_WIDGETS: List<WidgetState> = listOf(
            WidgetState.Loaded.Tile.Monizze(id = "m1", size = WidgetSize.SMALL, isInYourWidgets = true),
            WidgetState.Loaded.Tile.Cashback(id = "c1", size = WidgetSize.SMALL, isInYourWidgets = true),
            WidgetState.Loaded.InvestmentEntryPoint(id = "iep1", size = WidgetSize.FULL, isInYourWidgets = true),
            WidgetState.Loaded.Pfm(id = "pfm1", size = WidgetSize.FULL, isInYourWidgets = false),
            WidgetState.Loaded.Tile.Pluxee(id = "p1", size = WidgetSize.SMALL, isInYourWidgets = false),
            WidgetState.Loaded.Tile.Monizze(id = "m2", size = WidgetSize.SMALL, isInYourWidgets = false),
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

The single `flowOf(INITIAL_WIDGETS)` emits once and completes — matches today's static-data behavior. When the real use case lands, it returns a long-lived flow with live updates; the ViewModel's deferred-emission logic (Task 4) is what makes that work without breaking active drags.

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/arthlem/dragdrop/WidgetsUseCase.kt
git commit -m "feat: add WidgetsUseCase with fake eligibility-aware impl"
```

---

## Task 4: Rewrite `WidgetsViewModel.kt`

**Files:**
- Modify (full rewrite): `app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt`

> **Do not run the build at the end of this task.** The project will have unresolved references in `WidgetsScreen.kt` and `MainActivity.kt` until Tasks 5 and 6 complete. The build verification happens after Task 6.

- [ ] **Step 1: Replace `WidgetsViewModel.kt` with the rewritten file**

Overwrite `app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt`:

```kotlin
package com.arthlem.dragdrop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface GridEntry {
    val key: String

    data class Header(override val key: String, val title: String) : GridEntry
    data class Cell(val state: WidgetState) : GridEntry {
        override val key: String get() = when (state) {
            is WidgetState.Loaded -> state.id
            is WidgetState.Skeleton -> state.key
            is WidgetState.Failure -> state.key
        }
    }
    data class Empty(override val key: String, val message: String) : GridEntry
}

data class DragState(
    val draggedWidget: WidgetState.Loaded,
    val originalIndex: Int,
    val originalIsInYourWidgets: Boolean,
)

sealed interface UiState {
    data object Loading : UiState
    data class Error(val cause: Throwable) : UiState
    data object Loaded : UiState
}

class WidgetsViewModel(
    private val useCase: WidgetsUseCase,
) : ViewModel() {

    companion object {
        const val YOURS_HEADER_KEY = "header_yours"
        const val AVAILABLE_HEADER_KEY = "header_available"
        const val YOURS_EMPTY_KEY = "empty_yours"
        const val AVAILABLE_EMPTY_KEY = "empty_available"

        private const val YOURS_HEADER_TITLE = "Your widgets"
        private const val AVAILABLE_HEADER_TITLE = "Other widgets"
        private const val YOURS_EMPTY_MESSAGE = "Drop a widget here"
        private const val AVAILABLE_EMPTY_MESSAGE = "Nothing available"

        private val SKELETON_SEED: List<WidgetState.Skeleton> = listOf(
            WidgetState.Skeleton("skeleton_0", WidgetSize.SMALL),
            WidgetState.Skeleton("skeleton_1", WidgetSize.SMALL),
            WidgetState.Skeleton("skeleton_2", WidgetSize.FULL),
            WidgetState.Skeleton("skeleton_3", WidgetSize.FULL),
            WidgetState.Skeleton("skeleton_4", WidgetSize.SMALL),
            WidgetState.Skeleton("skeleton_5", WidgetSize.SMALL),
        )
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _entries = mutableStateListOf<GridEntry>()
    val entries: List<GridEntry> get() = _entries

    private val _dragState = mutableStateOf<DragState?>(null)
    val dragState: DragState? get() = _dragState.value

    /** Latest emission deferred while a drag is in progress. Replayed on commit/cancel. */
    private var pendingEntries: List<WidgetState>? = null

    init {
        Snapshot.withMutableSnapshot {
            _entries.addAll(SKELETON_SEED.map { GridEntry.Cell(it) })
        }
        viewModelScope.launch {
            useCase.fetchWidgets().fold(
                ifLeft = ::handleError,
                ifRight = { collectLoadedFlow(it) },
            )
        }
    }

    private fun handleError(cause: Throwable) {
        Snapshot.withMutableSnapshot { _entries.clear() }
        _uiState.value = UiState.Error(cause)
    }

    private suspend fun collectLoadedFlow(flow: Flow<List<WidgetState>>) {
        flow.collect { widgets ->
            if (_dragState.value == null) {
                rebuildEntriesFromWidgets(widgets)
            } else {
                pendingEntries = widgets
            }
            if (_uiState.value != UiState.Loaded) {
                _uiState.value = UiState.Loaded
            }
        }
    }

    private fun rebuildEntriesFromWidgets(widgets: List<WidgetState>) {
        val (yours, other) = widgets
            .filterIsInstance<WidgetState.Loaded>()
            .partition { it.isInYourWidgets }
        Snapshot.withMutableSnapshot {
            _entries.clear()
            _entries.add(GridEntry.Header(YOURS_HEADER_KEY, YOURS_HEADER_TITLE))
            _entries.addAll(yours.map { GridEntry.Cell(it) })
            _entries.add(GridEntry.Header(AVAILABLE_HEADER_KEY, AVAILABLE_HEADER_TITLE))
            _entries.addAll(other.map { GridEntry.Cell(it) })
            reconcileEmptyPlaceholders()
        }
    }

    fun onDragStart(widgetId: String) {
        if (_dragState.value != null) return
        val index = indexOfLoaded(widgetId)
        val widget = loadedAt(index) ?: return
        _dragState.value = DragState(
            draggedWidget = widget,
            originalIndex = index,
            originalIsInYourWidgets = widget.isInYourWidgets,
        )
    }

    fun onDragHover(targetKey: String) {
        val state = _dragState.value ?: return
        val widgetId = state.draggedWidget.id
        if (targetKey == widgetId) return

        val currentIndex = indexOfLoaded(widgetId)
        if (currentIndex < 0) return

        val targetIndex = if (targetKey == YOURS_HEADER_KEY) {
            indexOfKey(YOURS_HEADER_KEY).let { if (it < 0) -1 else it + 1 }
        } else {
            indexOfKey(targetKey)
        }
        if (targetIndex < 0 || targetIndex == currentIndex) return

        Snapshot.withMutableSnapshot {
            val current = loadedAt(currentIndex) ?: return@withMutableSnapshot
            _entries.removeAt(currentIndex)
            val safeIndex = targetIndex.coerceIn(0, _entries.size)
            _entries.add(safeIndex, GridEntry.Cell(current))
            reconcileIsYoursForDraggedWidget(widgetId)
            reconcileEmptyPlaceholders()
        }
    }

    fun onDragCommit() {
        val state = _dragState.value ?: return
        val widgetId = state.draggedWidget.id
        val currentIndex = indexOfLoaded(widgetId)
        val availableHeaderIndex = indexOfKey(AVAILABLE_HEADER_KEY)
        val landedInYours = currentIndex >= 0 &&
            (availableHeaderIndex !in 0..currentIndex)

        when {
            landedInYours -> {
                // Yours → Yours (reorder) or Other → Yours (transfer-in): commit in place.
                _dragState.value = null
            }
            state.originalIsInYourWidgets -> {
                // Yours → Other: whole-section drop zone — snap to top of Other.
                Snapshot.withMutableSnapshot {
                    if (currentIndex >= 0) _entries.removeAt(currentIndex)
                    val anchorIndex = indexOfKey(AVAILABLE_HEADER_KEY)
                    val target = (if (anchorIndex < 0) _entries.size else anchorIndex + 1)
                        .coerceIn(0, _entries.size)
                    val moved = state.draggedWidget.toggleIsInYourWidgets(false)
                    _entries.add(target, GridEntry.Cell(moved))
                    _dragState.value = null
                    reconcileEmptyPlaceholders()
                }
            }
            else -> {
                // Other → Other: no in-section reorder; revert to origin.
                onDragCancel()
                return
            }
        }
        flushPendingEntriesIfAny()
    }

    fun onDragCancel() {
        val state = _dragState.value ?: return
        Snapshot.withMutableSnapshot {
            val widgetId = state.draggedWidget.id
            val currentIndex = indexOfLoaded(widgetId)
            if (currentIndex >= 0) _entries.removeAt(currentIndex)
            val restored = state.draggedWidget.toggleIsInYourWidgets(state.originalIsInYourWidgets)
            val safeOriginal = state.originalIndex.coerceIn(0, _entries.size)
            _entries.add(safeOriginal, GridEntry.Cell(restored))
            _dragState.value = null
            reconcileEmptyPlaceholders()
        }
        flushPendingEntriesIfAny()
    }

    fun onTransfer(widgetId: String) {
        val cellIndex = indexOfLoaded(widgetId)
        val current = loadedAt(cellIndex) ?: return
        val moved = current.toggleIsInYourWidgets(!current.isInYourWidgets)
        val anchorKey = if (moved.isInYourWidgets) YOURS_HEADER_KEY else AVAILABLE_HEADER_KEY
        Snapshot.withMutableSnapshot {
            _entries.removeAt(cellIndex)
            val anchor = indexOfKey(anchorKey)
            val target = (if (anchor < 0) _entries.size else anchor + 1)
                .coerceIn(0, _entries.size)
            _entries.add(target, GridEntry.Cell(moved))
            reconcileEmptyPlaceholders()
        }
    }

    private fun flushPendingEntriesIfAny() {
        val pending = pendingEntries ?: return
        pendingEntries = null
        rebuildEntriesFromWidgets(pending)
    }

    private fun indexOfKey(key: String): Int = _entries.indexOfFirst { it.key == key }

    private fun indexOfLoaded(widgetId: String): Int =
        _entries.indexOfFirst {
            it is GridEntry.Cell && it.state is WidgetState.Loaded && it.state.id == widgetId
        }

    private fun loadedAt(index: Int): WidgetState.Loaded? =
        ((_entries.getOrNull(index) as? GridEntry.Cell)?.state as? WidgetState.Loaded)

    private fun reconcileIsYoursForDraggedWidget(widgetId: String) {
        val index = indexOfLoaded(widgetId)
        val current = loadedAt(index) ?: return
        val availableHeaderIndex = indexOfKey(AVAILABLE_HEADER_KEY)
        val shouldBeInYourWidgets = availableHeaderIndex < 0 || index < availableHeaderIndex
        if (current.isInYourWidgets != shouldBeInYourWidgets) {
            _entries[index] = GridEntry.Cell(current.toggleIsInYourWidgets(shouldBeInYourWidgets))
        }
    }

    private fun reconcileEmptyPlaceholders() {
        _entries.removeAll { it is GridEntry.Empty }

        val yoursHeaderIndex = indexOfKey(YOURS_HEADER_KEY)
        val availableHeaderIndex = indexOfKey(AVAILABLE_HEADER_KEY)

        // Tail-first: appending the available-empty doesn't shift any earlier index,
        // so the yoursHeaderIndex / availableHeaderIndex captured above stay valid for the second insert.
        if (availableHeaderIndex == _entries.size - 1) {
            _entries.add(GridEntry.Empty(AVAILABLE_EMPTY_KEY, AVAILABLE_EMPTY_MESSAGE))
        }
        if (yoursHeaderIndex >= 0 && availableHeaderIndex == yoursHeaderIndex + 1) {
            _entries.add(yoursHeaderIndex + 1, GridEntry.Empty(YOURS_EMPTY_KEY, YOURS_EMPTY_MESSAGE))
        }
    }
}
```

Key behavioral notes for review:

- **Init seeds skeletons synchronously.** The `_entries.addAll(SKELETON_SEED.map { ... })` runs before `viewModelScope.launch { ... }`, so the first frame Compose sees has skeletons.
- **`onDragCommit`'s `else` branch returns early** to skip `flushPendingEntriesIfAny()` — `onDragCancel()` already calls it, and double-flushing would no-op (pendingEntries gets nulled), but the early return makes intent explicit.
- **`pendingEntries` is a plain `var`, not a `MutableState`** — Compose does not need to observe it; only the deferred-replay path reads it.
- **`reconcileIsYoursForDraggedWidget` uses `current.toggleIsInYourWidgets(shouldBeInYourWidgets)`** instead of `widget.copy(isYours = ...)`. The polymorphic toggle returns the same concrete subtype, so a `Monizze` stays a `Monizze` after crossing sections during drag.
- **`onDragCancel`'s restore uses `state.draggedWidget.toggleIsInYourWidgets(state.originalIsInYourWidgets)`** to ensure the restored cell matches the original section's widget state regardless of mid-drag toggles.

- [ ] **Step 2: Do not build yet — proceed to Task 5**

`WidgetsScreen.kt` and `MainActivity.kt` still reference the deleted `Widget` type and the old parameterless `WidgetsViewModel()` constructor. Move directly to Task 5.

---

## Task 5: Rewrite `WidgetsScreen.kt`

**Files:**
- Modify (full rewrite): `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt`

> Build at the end of this task is also skipped — `MainActivity.kt` still calls `WidgetsScreen()` with no factory and the new ViewModel constructor needs one. Build runs after Task 6.

- [ ] **Step 1: Replace `WidgetsScreen.kt` with the rewritten file**

Overwrite `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt`:

```kotlin
@file:OptIn(ExperimentalFoundationApi::class)

package com.arthlem.dragdrop

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val LONG_PRESS_TIMEOUT_MS = 200L

private fun acceptPlainText(event: DragAndDropEvent): Boolean =
    event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)

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

@Composable
fun WidgetsScreen(viewModel: WidgetsViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    when (val state = uiState) {
        UiState.Loading, UiState.Loaded -> WidgetsContent(viewModel)
        is UiState.Error -> ErrorScreen(cause = state.cause)
    }
}

@Composable
private fun WidgetsContent(viewModel: WidgetsViewModel) {
    val entries: List<GridEntry> = viewModel.entries
    val dragState = viewModel.dragState
    val commitIfDragging: () -> Unit = {
        if (viewModel.dragState != null) viewModel.onDragCommit()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .systemGestureExclusion(),
    ) {
        items(
            items = entries,
            key = { it.key },
            span = { entry ->
                when (entry) {
                    is GridEntry.Header, is GridEntry.Empty -> GridItemSpan(maxLineSpan)
                    is GridEntry.Cell -> when (val s = entry.state) {
                        is WidgetState.Loaded -> if (s.size == WidgetSize.FULL) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                        is WidgetState.Skeleton -> if (s.size == WidgetSize.FULL) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                        is WidgetState.Failure -> if (s.size == WidgetSize.FULL) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                    }
                }
            },
            contentType = { entry ->
                when (entry) {
                    is GridEntry.Cell -> entry.state::class
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
                        state = s,
                        isBeingDragged = dragState?.draggedWidget?.id == s.id,
                        onDragStart = { viewModel.onDragStart(s.id) },
                        onHover = { viewModel.onDragHover(entry.key) },
                        onDrop = { viewModel.onDragCommit() },
                        onEnded = commitIfDragging,
                        onTransfer = { viewModel.onTransfer(s.id) },
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

@Composable
private fun LazyGridItemScope.HeaderCell(
    title: String,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dropTarget = rememberDropTarget(onHover, onDrop, onEnded)
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp)
            .dragAndDropTarget(
                shouldStartDragAndDrop = ::acceptPlainText,
                target = dropTarget,
            ),
    )
}

@Composable
private fun LazyGridItemScope.WidgetCard(
    state: WidgetState.Loaded,
    isBeingDragged: Boolean,
    onDragStart: () -> Unit,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
    onTransfer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val widgetId = state.id
    val cardLayer = rememberGraphicsLayer()
    val dropTarget = rememberDropTarget(onHover, onDrop, onEnded)

    val minHeight = if (state.size == WidgetSize.FULL) 120.dp else 96.dp
    val elevation by animateDpAsState(if (isBeingDragged) 4.dp else 0.dp, label = "drag-elevation")
    val scale by animateFloatAsState(if (isBeingDragged) 1.05f else 1f, label = "drag-scale")

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
            .dragAndDropSource(drawDragDecoration = { drawLayer(cardLayer) }) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (detectShortLongPress(down.id, LONG_PRESS_TIMEOUT_MS)) {
                        currentOnDragStart()
                        startTransfer(
                            DragAndDropTransferData(
                                ClipData.newPlainText("widgetId", widgetId),
                            )
                        )
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
                text = debugLabel(state),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onTransfer) {
                Icon(
                    imageVector = if (state.isInYourWidgets) Icons.Default.Remove else Icons.Default.Add,
                    contentDescription = if (state.isInYourWidgets)
                        "Move to Other widgets"
                    else
                        "Move to Your widgets",
                )
            }
        }
    }
}

@Composable
private fun LazyGridItemScope.SkeletonCell(
    size: WidgetSize,
    modifier: Modifier = Modifier,
) {
    val minHeight = if (size == WidgetSize.FULL) 120.dp else 96.dp
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {}
}

@Composable
private fun LazyGridItemScope.FailureCell(
    size: WidgetSize,
    modifier: Modifier = Modifier,
) {
    val minHeight = if (size == WidgetSize.FULL) 120.dp else 96.dp
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.error,
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
                text = "Failed to load",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun LazyGridItemScope.EmptyDropZone(
    message: String,
    isDragActive: Boolean,
    onHover: () -> Unit,
    onDrop: () -> Unit,
    onEnded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dropTarget = rememberDropTarget(onHover, onDrop, onEnded)
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
            )
            .dragAndDropTarget(
                shouldStartDragAndDrop = ::acceptPlainText,
                target = dropTarget,
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

@Composable
private fun ErrorScreen(cause: Throwable) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Couldn't load widgets",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = cause.message ?: cause::class.simpleName.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun debugLabel(state: WidgetState.Loaded): String = when (state) {
    is WidgetState.Loaded.InvestmentEntryPoint -> "Investment · ${state.id}"
    is WidgetState.Loaded.Pfm -> "PFM · ${state.id}"
    is WidgetState.Loaded.Tile.Monizze -> "Monizze · ${state.id}"
    is WidgetState.Loaded.Tile.Cashback -> "Cashback · ${state.id}"
    is WidgetState.Loaded.Tile.Pluxee -> "Pluxee · ${state.id}"
}

private suspend fun AwaitPointerEventScope.detectShortLongPress(
    pointerId: PointerId,
    timeoutMs: Long,
): Boolean {
    return try {
        withTimeout(timeoutMs) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId }
                if (change != null && !change.pressed) {
                    return@withTimeout false
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        true
    }
}
```

Key notes:

- **`WidgetsScreen` is now a composable that requires a `WidgetsViewModel` argument.** The previous default `viewModel()` call is gone — `MainActivity` constructs the VM via `viewModelFactory` (Task 6) because the constructor takes a `WidgetsUseCase`.
- **`SkeletonCell` and `FailureCell` have no drag modifiers** — neither `dragAndDropSource` nor `dragAndDropTarget`. Long-pressing a skeleton or failure cell does nothing; that is correct behavior.
- **`Icons.Default.Warning`** comes from `material-icons-core`, which is already on the classpath via `material-icons-extended` (line 31 of `libs.versions.toml`). No new dependency needed.
- **`debugLabel` is the only POC-specific concession.** When real presentation fields land on each `Loaded` subtype (or real per-tile composables ship in the production app), `debugLabel` is the one symbol to delete.

- [ ] **Step 2: Do not build yet — proceed to Task 6**

`MainActivity.kt` still calls `WidgetsScreen()` with no factory; that single-line fix is in Task 6.

---

## Task 6: Wire DI in `MainActivity.kt`

**Files:**
- Modify: `app/src/main/java/com/arthlem/dragdrop/MainActivity.kt`

- [ ] **Step 1: Replace the body of `MainActivity.kt`**

Overwrite `app/src/main/java/com/arthlem/dragdrop/MainActivity.kt`:

```kotlin
package com.arthlem.dragdrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val viewModel: WidgetsViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { WidgetsViewModel(FakeWidgetsUseCase()) }
                        }
                    )
                    WidgetsScreen(viewModel = viewModel)
                }
            }
        }
    }
}
```

When the real `WidgetsUseCase` lands, only the `FakeWidgetsUseCase()` reference inside `initializer { ... }` changes.

- [ ] **Step 2: Run a full debug build**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. The migration is now complete and the project compiles end-to-end.

If the build fails, the most likely cause is a missing `viewModelFactory` import — `androidx.lifecycle.viewmodel.viewModelFactory` and `androidx.lifecycle.viewmodel.initializer` come from the existing `androidx-lifecycle-viewmodel-compose` dependency (no module addition needed).

- [ ] **Step 3: Commit the combined VM/Screen/MainActivity migration**

```bash
git add app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt \
        app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt \
        app/src/main/java/com/arthlem/dragdrop/MainActivity.kt
git commit -m "feat: migrate ViewModel and Screen to WidgetState hierarchy

- Replace flat Widget data class with sealed WidgetState (Skeleton/Failure/Loaded)
- Constructor-inject WidgetsUseCase via viewModelFactory in MainActivity
- Seed 6 skeletons on init; replace atomically once eligibility-checked flow emits
- Defer flow emissions during active drag; replay on commit/cancel
- Add UiState.Error path with ErrorScreen composable
- Use polymorphic toggleIsInYourWidgets to preserve Loaded subtype across drag/transfer"
```

---

## Task 7: Update `CLAUDE.md`

**Files:**
- Modify: `CLAUDE.md`

The architecture description, file list, and stack section need updates to reflect the redesign.

- [ ] **Step 1: Update the `## Stack` section**

In `CLAUDE.md`, find the `## Stack` section. Add Arrow to the bullet list. The section should read:

```markdown
## Stack

- AGP 9.0.0-beta03, Gradle 9.1.0 (built-in Kotlin support — do **not** apply `org.jetbrains.kotlin.android` plugin, it conflicts with the AGP-provided `kotlin` extension; only `org.jetbrains.kotlin.plugin.compose` is applied).
- Compose BOM 2025.01.00 (Compose Foundation/UI 1.7.x), Material 3, lifecycle 2.8.7, activity-compose 1.9.3.
- Arrow Core 1.2.4 (`Either<Throwable, Flow<List<WidgetState>>>` for eligibility-aware data fetching).
- minSdk 26, targetSdk/compileSdk 36, JVM target 11.
```

- [ ] **Step 2: Replace the `## Architecture` section**

Find the `## Architecture` section and replace its body with:

```markdown
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
```

- [ ] **Step 3: Replace the `## Files` section**

Find the `## Files` section and replace its body with:

```markdown
## Files

- `app/src/main/java/com/arthlem/dragdrop/MainActivity.kt` — entry point; constructs `WidgetsViewModel` via `viewModelFactory` with `FakeWidgetsUseCase`, hosts `WidgetsScreen` under `MaterialTheme`.
- `app/src/main/java/com/arthlem/dragdrop/WidgetState.kt` — `enum WidgetSize`, sealed `WidgetState` hierarchy (`Skeleton`/`Failure`/`Loaded` with `InvestmentEntryPoint`/`Pfm`/`Tile.{Monizze,Cashback,Pluxee}`).
- `app/src/main/java/com/arthlem/dragdrop/WidgetsUseCase.kt` — `WidgetsUseCase` interface + `FakeWidgetsUseCase` returning `Either<Throwable, Flow<List<WidgetState>>>`.
- `app/src/main/java/com/arthlem/dragdrop/WidgetsViewModel.kt` — state machine, mutation functions, reconciliation helpers, deferred-emission logic, skeleton seed.
- `app/src/main/java/com/arthlem/dragdrop/WidgetsScreen.kt` — `WidgetsScreen` / `WidgetsContent` / `HeaderCell` / `WidgetCard` / `SkeletonCell` / `FailureCell` / `EmptyDropZone` / `ErrorScreen` / `rememberDropTarget` / `acceptPlainText` / `debugLabel` / `detectShortLongPress`.
```

- [ ] **Step 4: Replace the `## Initial test data` section**

Find the `## Initial test data` section and replace its body with:

```markdown
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
```

- [ ] **Step 5: Update the long-press section's reference (if present) and remove obsolete test data section if it lives elsewhere**

Search `CLAUDE.md` for any remaining references to the deleted `Widget` data class, `INITIAL_WIDGETS` constant in `WidgetsViewModel`, `Widget.isFullSpan`, `Widget.isYours`, or `GridEntry.Item`. Replace them inline:

- `Widget` → `WidgetState.Loaded`
- `Widget.isFullSpan` → `state.size == WidgetSize.FULL`
- `Widget.isYours` → `state.isInYourWidgets`
- `GridEntry.Item` → `GridEntry.Cell`
- `INITIAL_WIDGETS` (in `WidgetsViewModel`) → `FakeWidgetsUseCase.INITIAL_WIDGETS`

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md for WidgetState architecture"
```

---

## Task 8: On-device verification

**Files:** none (verification only)

This is the integration test for the migration. The build was already verified in Task 6; this task validates the runtime behavior.

- [ ] **Step 1: Build the debug APK if not already built**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. Output APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Install on a connected device**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`.

- [ ] **Step 3: Verify the loading → loaded happy path**

Launch the app. Expected:
- For ~500 ms, a 2-column grid of 6 skeletons (gray cards) renders with no headers — 4 small + 2 full mixed.
- The grid transitions to the loaded state: `Your widgets` header, then `Monizze · m1`, `Cashback · c1`, `Investment · iep1` (full-span); `Other widgets` header, then `PFM · pfm1` (full-span), `Pluxee · p1`, `Monizze · m2`.
- No layout reflow surprises.

- [ ] **Step 4: Verify all four drag scenarios still work**

| Scenario | Setup | Action | Expected |
|---|---|---|---|
| Yours → Yours reorder | Two cells in Yours | Long-press one, drag, release on the other | Cells swap positions |
| Other → Yours transfer | A cell in Other | Long-press, drag into Yours, release on a target | Cell commits at drop position; `−` icon now shown |
| Yours → Other (snap) | A cell in Yours | Long-press, drag into Other, release anywhere in the section | Cell snaps to top of Other; `+` icon now shown |
| Other → Other (revert) | Two cells in Other | Long-press one, drag, release on the other | Cell reverts to original index |

- [ ] **Step 5: Verify type preservation through the toggle button**

- Press `−` on `Monizze · m1` (in Yours). Expected: it appears at the top of Other, still rendered as `Monizze · m1`. Press `+`. Expected: it returns to the top of Yours.
- Press `+` on `PFM · pfm1` (in Other, full-span). Expected: it appears at the top of Yours, still rendered as `PFM · pfm1` with full-span layout.

- [ ] **Step 6: Verify skeletons are not draggable**

Open `app/src/main/java/com/arthlem/dragdrop/WidgetsUseCase.kt`. Temporarily increase `ELIGIBILITY_DELAY_MS` from `500L` to `5000L`. Rebuild and reinstall:

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch the app. While skeletons are visible, long-press one. Expected: nothing happens — no drag shadow appears, no reorder. After 5 seconds the loaded grid replaces the skeletons.

Restore `ELIGIBILITY_DELAY_MS` to `500L` and rebuild before continuing:

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 7: Verify the error screen**

Open `app/src/main/java/com/arthlem/dragdrop/WidgetsUseCase.kt`. Set `FAIL_ELIGIBILITY = true`. Rebuild and reinstall:

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch the app. Expected: after the eligibility delay, no skeletons remain — instead the screen shows a centered warning icon, "Couldn't load widgets" title, and "Eligibility check failed" subtitle.

Restore `FAIL_ELIGIBILITY = false` and rebuild before continuing:

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 8: Final commit (optional, only if any flag toggles slipped in)**

After all verification, confirm `FAIL_ELIGIBILITY = false` and `ELIGIBILITY_DELAY_MS = 500L` in `WidgetsUseCase.kt`. If git shows any unexpected diffs:

```bash
git status
git diff
```

If `WidgetsUseCase.kt` is clean, the migration is complete with no further commits.

---

## Self-review

After completing the plan, run a final pass:

1. **Spec coverage.** Every section of `docs/superpowers/specs/2026-05-04-widgetstate-architecture-design.md` should map to a task or step. Cross-checked: §1 type hierarchy → Task 2; §2 use case → Task 3; §3 UiState/transitions → Task 4 (init + collect); §4 ViewModel changes → Task 4; §5 Screen changes → Task 5; §6 test data + display → Task 3 (data) + Task 5 (`debugLabel`); §7 unchanged surface → preserved verbatim in Task 4/5 rewrites; §8 out of scope → respected (no retry button, no Hilt, no shimmer).
2. **Type consistency.** Method names match across tasks: `toggleIsInYourWidgets` (every Loaded subtype), `indexOfLoaded` / `loadedAt` (Task 4 helpers, used in 6 places within VM), `rebuildEntriesFromWidgets` (called from `collectLoadedFlow` and `flushPendingEntriesIfAny`), `flushPendingEntriesIfAny` (called from `onDragCommit` and `onDragCancel`).
3. **Build sequencing.** Tasks 1–3 each end with a successful `compileDebugKotlin`. Tasks 4 and 5 deliberately skip the build because `Widget` is removed mid-migration and only restored as `WidgetState.Loaded` once Task 5 completes. Task 6 includes the first end-to-end `assembleDebug`. Task 8 verifies on-device runtime behavior. The single combined commit at the end of Task 6 captures the cross-file migration atomically.
