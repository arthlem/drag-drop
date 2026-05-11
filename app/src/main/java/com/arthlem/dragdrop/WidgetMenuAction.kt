package com.arthlem.dragdrop

/**
 * Items that can appear in the long-press contextual menu over a widget.
 * Top-level entries are common to every widget; nested groups carry per-widget-type extras.
 *
 * Keep [actionLabel] and [GenericWidget.menuActions] in sync when adding members:
 * `actionLabel`'s `when` is exhaustive, the compiler will fail builds that miss a label.
 */
sealed interface WidgetMenuAction {
    data object Reorder : WidgetMenuAction
    data object ManageWidgets : WidgetMenuAction

    sealed interface InvestmentEntryPoint : WidgetMenuAction {
        data object Buy : InvestmentEntryPoint
        data object Sell : InvestmentEntryPoint
    }

    sealed interface Pfm : WidgetMenuAction {
        data object Categories : Pfm
    }

    sealed interface Cashback : WidgetMenuAction {
        data object ViewTransactions : Cashback
    }

    sealed interface Pluxee : WidgetMenuAction {
        data object Settings : Pluxee
    }

    sealed interface Monizze : WidgetMenuAction {
        data object Topup : Monizze
    }
}

/**
 * Per-widget menu config. Per-type items first (so they appear at the top of the menu),
 * common items (Reorder, Manage widgets) appended last. Exhaustive `when` over
 * [GenericWidget] forces every new widget type to make an explicit choice here.
 */
fun GenericWidget.menuActions(): List<WidgetMenuAction> = buildList {
    when (this@menuActions) {
        is GenericWidget.InvestmentEntryPoint -> {
            add(WidgetMenuAction.InvestmentEntryPoint.Buy)
            add(WidgetMenuAction.InvestmentEntryPoint.Sell)
        }
        is GenericWidget.Pfm -> add(WidgetMenuAction.Pfm.Categories)
        is GenericWidget.Tile.Cashback -> add(WidgetMenuAction.Cashback.ViewTransactions)
        is GenericWidget.Tile.Pluxee -> add(WidgetMenuAction.Pluxee.Settings)
        is GenericWidget.Tile.Monizze -> add(WidgetMenuAction.Monizze.Topup)
    }
    add(WidgetMenuAction.Reorder)
    add(WidgetMenuAction.ManageWidgets)
}

fun actionLabel(action: WidgetMenuAction): String = when (action) {
    WidgetMenuAction.Reorder -> "Reorder"
    WidgetMenuAction.ManageWidgets -> "Manage widgets"
    WidgetMenuAction.InvestmentEntryPoint.Buy -> "Buy"
    WidgetMenuAction.InvestmentEntryPoint.Sell -> "Sell"
    WidgetMenuAction.Pfm.Categories -> "Categories"
    WidgetMenuAction.Cashback.ViewTransactions -> "View transactions"
    WidgetMenuAction.Pluxee.Settings -> "Settings"
    WidgetMenuAction.Monizze.Topup -> "Top up"
}
