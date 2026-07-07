package com.cvuong233.cinephantom.notifications

import android.content.Context
import android.content.SharedPreferences

class NotificationPreferences private constructor(private val prefs: SharedPreferences) {

    var masterEnabled: Boolean
        get() = prefs.getBoolean("master_enabled", true)
        set(v) = prefs.edit().putBoolean("master_enabled", v).apply()

    var notificationHour: Int
        get() = prefs.getInt("notif_hour", 9)
        set(v) = prefs.edit().putInt("notif_hour", v).apply()

    var notificationMinute: Int
        get() = prefs.getInt("notif_minute", 0)
        set(v) = prefs.edit().putInt("notif_minute", v).apply()

    // Days before release/air-date to notify: 0=day-of, 1=1 day, 3=3 days, 7=1 week.
    // Negative means "after" the date instead of before: -1=1 day after.
    var movieLeadDays: Int
        get() = prefs.getInt("movie_lead_days", 0)
        set(v) = prefs.edit().putInt("movie_lead_days", v).apply()

    companion object {
        fun get(context: Context) =
            NotificationPreferences(context.applicationContext.getSharedPreferences("notif_prefs", Context.MODE_PRIVATE))
    }
}
