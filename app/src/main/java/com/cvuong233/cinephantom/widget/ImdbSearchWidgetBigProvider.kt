package com.cvuong233.cinephantom.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.search.SearchActivity

/**
 * Big widget — DEBUG BUILD.
 * onUpdate() sets hardcoded text directly, no threads, no network.
 */
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
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)

            // HARDCODED DEBUG TEXT — no fetch, no thread
            views.setTextViewText(R.id.widget_rank_badge, "#1 DEBUG MOVIE")
            views.setTextViewText(R.id.widget_title, "Debug Title v52")
            views.setTextViewText(R.id.widget_rating, "IMDb 9.3")
            views.setViewVisibility(R.id.widget_rating, 0)  // VISIBLE
            views.setTextViewText(R.id.widget_year, "2026")
            views.setViewVisibility(R.id.widget_year, 0)    // VISIBLE
            views.setViewVisibility(R.id.widget_poster, 8)  // GONE
            views.setViewVisibility(R.id.widget_poster_label, 0) // VISIBLE
            // Change poster label to reflect debug
            views.setTextViewText(R.id.widget_poster_label, "D?")

            // Search tap
            val searchIntent = Intent(context, SearchActivity::class.java).apply {
                action = Intent.ACTION_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val searchPi = PendingIntent.getActivity(
                context, 1001, searchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_search_section, searchPi)

            appWidgetManager.updateAppWidget(id, views)
        }

        // Also schedule hourly refresh so data runs eventually
        WidgetRefreshReceiver.scheduleRefresh(context)
    }

    companion object {
        const val ACTION_IMMEDIATE_REFRESH = "com.cvuong233.cinephantom.REFRESH_BIG_NOW"
    }
}
