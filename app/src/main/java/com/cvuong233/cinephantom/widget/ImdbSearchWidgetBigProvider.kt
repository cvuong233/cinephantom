package com.cvuong233.cinephantom.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** Home screen widget with featured item display (big size). */
class ImdbSearchWidgetBigProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        WidgetRefreshReceiver.scheduleRefresh(context)
    }

    override fun onDisabled(context: Context) {
        WidgetRefreshReceiver.cancelRefresh(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        WidgetRefreshReceiver.updateBigWidgets(context, appWidgetIds)
    }
}
