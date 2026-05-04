package com.cvuong233.cinephantom.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SearchActivity
import java.net.HttpURLConnection
import java.net.URL

class ImdbSearchWidgetBigProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val ids = if (appWidgetIds.isNotEmpty()) appWidgetIds.toList()
            else appWidgetManager.getAppWidgetIds(
                android.content.ComponentName(context, ImdbSearchWidgetBigProvider::class.java)
            ).toList()
        if (ids.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val counter = prefs.getInt(PREF_COUNTER, 0) + 1
        prefs.edit().putInt(PREF_COUNTER, counter).apply()

        // Phase 1: instant — use cached live data if available
        val cached = WidgetDataFetcher.instantSeed(context)
        if (cached != null) {
            val typeLabel = if (cached.type == "movie") "Movie" else "TV Show"
            val views1 = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)
            setupClicks(context, views1, cached)
            views1.setTextViewText(R.id.widget_rank_badge, "#${cached.rank} $typeLabel")
            views1.setTextViewText(R.id.widget_counter, "Refresh #$counter")
            for (id in ids) appWidgetManager.updateAppWidget(id, views1)
        }
        // If no cache, widget shows its default layout text (from XML) briefly

        // Phase 2: background — fetch live data, download poster, update widget
        val pendingResult = goAsync()
        Thread {
            try {
                val seed = WidgetDataFetcher.liveSeed(context)
                if (seed != null) {
                    val typeLabel = if (seed.type == "movie") "Movie" else "TV Show"
                    val posterBmp = downloadBitmap(seed.posterUrlComputed)

                    val views2 = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)
                    setupClicks(context, views2, seed)
                    views2.setTextViewText(R.id.widget_rank_badge, "#${seed.rank} $typeLabel")
                    views2.setTextViewText(R.id.widget_counter, "Refresh #$counter")
                    if (posterBmp != null) {
                        views2.setImageViewBitmap(R.id.widget_poster, posterBmp)
                    }
                    for (id in ids) appWidgetManager.updateAppWidget(id, views2)
                }
            } catch (_: Exception) {
                // Cached content from phase 1 is already visible
            } finally {
                pendingResult.finish()
            }
        }.start()
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

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "CinePhantom/1.0")
            val bmp = BitmapFactory.decodeStream(conn.inputStream)
            conn.disconnect()
            bmp
        } catch (_: Exception) { null }
    }

    companion object {
        private const val PREFS_NAME = "cinephantom_widget"
        private const val PREF_COUNTER = "refresh_counter"
    }
}
