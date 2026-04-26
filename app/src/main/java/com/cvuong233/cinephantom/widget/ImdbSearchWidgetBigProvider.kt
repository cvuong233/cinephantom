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
 * Big widget: featured movie/TV show with poster, rating, year + search bar.
 * Refreshes hourly via AlarmManager.
 * Layout: nested LinearLayouts with divider as TextView (avoid View element).
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
                for (id in appWidgetIds) {
                    val views = buildViews(context, null)
                    appWidgetManager.updateAppWidget(id, views)
                }
            }
        }.apply { isDaemon = false; start() }
    }

    private fun buildViews(context: Context, item: WidgetFeaturedItem?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)

        // Search: tap on search bar or brand
        val searchPi = PendingIntent.getActivity(
            context, 1001,
            Intent(context, SearchActivity::class.java).apply {
                action = Intent.ACTION_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_brand, searchPi)

        if (item == null) {
            views.setTextViewText(R.id.widget_rank_badge, "")
            views.setTextViewText(R.id.widget_title, "Tap to search")
            views.setTextViewText(R.id.widget_rating, "")
            views.setTextViewText(R.id.widget_year, "")
            return views
        }

        // ── Featured content ──

        val typeLabel = if (item.type == "Movie") "Movie" else "TV Show"
        views.setTextViewText(R.id.widget_rank_badge, "#${item.rank} $typeLabel")
        views.setTextViewText(R.id.widget_title, item.title)

        if (!item.imdbRating.isNullOrBlank()) {
            views.setTextViewText(R.id.widget_rating, "IMDb ${item.imdbRating}")
        } else {
            views.setTextViewText(R.id.widget_rating, "")
        }

        if (!item.year.isNullOrBlank()) {
            views.setTextViewText(R.id.widget_year, item.year)
        } else {
            views.setTextViewText(R.id.widget_year, "")
        }

        // Poster
        loadPoster(views, item.posterUrl)

        // Featured tap → detail page
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
                views.setViewVisibility(R.id.widget_poster, VISIBLE)
                views.setViewVisibility(R.id.widget_poster_label, GONE)
            }
        } catch (_: Exception) {
            views.setViewVisibility(R.id.widget_poster, GONE)
            views.setViewVisibility(R.id.widget_poster_label, VISIBLE)
        }
    }

    // ── Hourly refresh ──

    companion object {
        private const val ALARM_REQ = 2003
        private const val VISIBLE = 0
        private const val GONE = 8

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
