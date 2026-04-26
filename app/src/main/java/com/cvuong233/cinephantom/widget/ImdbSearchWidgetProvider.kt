package com.cvuong233.cinephantom.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

class ImdbSearchWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        // First widget added — schedule hourly refresh
        WidgetRefreshReceiver.scheduleRefresh(context)
    }

    override fun onDisabled(context: Context) {
        // Last widget removed — cancel refresh
        WidgetRefreshReceiver.cancelRefresh(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Initial update: fetch data and build views
        WidgetRefreshReceiver.updateAllWidgets(context)
    }
}
