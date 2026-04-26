package com.cvuong233.cinephantom.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R

/**
 * TEST C: Minimal provider. Only sets text on 2 TextViews.
 * Matches the flat, non-nested green diagnostic structure.
 */
class ImdbSearchWidgetBigProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)
            views.setTextViewText(R.id.widget_rank_badge, "#3 Movie")
            views.setTextViewText(R.id.widget_title, "The Shawshank Redemption")
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
