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
 * Handles async refreshes for both small and big widgets.
 * BIG: called by ImdbSearchWidgetBigProvider.onUpdate() → fetches data on background
 *      thread with goAsync(), then updates widget when ready.
 * SMALL: hourly alarm pings that refresh the search bar layout.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val manager = AppWidgetManager.getInstance(context)

        when (action) {
            ACTION_REFRESH_BIG -> {
                // Called from ImdbSearchWidgetBigProvider.onUpdate()
                // goAsync keeps this broadcast alive past the 10s timeout
                val pendingResult = goAsync()
                Thread {
                    try {
                        val item = WidgetDataFetcher.fetchRandomFeatured()
                        val ids = manager.getAppWidgetIds(
                            ComponentName(context, ImdbSearchWidgetBigProvider::class.java)
                        )
                        for (id in ids) {
                            val views = ImdbSearchWidgetBigProvider.buildBigViews(context, item)
                            manager.updateAppWidget(id, views)
                        }
                    } catch (_: Exception) {
                        // Retry on next placement/hourly refresh
                    } finally {
                        pendingResult.finish()
                    }
                }.start()
            }

            ALARM_SMALL -> {
                // Hourly: refresh the small search-bar widget
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, ImdbSearchWidgetProvider::class.java)
                )
                for (id in ids) {
                    val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_small)
                    views.setOnClickPendingIntent(R.id.widget_root, searchPi(context))
                    manager.updateAppWidget(id, views)
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_BIG = "com.cvuong233.cinephantom.REFRESH_BIG"
        private const val ALARM_SMALL = "com.cvuong233.cinephantom.ALARM_SMALL"
        private const val ALARM_REQ = 2001

        /** Called from ImdbSearchWidgetBigProvider.onUpdate() to trigger async refresh */
        fun refreshBigWidgets(context: Context) {
            val intent = Intent(context, WidgetRefreshReceiver::class.java).apply {
                action = ACTION_REFRESH_BIG
            }
            context.sendBroadcast(intent)
        }

        fun scheduleRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ,
                Intent(context, WidgetRefreshReceiver::class.java).apply {
                    action = ALARM_SMALL
                },
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
                Intent(context, WidgetRefreshReceiver::class.java).apply {
                    action = ALARM_SMALL
                },
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
