package com.cvuong233.cinephantom.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R

/**
 * Big widget — ABSOLUTE MINIMUM debug.
 * No external drawables, no themes, no complex nesting.
 * Sets text synchronously in onUpdate.
 */
class ImdbSearchWidgetBigProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)

            // Set all text directly — no threads, no async, no network
            views.setTextViewText(R.id.widget_rank_badge, "#1 DEBUG MOVIE")
            views.setTextViewText(R.id.widget_title, "Debug Title v53")
            views.setTextViewText(R.id.widget_rating, "IMDb 9.3")
            views.setTextViewText(R.id.widget_debug_header, "CinePhantom BIG")

            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
