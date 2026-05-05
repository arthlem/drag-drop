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
            WidgetState.Loaded(GenericWidget.Tile.Monizze(id = "m1", size = WidgetSize.SMALL, isInYourWidgets = true)),
            WidgetState.Loaded(GenericWidget.Tile.Cashback(id = "c1", size = WidgetSize.SMALL, isInYourWidgets = true)),
            WidgetState.Loaded(GenericWidget.InvestmentEntryPoint(id = "iep1", size = WidgetSize.FULL, isInYourWidgets = true)),
            WidgetState.Loaded(GenericWidget.Pfm(id = "pfm1", size = WidgetSize.FULL, isInYourWidgets = false)),
            WidgetState.Loaded(GenericWidget.Tile.Pluxee(id = "p1", size = WidgetSize.SMALL, isInYourWidgets = false)),
            WidgetState.Loaded(GenericWidget.Tile.Monizze(id = "m2", size = WidgetSize.SMALL, isInYourWidgets = false)),
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
