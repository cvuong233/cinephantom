package com.cvuong233.cinephantom.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.search.SearchActivity

/** Small home screen widget: search bar only. */
class ImdbSearchWidgetProvider : AppWidgetProvider() {

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
        val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_small)
        val pendingIntent = buildSearchIntent(context)
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    companion object {
        private fun buildSearchIntent(context: Context): PendingIntent {
            val intent = Intent(context, SearchActivity::class.java).apply {
                action = Intent.ACTION_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
