package com.cvuong233.cinephantom.widget

import android.app.AlarmManager
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

    override fun onEnabled(context: Context) {
        scheduleRefresh(context)
    }

    override fun onDisabled(context: Context) {
        cancelRefresh(context)
    }

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

        // Phase 1: instant — show cached seed if available
        val cached = WidgetDataFetcher.cachedSeed(context)
        if (cached != null) {
            showWidget(context, cached, ids, appWidgetManager)
        }

        // Phase 2: background — fetch fresh data, pick random, update
        val pendingResult = goAsync()
        Thread {
            try {
                val seeds = WidgetDataFetcher.fetchSeeds(context)
                seeds?.randomOrNull()?.let { seed ->
                    showWidget(context, seed, ids, appWidgetManager, downloadPoster = true)
                }
            } catch (_: Exception) {
                // Cached content from phase 1 already visible
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun showWidget(
        context: Context,
        seed: WidgetDataFetcher.Seed,
        ids: List<Int>,
        appWidgetManager: AppWidgetManager,
        downloadPoster: Boolean = false,
    ) {
        val typeLabel = if (seed.type == "movie") "Movie" else "TV Show"
        val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)
        setupClicks(context, views, seed)
        views.setTextViewText(R.id.widget_rank_badge, "#${seed.rank} $typeLabel")

        if (downloadPoster) {
            val bmp = downloadBitmap(seed.posterUrlComputed)
            if (bmp != null) views.setImageViewBitmap(R.id.widget_poster, bmp)
        }

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
                putExtra(DetailActivity.EXTRA_YEAR, "")
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
        private const val ALARM_REQ = 3001
        private const val REFRESH_INTERVAL_MS = 30 * 60 * 1000L

        private fun scheduleRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ImdbSearchWidgetBigProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME, REFRESH_INTERVAL_MS,
                REFRESH_INTERVAL_MS, pi,
            )
        }

        private fun cancelRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, ImdbSearchWidgetBigProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.cancel(pi)
        }
    }
}
