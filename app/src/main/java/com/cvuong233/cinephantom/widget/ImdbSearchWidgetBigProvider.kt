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
import com.cvuong233.cinephantom.MainActivity
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class ImdbSearchWidgetBigProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        scheduleRefresh(context)
    }

    override fun onDisabled(context: Context) {
        cancelRefresh(context)
        // Clean up cached poster and seed
        File(context.filesDir, "widget_poster.jpg").delete()
        File(context.filesDir, "widget_seed.json").delete()
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

        // Single-phase: fetch fresh data, download poster, then update widget.
        // No Phase 1 update without poster — keeps old poster visible until new one loads.
        val pendingResult = goAsync()
        Thread {
            try {
                val seeds = WidgetDataFetcher.fetchSeeds(context)
                seeds?.randomOrNull()?.let { seed ->
                    showWidget(context, seed, ids, appWidgetManager)
                }
            } catch (_: Exception) {
                // Widget keeps previous state on failure — no blank flash
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private data class PosterResult(val bitmap: Bitmap, val seed: WidgetDataFetcher.Seed)

    private fun showWidget(
        context: Context,
        seed: WidgetDataFetcher.Seed,
        ids: List<Int>,
        appWidgetManager: AppWidgetManager,
    ) {
        val result = loadPosterBitmap(context, seed) ?: return
        val effectiveSeed = result.seed  // may be cached seed if poster fell back to cache
        val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)
        setupClicks(context, views, effectiveSeed)
        views.setImageViewBitmap(R.id.widget_poster, result.bitmap)
        views.setTextViewText(R.id.widget_rating, effectiveSeed.displayRating)

        for (id in ids) appWidgetManager.updateAppWidget(id, views)
    }

    /**
     * Load poster: try download first, fall back to cache on failure.
     * When falling back to cache, also returns the cached seed so poster and
     * click intent stay consistent — no more "wrong title" mismatch.
     */
    private fun loadPosterBitmap(context: Context, seed: WidgetDataFetcher.Seed): PosterResult? {
        val cacheFile = File(context.filesDir, "widget_poster.jpg")
        val seedCacheFile = File(context.filesDir, "widget_seed.json")

        val primaryUrl = seed.posterUrl.takeIf { it.isNotBlank() }
        val metahubUrl = if (seed.id.isNotBlank())
            "https://images.metahub.space/poster/small/${seed.id}/img" else null

        // Download fresh poster first
        var bmp: Bitmap? = null
        if (primaryUrl != null) bmp = downloadBitmap(primaryUrl)
        if (bmp == null && metahubUrl != null) bmp = downloadBitmap(metahubUrl)

        // Cache successful download + seed metadata
        if (bmp != null) {
            try {
                cacheFile.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                seedCacheFile.writeText(JSONObject().apply {
                    put("id", seed.id)
                    put("title", seed.title)
                    put("type", seed.type)
                    put("rank", seed.rank)
                    put("posterUrl", seed.posterUrl)
                    put("backdropUrl", seed.backdropUrl)
                    put("ratingText", seed.ratingText)
                    put("source", seed.source)
                }.toString())
            } catch (_: Exception) { /* non-critical */ }
            return PosterResult(bmp, seed)
        }

        // Fall back to cached poster + cached seed (keeps poster/intent consistent)
        val cachedBmp = try { BitmapFactory.decodeFile(cacheFile.absolutePath) } catch (_: Exception) { null }
        val cachedSeed = try {
            if (seedCacheFile.exists()) {
                val json = JSONObject(seedCacheFile.readText())
                WidgetDataFetcher.Seed(
                    id = json.optString("id", ""),
                    title = json.optString("title", ""),
                    type = json.optString("type", ""),
                    rank = json.optInt("rank", 0),
                    posterUrl = json.optString("posterUrl", ""),
                    backdropUrl = json.optString("backdropUrl", ""),
                    ratingText = json.optString("ratingText", ""),
                    source = json.optString("source", "imdb"),
                )
            } else null
        } catch (_: Exception) { null }

        return if (cachedBmp != null && cachedSeed != null) PosterResult(cachedBmp, cachedSeed) else null
    }

    private fun setupClicks(context: Context, views: RemoteViews, seed: WidgetDataFetcher.Seed) {
        val searchPi = PendingIntent.getActivity(
            context, 1001,
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_search_bar, searchPi)

        val detailPi = PendingIntent.getActivity(
            context, seed.id.hashCode(),
            Intent(context, DetailActivity::class.java).apply {
                action = "cinephantom.intent.action.DETAIL_${seed.id}"
                data = android.net.Uri.parse("cinephantom://detail/${seed.id}")
                putExtra(DetailActivity.EXTRA_IMDB_ID, seed.id)
                putExtra(DetailActivity.EXTRA_TITLE, seed.title)
                putExtra(DetailActivity.EXTRA_IMAGE_URL, seed.posterUrl)
                putExtra(DetailActivity.EXTRA_BACKDROP_URL, seed.backdropUrl)
                putExtra(DetailActivity.EXTRA_YEAR, "")
                putExtra(DetailActivity.EXTRA_TYPE, if (seed.type == "movie") "Movie" else "Series")
                putExtra(DetailActivity.EXTRA_FROM_WIDGET, true)
                // K-Drama seeds carry type="series" like any IMDb TV show, but they belong on
                // a distinct Discover tab — pass "kdrama" here so the back-press round trip
                // (navigateBackToDiscover -> MainActivity -> DiscoverFragment.focusOnTitle)
                // lands on the KDrama tab instead of IMDb TV.
                putExtra(
                    DetailActivity.EXTRA_RETURN_DISCOVER_TYPE,
                    if (seed.source == "kdrama") "kdrama" else seed.type
                )
                if (seed.source == "kdrama" && seed.ratingText.isNotBlank()) {
                    putExtra(DetailActivity.EXTRA_FUNDEX_RATING, seed.ratingText)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_poster, detailPi)
    }

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
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
