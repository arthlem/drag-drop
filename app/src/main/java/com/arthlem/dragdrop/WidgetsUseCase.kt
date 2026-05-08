package com.arthlem.dragdrop

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf

interface WidgetsUseCase {
	suspend fun fetchWidgets(): Either<Throwable, Flow<List<WidgetState>>>

	/**
	 * Persist the order of widgets currently in "Your widgets". Implicitly encodes which
	 * widgets are in Yours (anything not in [ids] defaults to Other on next load). Called
	 * after committed user actions only — drag-time intermediate states are not persisted.
	 */
	suspend fun saveYoursOrder(ids: List<String>)
}

class FakeWidgetsUseCase(
	private val dataStore: DataStore<Preferences>,
) : WidgetsUseCase {

	companion object {
		/** Flip to `true` to verify the error screen on next install. */
		const val FAIL_ELIGIBILITY = false
		private const val ELIGIBILITY_DELAY_MS = 2000L
		private const val YOURS_ORDER_DELIMITER = ","
		private val YOURS_ORDER_KEY = stringPreferencesKey("yours_order")

		private val INITIAL_WIDGETS: List<WidgetState> = listOf(
			WidgetState.Loaded(GenericWidget.Tile.Monizze(id = "m1", size = WidgetSize.SMALL, isInYourWidgets = true)),
			WidgetState.Loaded(GenericWidget.Tile.Cashback(id = "c1", size = WidgetSize.SMALL, isInYourWidgets = true)),
			WidgetState.Loaded(GenericWidget.InvestmentEntryPoint(id = "iep1", size = WidgetSize.FULL, isInYourWidgets = true)),
			WidgetState.Loaded(GenericWidget.Pfm(id = "pfm1", size = WidgetSize.FULL, isInYourWidgets = false)),
			WidgetState.Loaded(GenericWidget.Tile.Pluxee(id = "p1", size = WidgetSize.SMALL, isInYourWidgets = false)),
			WidgetState.Loaded(GenericWidget.Tile.Monizze(id = "m2", size = WidgetSize.SMALL, isInYourWidgets = false)),
			WidgetState.Loaded(GenericWidget.Tile.Monizze(id = "m3", size = WidgetSize.SMALL, isInYourWidgets = false)),
			WidgetState.Loaded(GenericWidget.Tile.Monizze(id = "m4", size = WidgetSize.SMALL, isInYourWidgets = false)),
			WidgetState.Loaded(GenericWidget.Tile.Monizze(id = "m5", size = WidgetSize.SMALL, isInYourWidgets = false)),
			WidgetState.Loaded(GenericWidget.Tile.Monizze(id = "m6", size = WidgetSize.SMALL, isInYourWidgets = false)),
			WidgetState.Loaded(GenericWidget.Tile.Monizze(id = "m7", size = WidgetSize.SMALL, isInYourWidgets = false)),
			WidgetState.Loaded(GenericWidget.Tile.Monizze(id = "m8", size = WidgetSize.SMALL, isInYourWidgets = false)),
			WidgetState.Loaded(GenericWidget.Tile.Monizze(id = "m9", size = WidgetSize.FULL, isInYourWidgets = false)),
		)
	}

	override suspend fun fetchWidgets(): Either<Throwable, Flow<List<WidgetState>>> {
		delay(ELIGIBILITY_DELAY_MS)
		if (FAIL_ELIGIBILITY) return IllegalStateException("Eligibility check failed").left()

		val savedOrder = readSavedYoursOrder()
		val applied = if (savedOrder.isEmpty()) INITIAL_WIDGETS else applySavedOrder(INITIAL_WIDGETS, savedOrder)
		return flowOf(applied).right()
	}

	override suspend fun saveYoursOrder(ids: List<String>) {
		dataStore.edit { prefs ->
			prefs[YOURS_ORDER_KEY] = ids.joinToString(YOURS_ORDER_DELIMITER)
		}
	}

	private suspend fun readSavedYoursOrder(): List<String> {
		val raw = dataStore.data.first()[YOURS_ORDER_KEY] ?: return emptyList()
		if (raw.isEmpty()) return emptyList()
		return raw.split(YOURS_ORDER_DELIMITER)
	}

	/**
	 * Apply [savedOrder] to [widgets]: anything in [savedOrder] becomes a Yours-section
	 * widget in that exact order; everything else keeps its default `isInYourWidgets`. IDs
	 * in [savedOrder] that no longer exist in [widgets] are silently dropped.
	 */
	private fun applySavedOrder(
		widgets: List<WidgetState>,
		savedOrder: List<String>,
	): List<WidgetState> {
		val savedSet = savedOrder.toSet()
		val byId = widgets.mapNotNull { state ->
			val widget = (state as? WidgetState.Loaded)?.widget
			if (widget != null) widget.id to state else null
		}.toMap()

		val yoursOrdered: List<WidgetState> = savedOrder.mapNotNull { id ->
			val state = byId[id] ?: return@mapNotNull null
			val widget = state.widget
			WidgetState.Loaded(widget.toggleIsInYourWidgets(true))
		}

		val others: List<WidgetState> = widgets.mapNotNull { state ->
			val widget = (state as? WidgetState.Loaded)?.widget ?: return@mapNotNull state
			if (widget.id in savedSet) null else WidgetState.Loaded(widget.toggleIsInYourWidgets(false))
		}

		return yoursOrdered + others
	}
}
