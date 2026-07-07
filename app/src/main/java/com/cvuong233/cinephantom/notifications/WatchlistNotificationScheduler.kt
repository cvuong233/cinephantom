package com.cvuong233.cinephantom.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object WatchlistNotificationScheduler {

    const val CHANNEL_ID = "watchlist_air_dates"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Watchlist Air Dates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies when watchlisted titles air or are released"
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    fun schedule(
        context: Context,
        imdbId: String,
        title: String,
        isTV: Boolean,
        airDate: String,
        season: Int = 0,
        episode: Int = 0,
        imageUrl: String? = null,
    ) {
        val prefs = NotificationPreferences.get(context)
        if (!prefs.masterEnabled) return

        val date = try { LocalDate.parse(airDate) } catch (_: Exception) { return }
        val leadDays = if (isTV) 0L else prefs.movieLeadDays.toLong()
        // Negative leadDays (e.g. -1 for "1 day after") naturally lands after the date here.
        val notifyDate = date.minusDays(leadDays)
        val today = LocalDate.now(ZoneId.systemDefault())
        if (notifyDate.isBefore(today)) return
        if (notifyDate.isEqual(today) && LocalTime.now(ZoneId.systemDefault()).hour >= prefs.notificationHour) return

        val triggerAtMillis = notifyDate.atTime(prefs.notificationHour, prefs.notificationMinute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val receiverIntent = Intent(context, WatchlistNotificationReceiver::class.java).apply {
            putExtra(WatchlistNotificationReceiver.EXTRA_IMDB_ID, imdbId)
            putExtra(WatchlistNotificationReceiver.EXTRA_TITLE, title)
            putExtra(WatchlistNotificationReceiver.EXTRA_IS_TV, isTV)
            putExtra(WatchlistNotificationReceiver.EXTRA_SEASON, season)
            putExtra(WatchlistNotificationReceiver.EXTRA_EPISODE, episode)
            putExtra(WatchlistNotificationReceiver.EXTRA_IMAGE_URL, imageUrl)
        }
        val pi = PendingIntent.getBroadcast(
            context, imdbId.hashCode(), receiverIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun cancel(context: Context, imdbId: String) {
        val intent = Intent(context, WatchlistNotificationReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, imdbId.hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }
}
