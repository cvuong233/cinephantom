package com.cvuong233.cinephantom.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SearchActivity
import java.net.URL

/**
 * Big widget v2: hero poster fills area, rank badge overlaid, search bar below.
 * Hourly refresh via AlarmManager.
 */
class ImdbSearchWidgetBigProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        scheduleHourlyRefresh(context)
    }

    override fun onDisabled(context: Context) {
        cancelHourlyRefresh(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Thread {
            try {
                val item = WidgetDataFetcher.fetchRandomFeatured()
                for (id in appWidgetIds) {
                    val views = buildViews(context, item)
                    appWidgetManager.updateAppWidget(id, views)
                }
            } catch (_: Exception) {
                // On error, show nothing
            }
        }.apply { isDaemon = false; start() }
    }

    private fun buildViews(context: Context, item: WidgetFeaturedItem?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)

        // Search bar → search activity
        val searchPi = PendingIntent.getActivity(
            context, 1001,
            Intent(context, SearchActivity::class.java).apply {
                action = Intent.ACTION_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_search_bar, searchPi)
        views.setOnClickPendingIntent(R.id.widget_brand, searchPi)

        if (item == null) {
            views.setTextViewText(R.id.widget_rank_badge, "")
            return views
        }

        // ── Rank badge ──
        val typeLabel = if (item.type == "Movie") "Movie" else "TV Show"
        views.setTextViewText(R.id.widget_rank_badge, "#${item.rank} $typeLabel")

        // ── Poster ──
        loadPoster(views, item.posterUrl)

        // ── Featured tap → detail ──
        val detailPi = PendingIntent.getActivity(
            context, 1002,
            Intent(context, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_IMDB_ID, item.id)
                putExtra(DetailActivity.EXTRA_TITLE, item.title)
                putExtra(DetailActivity.EXTRA_IMAGE_URL, item.posterUrl)
                putExtra(DetailActivity.EXTRA_YEAR, item.year)
                putExtra(DetailActivity.EXTRA_TYPE, item.type)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_featured, detailPi)

        return views
    }

    private fun loadPoster(views: RemoteViews, posterUrl: String?) {
        if (posterUrl.isNullOrBlank()) return
        try {
            val conn = URL(posterUrl).openConnection()
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            val bmp = BitmapFactory.decodeStream(conn.getInputStream())
            (conn as? java.net.HttpURLConnection)?.disconnect()
            if (bmp != null) {
                views.setImageViewBitmap(R.id.widget_poster, bmp)
            }
        } catch (_: Exception) {
            // Poster stays dark background
        }
    }

    companion object {
        private const val ALARM_REQ = 2003

        private fun scheduleHourlyRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ,
                Intent(context, ImdbSearchWidgetBigProvider::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME, AlarmManager.INTERVAL_HOUR,
                AlarmManager.INTERVAL_HOUR, pi,
            )
        }

        private fun cancelHourlyRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ,
                Intent(context, ImdbSearchWidgetBigProvider::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.cancel(pi)
        }
    }
}
