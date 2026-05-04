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

        // Phase 1: instant — show a hardcoded classic seed immediately (no network)
        val fallbackSeed = WidgetDataFetcher.randomSeed()
        showPhase1(context, fallbackSeed, counter, ids, appWidgetManager)

        // Phase 2: background — try live IMDb charts + download poster
        val pendingResult = goAsync()
        Thread {
            try {
                // Try live data; fall back to the classic seed on any failure
                val liveSeed = WidgetDataFetcher.fetchLiveSeed()
                val finalSeed = liveSeed ?: fallbackSeed
                val typeLabel = if (finalSeed.type == "movie") "Movie" else "TV Show"

                // Fetch IMDb rating if we don't already have it from live data
                val rating = finalSeed.imdbRating
                    ?: fetchCinemetaRating(finalSeed.id, finalSeed.type)

                // Download poster
                val posterUrl = finalSeed.posterUrlComputed
                val bmp = downloadBitmap(posterUrl)

                // Update widget with final data
                val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)
                setupClicks(context, views, finalSeed)

                val rankText = "#${finalSeed.rank} $typeLabel"
                views.setTextViewText(R.id.widget_rank_badge, rankText)
                views.setTextViewText(R.id.widget_counter, "Refresh #$counter")
                if (bmp != null) {
                    views.setImageViewBitmap(R.id.widget_poster, bmp)
                }
                for (id in ids) appWidgetManager.updateAppWidget(id, views)
            } catch (_: Exception) {
                // Phase 1 already shows content; widget is usable
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    /** Phase 1: show text immediately with a fallback seed (no poster yet). */
    private fun showPhase1(
        context: Context,
        seed: WidgetDataFetcher.Seed,
        counter: Int,
        ids: List<Int>,
        appWidgetManager: AppWidgetManager,
    ) {
        val typeLabel = if (seed.type == "movie") "Movie" else "TV Show"
        val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)
        setupClicks(context, views, seed)
        views.setTextViewText(R.id.widget_rank_badge, "#${seed.rank} $typeLabel")
        views.setTextViewText(R.id.widget_counter, "Refresh #$counter")
        for (id in ids) appWidgetManager.updateAppWidget(id, views)
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

    private fun fetchCinemetaRating(imdbId: String, contentType: String): String? {
        return try {
            val url = URL("https://v3-cinemeta.strem.io/meta/$contentType/$imdbId.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.instanceFollowRedirects = false
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val meta = org.json.JSONObject(text).optJSONObject("meta") ?: return null
            meta.optString("imdbRating").takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    companion object {
        private const val PREFS_NAME = "cinephantom_widget"
        private const val PREF_COUNTER = "refresh_counter"
    }
}
