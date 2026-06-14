package com.cvuong233.cinephantom.notifications

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cvuong233.cinephantom.R
import java.util.Locale

class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: NotificationPreferences
    private lateinit var masterToggle: Switch
    private lateinit var settingsContent: LinearLayout
    private lateinit var timeDisplay: TextView

    private val leadOptions = listOf(0, 1, 3, 7)
    private lateinit var leadRows: List<LinearLayout>
    private lateinit var leadChecks: List<ImageView>

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

        leadRows = listOf(
            findViewById(R.id.notif_lead_day_of),
            findViewById(R.id.notif_lead_1_day),
            findViewById(R.id.notif_lead_3_days),
            findViewById(R.id.notif_lead_1_week)
        )
        leadChecks = listOf(
            findViewById(R.id.notif_lead_day_of_check),
            findViewById(R.id.notif_lead_1_day_check),
            findViewById(R.id.notif_lead_3_days_check),
            findViewById(R.id.notif_lead_1_week_check)
        )

        masterToggle.isChecked = prefs.masterEnabled
        updateSettingsContentAlpha(prefs.masterEnabled, animate = false)
        updateTimeDisplay(prefs.notificationHour, prefs.notificationMinute)
        updateLeadSelection(prefs.movieLeadDays)

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
                },
                prefs.notificationHour,
                prefs.notificationMinute,
                false
            ).show()
        }

        leadRows.forEachIndexed { i, row ->
            row.setOnClickListener {
                if (!prefs.masterEnabled) return@setOnClickListener
                val days = leadOptions[i]
                prefs.movieLeadDays = days
                updateLeadSelection(days)
            }
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

    private fun updateLeadSelection(selectedDays: Int) {
        leadOptions.forEachIndexed { i, days ->
            val isSelected = days == selectedDays
            leadChecks[i].visibility = if (isSelected) View.VISIBLE else View.GONE
            val labelView = leadRows[i].getChildAt(0) as? TextView
            labelView?.setTextColor(
                if (isSelected) getColor(R.color.secondary_link) else getColor(R.color.text_primary)
            )
        }
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
