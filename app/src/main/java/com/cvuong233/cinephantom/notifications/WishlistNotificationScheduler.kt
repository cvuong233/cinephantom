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

object WishlistNotificationScheduler {

    const val CHANNEL_ID = "wishlist_air_dates"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wishlist Air Dates",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies when wishlisted titles air or are released"
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
        val notifyDate = date.minusDays(leadDays)
        val today = LocalDate.now(ZoneId.systemDefault())
        if (notifyDate.isBefore(today)) return
        if (notifyDate.isEqual(today) && LocalTime.now(ZoneId.systemDefault()).hour >= prefs.notificationHour) return

        val triggerAtMillis = notifyDate.atTime(prefs.notificationHour, prefs.notificationMinute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        val receiverIntent = Intent(context, WishlistNotificationReceiver::class.java).apply {
            putExtra(WishlistNotificationReceiver.EXTRA_IMDB_ID, imdbId)
            putExtra(WishlistNotificationReceiver.EXTRA_TITLE, title)
            putExtra(WishlistNotificationReceiver.EXTRA_IS_TV, isTV)
            putExtra(WishlistNotificationReceiver.EXTRA_SEASON, season)
            putExtra(WishlistNotificationReceiver.EXTRA_EPISODE, episode)
            putExtra(WishlistNotificationReceiver.EXTRA_IMAGE_URL, imageUrl)
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
        val intent = Intent(context, WishlistNotificationReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, imdbId.hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }
}
