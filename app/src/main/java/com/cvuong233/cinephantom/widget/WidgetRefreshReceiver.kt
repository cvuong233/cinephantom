package com.cvuong233.cinephantom.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.search.SearchActivity

/**
 * Simplified RefreshReceiver — only handles small widget hourly pings.
 * Big widget handles updates directly in its onUpdate().
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val manager = AppWidgetManager.getInstance(context)

        // Update small search-bar widgets
        val smallIds = manager.getAppWidgetIds(
            ComponentName(context, ImdbSearchWidgetProvider::class.java)
        )
        for (id in smallIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_small)
            views.setOnClickPendingIntent(R.id.widget_root, searchPi(context))
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        private const val ALARM_REQ = 2001

        fun scheduleRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ,
                Intent(context, WidgetRefreshReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME, AlarmManager.INTERVAL_HOUR,
                AlarmManager.INTERVAL_HOUR, pi,
            )
        }

        fun cancelRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ,
                Intent(context, WidgetRefreshReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.cancel(pi)
        }

        private fun searchPi(context: Context): PendingIntent {
            val intent = Intent(context, SearchActivity::class.java).apply {
                action = Intent.ACTION_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context, 1001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
