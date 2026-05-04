package com.arthlem.dragdrop

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Widget(
    val id: String,
    val name: String,
    val isFullSpan: Boolean,
    val isYours: Boolean,
)

sealed interface GridEntry {
    val key: String

    data class Header(override val key: String, val title: String) : GridEntry
    data class Item(val widget: Widget) : GridEntry {
        override val key: String get() = widget.id
    }
    data class Empty(override val key: String, val message: String) : GridEntry
}

data class DragState(
    val draggedWidget: Widget,
    val originalIndex: Int,
    val originalIsYours: Boolean,
)

sealed interface UiState {
    data object Loading : UiState
    data object Success : UiState
}

class WidgetsViewModel : ViewModel() {

    companion object {
        const val YOURS_HEADER_KEY = "header_yours"
        const val AVAILABLE_HEADER_KEY = "header_available"
        const val YOURS_EMPTY_KEY = "empty_yours"
        const val AVAILABLE_EMPTY_KEY = "empty_available"

        private const val YOURS_HEADER_TITLE = "Your widgets"
        private const val AVAILABLE_HEADER_TITLE = "Other widgets"
        private const val YOURS_EMPTY_MESSAGE = "Drop a widget here"
        private const val AVAILABLE_EMPTY_MESSAGE = "Nothing available"
        private const val LOAD_DELAY_MS = 500L

        private val INITIAL_WIDGETS = listOf(
            Widget("w12", "Widget 12", isFullSpan = false, isYours = true),
	        Widget("w31", "Widget 31", isFullSpan = false, isYours = true),
	        Widget("w32", "Widget 32", isFullSpan = false, isYours = true),
	        Widget("w0", "Widget 0", isFullSpan = false, isYours = true),
            Widget("w2", "Analyse de budget", isFullSpan = true, isYours = true),
            Widget("w3", "Widget 2", isFullSpan = false, isYours = false),
            Widget("w4", "Widget 3", isFullSpan = false, isYours = false),
        )
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _entries = mutableStateListOf<GridEntry>()
    val entries: List<GridEntry> get() = _entries

    private val _dragState = mutableStateOf<DragState?>(null)
    val dragState: DragState? get() = _dragState.value

    init {
        viewModelScope.launch {
            delay(LOAD_DELAY_MS)
            val (yours, available) = INITIAL_WIDGETS.partition { it.isYours }
            Snapshot.withMutableSnapshot {
                _entries.add(GridEntry.Header(YOURS_HEADER_KEY, YOURS_HEADER_TITLE))
                _entries.addAll(yours.map { GridEntry.Item(it) })
                _entries.add(GridEntry.Header(AVAILABLE_HEADER_KEY, AVAILABLE_HEADER_TITLE))
                _entries.addAll(available.map { GridEntry.Item(it) })
                reconcileEmptyPlaceholders()
            }
            _uiState.value = UiState.Success
        }
    }

    fun onDragStart(widgetId: String) {
        if (_dragState.value != null) return
        val index = indexOfWidget(widgetId)
        val widget = widgetAt(index) ?: return
        _dragState.value = DragState(
            draggedWidget = widget,
            originalIndex = index,
            originalIsYours = widget.isYours,
        )
    }

    fun onDragHover(targetKey: String) {
        val state = _dragState.value ?: return
        val widgetId = state.draggedWidget.id
        if (targetKey == widgetId) return

        val currentIndex = indexOfWidget(widgetId)
        if (currentIndex < 0) return

        val targetIndex = if (targetKey == YOURS_HEADER_KEY) {
            indexOfKey(YOURS_HEADER_KEY).let { if (it < 0) -1 else it + 1 }
        } else {
            indexOfKey(targetKey)
        }
        if (targetIndex < 0 || targetIndex == currentIndex) return

        Snapshot.withMutableSnapshot {
            _entries.removeAt(currentIndex)
            val safeIndex = targetIndex.coerceIn(0, _entries.size)
            _entries.add(safeIndex, GridEntry.Item(state.draggedWidget))
            reconcileIsYoursForDraggedWidget(widgetId)
            reconcileEmptyPlaceholders()
        }
    }

    fun onDragCommit() {
        val state = _dragState.value ?: return
        val widgetId = state.draggedWidget.id
        val currentIndex = indexOfWidget(widgetId)
        val availableHeaderIndex = indexOfKey(AVAILABLE_HEADER_KEY)
        val landedInYours = currentIndex >= 0 &&
            (availableHeaderIndex !in 0..currentIndex)

        when {
            landedInYours -> {
                // Yours → Yours (reorder) or Other → Yours (transfer-in): commit in place.
                _dragState.value = null
            }
            state.originalIsYours -> {
                // Yours → Other: whole-section drop zone — snap to top of Other.
                Snapshot.withMutableSnapshot {
                    if (currentIndex >= 0) _entries.removeAt(currentIndex)
                    val anchorIndex = indexOfKey(AVAILABLE_HEADER_KEY)
                    val target = (if (anchorIndex < 0) _entries.size else anchorIndex + 1)
                        .coerceIn(0, _entries.size)
                    _entries.add(target, GridEntry.Item(state.draggedWidget.copy(isYours = false)))
                    _dragState.value = null
                    reconcileEmptyPlaceholders()
                }
            }
            else -> {
                // Other → Other: no in-section reorder; revert to origin.
                onDragCancel()
            }
        }
    }

    fun onDragCancel() {
        val state = _dragState.value ?: return
        Snapshot.withMutableSnapshot {
            val widgetId = state.draggedWidget.id
            val currentIndex = indexOfWidget(widgetId)
            if (currentIndex >= 0) _entries.removeAt(currentIndex)
            val restored = state.draggedWidget.copy(isYours = state.originalIsYours)
            val safeOriginal = state.originalIndex.coerceIn(0, _entries.size)
            _entries.add(safeOriginal, GridEntry.Item(restored))
            _dragState.value = null
            reconcileEmptyPlaceholders()
        }
    }

    fun onTransfer(widgetId: String) {
        val currentIndex = indexOfWidget(widgetId)
        val widget = widgetAt(currentIndex) ?: return
        val moved = widget.copy(isYours = !widget.isYours)
        val anchorKey = if (moved.isYours) YOURS_HEADER_KEY else AVAILABLE_HEADER_KEY
        Snapshot.withMutableSnapshot {
            _entries.removeAt(currentIndex)
            val anchorIndex = indexOfKey(anchorKey)
            val target = (if (anchorIndex < 0) _entries.size else anchorIndex + 1)
                .coerceIn(0, _entries.size)
            _entries.add(target, GridEntry.Item(moved))
            reconcileEmptyPlaceholders()
        }
    }

    private fun indexOfKey(key: String): Int = _entries.indexOfFirst { it.key == key }

    private fun indexOfWidget(widgetId: String): Int =
        _entries.indexOfFirst { it is GridEntry.Item && it.widget.id == widgetId }

    private fun widgetAt(index: Int): Widget? =
        (_entries.getOrNull(index) as? GridEntry.Item)?.widget

    private fun reconcileIsYoursForDraggedWidget(widgetId: String) {
        val index = indexOfWidget(widgetId)
        val current = widgetAt(index) ?: return
        val availableHeaderIndex = indexOfKey(AVAILABLE_HEADER_KEY)
        val shouldBeYours = availableHeaderIndex < 0 || index < availableHeaderIndex
        if (current.isYours != shouldBeYours) {
            _entries[index] = GridEntry.Item(current.copy(isYours = shouldBeYours))
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
