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
            is WidgetState.Loaded -> state.widget.id
            is WidgetState.Skeleton -> state.key
            is WidgetState.Failure -> state.key
        }
    }
    data class Empty(override val key: String, val message: String) : GridEntry
    /**
     * Invisible 1-column slot that fills out a partial row. Acts as a first-class drop target:
     * dropping a widget onto a RowFiller swaps the widget and the filler in [_entries], so the
     * empty slot moves to where the widget came from. Reconciliation keeps the filler count per
     * section consistent with layout requirements but preserves user-placed positions.
     */
    data class RowFiller(override val key: String) : GridEntry
}

data class DragState(
    val draggedWidget: GenericWidget,
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

        private const val FILLER_YOURS_KEY_PREFIX = "filler_yours_"
        private const val FILLER_OTHER_KEY_PREFIX = "filler_other_"
        private const val GRID_COLS = 2

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
            val isDragging = Snapshot.withoutReadObservation { _dragState.value != null }
            if (!isDragging) {
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
        // filterIsInstance: Skeleton/Failure items in a live-flow emission are not yet
        // produced by any use case path (per spec: out of scope). Drop them until that
        // path ships — otherwise non-Loaded items would land between the section headers.
        val (yours, other) = widgets
            .filterIsInstance<WidgetState.Loaded>()
            .partition { it.widget.isInYourWidgets }
        Snapshot.withMutableSnapshot {
            _entries.clear()
            _entries.add(GridEntry.Header(YOURS_HEADER_KEY, YOURS_HEADER_TITLE))
            _entries.addAll(yours.map { GridEntry.Cell(it) })
            _entries.add(GridEntry.Header(AVAILABLE_HEADER_KEY, AVAILABLE_HEADER_TITLE))
            _entries.addAll(other.map { GridEntry.Cell(it) })
            reconcileRowFillers()
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

        // Filler targets resolve to their entries-index just like cells. The standard
        // removeAt + add reorder lands the dragged widget at the filler's position; the
        // following reconcile always strips and re-adds fillers at canonical positions, so
        // the *visible* drop spot may shift as auto-pack repacks the section. The benefit:
        // no awkward filler placements (top of section, stranded after a full-span widget).
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
            _entries.add(safeIndex, cellOf(current))
            reconcileIsYoursForDraggedWidget(widgetId)
            reconcileRowFillers()
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
                    _entries.add(target, cellOf(moved))
                    _dragState.value = null
                    reconcileRowFillers()
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
        persistYoursOrder()
    }

    fun onDragCancel() {
        val state = _dragState.value ?: return
        Snapshot.withMutableSnapshot {
            val widgetId = state.draggedWidget.id
            val currentIndex = indexOfLoaded(widgetId)
            if (currentIndex >= 0) _entries.removeAt(currentIndex)
            val restored = state.draggedWidget.toggleIsInYourWidgets(state.originalIsInYourWidgets)
            val safeOriginal = state.originalIndex.coerceIn(0, _entries.size)
            _entries.add(safeOriginal, cellOf(restored))
            _dragState.value = null
            reconcileRowFillers()
            reconcileEmptyPlaceholders()
        }
        flushPendingEntriesIfAny()
        persistYoursOrder()
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
            _entries.add(target, cellOf(moved))
            reconcileRowFillers()
            reconcileEmptyPlaceholders()
        }
        persistYoursOrder()
    }

    private fun flushPendingEntriesIfAny() {
        val pending = pendingEntries ?: return
        pendingEntries = null
        rebuildEntriesFromWidgets(pending)
    }

    /**
     * Walks `_entries` from the Yours header down to the Available header, collecting widget IDs
     * for `Loaded` cells. Fillers and Empty placeholders are skipped. Fired off on a separate
     * coroutine — fire-and-forget; persistence latency doesn't block UI updates.
     */
    private fun persistYoursOrder() {
        val ids = yoursOrder()
        viewModelScope.launch { useCase.saveYoursOrder(ids) }
    }

    private fun yoursOrder(): List<String> {
        val yoursHeaderIdx = indexOfKey(YOURS_HEADER_KEY)
        if (yoursHeaderIdx < 0) return emptyList()
        val availableHeaderIdx = indexOfKey(AVAILABLE_HEADER_KEY).takeIf { it >= 0 } ?: _entries.size
        if (yoursHeaderIdx + 1 >= availableHeaderIdx) return emptyList()
        return _entries.subList(yoursHeaderIdx + 1, availableHeaderIdx)
            .mapNotNull { ((it as? GridEntry.Cell)?.state as? WidgetState.Loaded)?.widget?.id }
    }

    private fun indexOfKey(key: String): Int = _entries.indexOfFirst { it.key == key }

    private fun indexOfLoaded(widgetId: String): Int =
        _entries.indexOfFirst {
            it is GridEntry.Cell && it.state is WidgetState.Loaded && it.state.widget.id == widgetId
        }

    private fun loadedAt(index: Int): GenericWidget? =
        ((_entries.getOrNull(index) as? GridEntry.Cell)?.state as? WidgetState.Loaded)?.widget

    private fun cellOf(widget: GenericWidget): GridEntry.Cell =
        GridEntry.Cell(WidgetState.Loaded(widget))

    private fun reconcileIsYoursForDraggedWidget(widgetId: String) {
        val index = indexOfLoaded(widgetId)
        val current = loadedAt(index) ?: return
        val availableHeaderIndex = indexOfKey(AVAILABLE_HEADER_KEY)
        val shouldBeInYourWidgets = availableHeaderIndex < 0 || index < availableHeaderIndex
        if (current.isInYourWidgets != shouldBeInYourWidgets) {
            _entries[index] = cellOf(current.toggleIsInYourWidgets(shouldBeInYourWidgets))
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

    /**
     * Strip every [GridEntry.RowFiller] in each section and re-add at canonical positions:
     * just before any full-span widget that follows a partial row, plus end-of-section if the
     * last row is incomplete. Always re-canonicalizes — no skip-on-match — so fillers can never
     * end up in awkward positions like immediately after a section header or stranded between
     * a full-span widget and a single trailing small widget.
     */
    private fun reconcileRowFillers() {
        // Other section first so the Yours pass works against stable indices.
        reconcileSectionFillers(AVAILABLE_HEADER_KEY, null, FILLER_OTHER_KEY_PREFIX)
        reconcileSectionFillers(YOURS_HEADER_KEY, AVAILABLE_HEADER_KEY, FILLER_YOURS_KEY_PREFIX)
    }

    private fun reconcileSectionFillers(headerKey: String, nextHeaderKey: String?, fillerPrefix: String) {
        val headerIndex = indexOfKey(headerKey)
        if (headerIndex < 0) return
        val sectionEnd = nextHeaderKey?.let { indexOfKey(it) }?.takeIf { it >= 0 } ?: _entries.size

        // Strip existing fillers in the section.
        for (i in (sectionEnd - 1) downTo (headerIndex + 1)) {
            if (_entries[i] is GridEntry.RowFiller) _entries.removeAt(i)
        }

        // Re-find section start (may have shifted) and walk cells, inserting fillers at canonical positions.
        val rebuiltStart = indexOfKey(headerKey).takeIf { it >= 0 }?.let { it + 1 } ?: return
        var col = 0
        var fillerCounter = 0
        var idx = rebuiltStart
        while (idx < _entries.size) {
            val entry = _entries[idx]
            if (nextHeaderKey != null && entry.key == nextHeaderKey) break
            val widget = ((entry as? GridEntry.Cell)?.state as? WidgetState.Loaded)?.widget
            if (widget != null) {
                if (widget.size == WidgetSize.FULL) {
                    if (col != 0) {
                        repeat(GRID_COLS - col) {
                            _entries.add(idx, GridEntry.RowFiller("$fillerPrefix${fillerCounter++}"))
                            idx++
                        }
                        col = 0
                    }
                } else {
                    col = (col + 1) % GRID_COLS
                }
            }
            idx++
        }
        if (col != 0) {
            repeat(GRID_COLS - col) {
                _entries.add(idx, GridEntry.RowFiller("$fillerPrefix${fillerCounter++}"))
            }
        }
    }
}
