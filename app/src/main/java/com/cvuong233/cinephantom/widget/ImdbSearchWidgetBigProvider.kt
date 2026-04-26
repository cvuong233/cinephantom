package com.cvuong233.cinephantom.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R

/**
 * TEST D: All elements at root level. Sets text on all fields.
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
            views.setTextViewText(R.id.widget_rating, "IMDb 9.3")
            views.setTextViewText(R.id.widget_year, "1994")
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
