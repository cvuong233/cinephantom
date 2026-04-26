package com.cvuong233.cinephantom.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R

/**
 * DIAGNOSTIC STEP 2: Prove onUpdate() works.
 * Starts with green "BIG WIDGET" initial layout, then onUpdate()
 * changes it to "UPDATED!" — confirming the RemoteViews update path.
 */
class ImdbSearchWidgetBigProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)

            // Change the green "BIG WIDGET" text to confirm update works
            views.setTextViewText(R.id.widget_debug_header, "UPDATED!")
            views.setTextViewText(R.id.widget_debug_subtitle, "onUpdate works")

            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
