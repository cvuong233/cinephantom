package com.cvuong233.cinephantom.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SearchActivity
import java.net.URL

class ImdbSearchWidgetBigProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        scheduleNext(context)
    }

    override fun onDisabled(context: Context) {
        cancelRefresh(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val ids = if (appWidgetIds != null && appWidgetIds.isNotEmpty()) appWidgetIds.toList()
            else appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, ImdbSearchWidgetBigProvider::class.java)
            ).toList()
        if (ids.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val counter = prefs.getInt(PREF_COUNTER, 0) + 1
        prefs.edit().putInt(PREF_COUNTER, counter).apply()

        // Do everything synchronously. Network takes ~2-4s, well within the
        // 10-second broadcast timeout. No threading, no goAsync complexity.
        val seed = WidgetDataFetcher.randomSeed()
        val item = try {
            WidgetDataFetcher.fetchFeatured(seed)
        } catch (_: Exception) {
            null
        }

        val views = if (item != null) buildViews(context, item, counter)
            else buildViews(context, WidgetFeaturedItem.fromSeed(seed), counter)

        for (id in ids) appWidgetManager.updateAppWidget(id, views)

        // Schedule next rotation 15s from now
        scheduleNext(context)
    }

    private fun buildViews(context: Context, item: WidgetFeaturedItem, counter: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)
        setupClicks(context, views, item.toSeed())

        val typeLabel = if (item.type == "Movie") "Movie" else "TV Show"
        views.setTextViewText(R.id.widget_rank_badge, "#${item.rank} $typeLabel")
        views.setTextViewText(R.id.widget_counter, "Refresh #$counter")

        // Poster: try URI first (launcher may load it), then bitmap fallback
        if (!item.posterUrl.isNullOrBlank()) {
            views.setImageViewUri(R.id.widget_poster, Uri.parse(item.posterUrl))
            val bmp = downloadPoster(item.posterUrl)
            if (bmp != null) views.setImageViewBitmap(R.id.widget_poster, bmp)
        }
        return views
    }

    private fun setupClicks(context: Context, views: RemoteViews, seed: WidgetDataFetcher.Seed) {
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

        val detailPi = PendingIntent.getActivity(
            context, 1002,
            Intent(context, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_IMDB_ID, seed.id)
                putExtra(DetailActivity.EXTRA_TITLE, seed.title)
                putExtra(DetailActivity.EXTRA_IMAGE_URL, seed.posterUrl)
                putExtra(DetailActivity.EXTRA_YEAR, seed.year)
                putExtra(DetailActivity.EXTRA_TYPE, if (seed.type == "movie") "Movie" else "Series")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_featured, detailPi)
    }

    private fun downloadPoster(url: String): Bitmap? {
        return try {
            val conn = URL(url).openConnection()
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = BitmapFactory.decodeStream(conn.getInputStream(), null, opts)
            (conn as? java.net.HttpURLConnection)?.disconnect()
            bmp
        } catch (_: Exception) { null }
    }

    companion object {
        private const val ALARM_REQ = 2003
        private const val INTERVAL_MS = 15_000L
        private const val PREFS_NAME = "cinephantom_widget"
        private const val PREF_COUNTER = "refresh_counter"

        fun scheduleNext(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ,
                Intent(context, ImdbSearchWidgetBigProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // setAndAllowWhileIdle: fires even during Doze, no special permission needed.
            // Falls back to setExact if permission is granted (more precise timing).
            try {
                alarm.setExact(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    pi,
                )
            } catch (_: SecurityException) {
                alarm.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    pi,
                )
            }
        }

        fun cancelRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ,
                Intent(context, ImdbSearchWidgetBigProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.cancel(pi)
        }
    }
}
