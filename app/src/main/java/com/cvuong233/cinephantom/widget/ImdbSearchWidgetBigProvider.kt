package com.cvuong233.cinephantom.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

/** Big widget: triggers an immediate one-shot alarm for data fetch,
 *  so the BroadcastReceiver runs with a guaranteed lifecycle. */
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
        // Store the widget IDs so the refresh receiver knows which to update
        WidgetRefreshReceiver.pendingBigWidgetIds = appWidgetIds

        // Schedule an exact alarm NOW to guarantee the fetch runs
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            context, 2002,
            Intent(context, WidgetRefreshReceiver::class.java).apply {
                action = ACTION_IMMEDIATE_REFRESH
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarm.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, 1000L, pi)
    }

    companion object {
        const val ACTION_IMMEDIATE_REFRESH = "com.cvuong233.cinephantom.REFRESH_BIG_NOW"
    }
}
