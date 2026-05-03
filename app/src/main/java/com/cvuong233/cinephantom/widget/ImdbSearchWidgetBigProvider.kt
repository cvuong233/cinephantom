package com.cvuong233.cinephantom.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SearchActivity
import java.net.URL

class ImdbSearchWidgetBigProvider : AppWidgetProvider() {

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

        // Phase 1: instant — title, rank, poster URI, counter. No network.
        val seed = WidgetDataFetcher.randomSeed()
        val views1 = buildImmediateViews(context, seed, counter)
        for (id in ids) appWidgetManager.updateAppWidget(id, views1)

        // Phase 2: background — fetch rating + poster bitmap
        Thread {
            try {
                val item = WidgetDataFetcher.fetchFeatured(seed)
                val views2 = buildFullViews(context, item, counter)
                for (id in ids) appWidgetManager.updateAppWidget(id, views2)
            } catch (_: Exception) {
                // Phase 1 already showed title + poster URI
            }
        }.start()
    }

    private fun buildImmediateViews(context: Context, seed: WidgetDataFetcher.Seed, counter: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)
        setupClicks(context, views, seed)
        val typeLabel = if (seed.type == "movie") "Movie" else "TV Show"
        views.setTextViewText(R.id.widget_rank_badge, "#${seed.rank} $typeLabel")
        views.setTextViewText(R.id.widget_counter, "Refresh #$counter")
        // Use computed URL (seeds have empty posterUrl, real URL is posterUrlComputed)
        if (seed.posterUrlComputed.isNotBlank()) {
            views.setImageViewUri(R.id.widget_poster, Uri.parse(seed.posterUrlComputed))
        }
        return views
    }

    private fun buildFullViews(context: Context, item: WidgetFeaturedItem, counter: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)
        setupClicks(context, views, item.toSeed())
        val typeLabel = if (item.type == "Movie") "Movie" else "TV Show"
        views.setTextViewText(R.id.widget_rank_badge, "#${item.rank} $typeLabel")
        views.setTextViewText(R.id.widget_counter, "Refresh #$counter")
        // Set URI as fallback first (instant), then overlay with downloaded bitmap
        if (!item.posterUrl.isNullOrBlank()) {
            views.setImageViewUri(R.id.widget_poster, Uri.parse(item.posterUrl))
            val bmp = downloadPoster(item.posterUrl)
            if (bmp != null) views.setImageViewBitmap(R.id.widget_poster, bmp)
        }
        return views
    }

    private fun setupClicks(context: Context, views: RemoteViews, seed: WidgetDataFetcher.Seed) {
        val searchPi = android.app.PendingIntent.getActivity(
            context, 1001,
            Intent(context, SearchActivity::class.java).apply {
                action = Intent.ACTION_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_search_bar, searchPi)
        views.setOnClickPendingIntent(R.id.widget_brand, searchPi)

        val detailPi = android.app.PendingIntent.getActivity(
            context, 1002,
            Intent(context, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_IMDB_ID, seed.id)
                putExtra(DetailActivity.EXTRA_TITLE, seed.title)
                putExtra(DetailActivity.EXTRA_IMAGE_URL, seed.posterUrl)
                putExtra(DetailActivity.EXTRA_YEAR, seed.year)
                putExtra(DetailActivity.EXTRA_TYPE, if (seed.type == "movie") "Movie" else "Series")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
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
        private const val PREFS_NAME = "cinephantom_widget"
        private const val PREF_COUNTER = "refresh_counter"
    }
}
