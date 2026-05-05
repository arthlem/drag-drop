package com.arthlem.dragdrop

enum class WidgetSize { SMALL, FULL }

sealed interface WidgetState {
    data class Skeleton(val key: String, val size: WidgetSize) : WidgetState
    data class Failure(val key: String, val size: WidgetSize) : WidgetState
    data class Loaded(val widget: GenericWidget) : WidgetState
}
