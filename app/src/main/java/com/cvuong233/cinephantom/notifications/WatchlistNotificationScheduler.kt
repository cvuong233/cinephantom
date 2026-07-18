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

    // Fixed reminder schedule: every watchlisted title gets a heads-up the day before it
    // releases/airs, plus one on the day itself. Not user-configurable — it used to be a single
    // selectable lead time (including a "1 day after" option that shipped as the default and
    // meant nothing ever notified on time), which is what this replaces.
    private val LEAD_DAYS_OPTIONS = listOf(1L, 0L)

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
        val today = LocalDate.now(ZoneId.systemDefault())

        for (leadDays in LEAD_DAYS_OPTIONS) {
            val notifyDate = date.minusDays(leadDays)
            if (notifyDate.isBefore(today)) continue
            if (notifyDate.isEqual(today) && LocalTime.now(ZoneId.systemDefault()).hour >= prefs.notificationHour) continue

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
                putExtra(WatchlistNotificationReceiver.EXTRA_LEAD_DAYS, leadDays.toInt())
            }
            val pi = PendingIntent.getBroadcast(
                context, requestCode(imdbId, leadDays), receiverIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        }
    }

    fun cancel(context: Context, imdbId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (leadDays in LEAD_DAYS_OPTIONS) {
            val intent = Intent(context, WatchlistNotificationReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, requestCode(imdbId, leadDays), intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            ) ?: continue
            am.cancel(pi)
            pi.cancel()
        }
    }

    // Distinct request code per (title, lead-time) pair — each title now has two independent
    // alarms, so the old imdbId-only code would make the second schedule() call overwrite the
    // first's PendingIntent instead of creating a separate alarm.
    private fun requestCode(imdbId: String, leadDays: Long): Int = "$imdbId#lead$leadDays".hashCode()
}
