package com.cvuong233.cinephantom.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.detail.DetailActivity

class WatchlistNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val imdbId = intent.getStringExtra(EXTRA_IMDB_ID) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val isTV = intent.getBooleanExtra(EXTRA_IS_TV, false)
        val season = intent.getIntExtra(EXTRA_SEASON, 0)
        val episode = intent.getIntExtra(EXTRA_EPISODE, 0)

        val body = if (isTV && season > 0 && episode > 0) {
            "📺 S${season}E${episode} airs today!"
        } else if (isTV) {
            "📺 New episode airs today!"
        } else {
            "🎬 Out today!"
        }

        val tapIntent = Intent(context, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, imdbId)
            putExtra(DetailActivity.EXTRA_TITLE, title)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPi = PendingIntent.getActivity(
            context, imdbId.hashCode() + 1, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, WatchlistNotificationScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_watchlist)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .build()

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(imdbId.hashCode(), notification)
    }

    companion object {
        const val EXTRA_IMDB_ID = "extra_imdb_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_IS_TV = "extra_is_tv"
        const val EXTRA_SEASON = "extra_season"
        const val EXTRA_EPISODE = "extra_episode"
        const val EXTRA_IMAGE_URL = "extra_image_url"
    }
}
