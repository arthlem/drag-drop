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
