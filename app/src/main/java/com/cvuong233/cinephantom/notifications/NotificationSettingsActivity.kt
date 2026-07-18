package com.cvuong233.cinephantom.notifications

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.cvuong233.cinephantom.R
import java.util.Locale

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: NotificationPreferences
    private lateinit var masterToggle: Switch
    private lateinit var settingsContent: LinearLayout
    private lateinit var timeDisplay: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0D0011.toInt()
        setContentView(R.layout.activity_notification_settings)

        prefs = NotificationPreferences.get(this)

        findViewById<View>(R.id.toolbar_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.toolbar_title).text = "Notifications"

        masterToggle = findViewById(R.id.notif_master_toggle)
        settingsContent = findViewById(R.id.notif_settings_content)
        timeDisplay = findViewById(R.id.notif_time_display)

        masterToggle.isChecked = prefs.masterEnabled
        updateSettingsContentAlpha(prefs.masterEnabled, animate = false)
        updateTimeDisplay(prefs.notificationHour, prefs.notificationMinute)

        masterToggle.setOnCheckedChangeListener { _, checked ->
            prefs.masterEnabled = checked
            updateSettingsContentAlpha(checked, animate = true)
        }

        timeDisplay.setOnClickListener {
            if (!prefs.masterEnabled) return@setOnClickListener
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    prefs.notificationHour = hour
                    prefs.notificationMinute = minute
                    updateTimeDisplay(hour, minute)
                    rescheduleWatchlistNotifications()
                },
                prefs.notificationHour,
                prefs.notificationMinute,
                false
            ).show()
        }

        // Entrance animation
        settingsContent.alpha = 0f
        settingsContent.translationY = 30f
        settingsContent.animate()
            .alpha(if (prefs.masterEnabled) 1f else 0.4f)
            .translationY(0f)
            .setDuration(360)
            .setStartDelay(80)
            .setInterpolator(DecelerateInterpolator(1.3f))
            .start()
    }

    private fun updateTimeDisplay(hour: Int, minute: Int) {
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        timeDisplay.text = String.format(Locale.US, "%d:%02d %s", displayHour, minute, amPm)
    }

    // Existing alarms were scheduled with whatever lead-time/hour was in effect at the
    // time — AlarmManager doesn't re-read prefs at fire time. Re-running the refresh
    // worker recomputes and overwrites every pending alarm with the new settings.
    private fun rescheduleWatchlistNotifications() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            "reschedule_notifications",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WatchlistRefreshWorker>().build(),
        )
    }

    private fun updateSettingsContentAlpha(enabled: Boolean, animate: Boolean) {
        val targetAlpha = if (enabled) 1f else 0.4f
        settingsContent.isEnabled = enabled
        if (animate) {
            settingsContent.animate().alpha(targetAlpha).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            settingsContent.alpha = targetAlpha
        }
    }
}
