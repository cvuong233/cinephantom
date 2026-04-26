package com.cvuong233.cinephantom.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
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

/**
 * Periodic refresh handler for both small and big home screen widgets.
 * Fetches Cinemeta catalog top-10 movies/tv-shows and picks a random featured item.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Refresh all widget instances (both small and big)
        val manager = AppWidgetManager.getInstance(context)

        // Small widgets — just re-render the static search bar layout
        val smallIds = manager.getAppWidgetIds(
            ComponentName(context, ImdbSearchWidgetProvider::class.java)
        )
        if (smallIds.isNotEmpty()) {
            val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_small)
            val searchPi = buildSearchPendingIntent(context)
            views.setOnClickPendingIntent(R.id.widget_root, searchPi)
            for (id in smallIds) {
                manager.updateAppWidget(id, views)
            }
        }

        // Big widgets — fetch data and update with featured item
        val bigIds = manager.getAppWidgetIds(
            ComponentName(context, ImdbSearchWidgetBigProvider::class.java)
        )
        updateBigWidgetsWithData(context, bigIds, manager)
    }

    companion object {

        private const val ALARM_REQUEST_CODE = 2001

        /** Schedule hourly refresh via AlarmManager. */
        fun scheduleRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE,
                Intent(context, WidgetRefreshReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                AlarmManager.INTERVAL_HOUR,
                AlarmManager.INTERVAL_HOUR,
                pi,
            )
        }

        /** Cancel hourly refresh. */
        fun cancelRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQUEST_CODE,
                Intent(context, WidgetRefreshReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.cancel(pi)
        }

        /** Called by ImdbSearchWidgetBigProvider.onUpdate() — fetch data, build big widget views. */
        fun updateBigWidgets(context: Context, appWidgetIds: IntArray) {
            val manager = AppWidgetManager.getInstance(context)
            updateBigWidgetsWithData(context, appWidgetIds, manager)
        }

        // ── internal ──

        private fun updateBigWidgetsWithData(context: Context, ids: IntArray, manager: AppWidgetManager) {
            if (ids.isEmpty()) return
            thread {
                val item = WidgetDataFetcher.fetchRandomFeatured()
                for (id in ids) {
                    val views = buildBigViews(context, item)
                    manager.updateAppWidget(id, views)
                }
            }
        }

        private fun buildBigViews(context: Context, item: WidgetFeaturedItem?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)

            // Search bar click → SearchActivity
            val searchPi = buildSearchPendingIntent(context)
            views.setOnClickPendingIntent(R.id.widget_search_section, searchPi)

            if (item == null) {
                views.setTextViewText(R.id.widget_title, "Tap to search")
                return views
            }

            views.setTextViewText(R.id.widget_rank_badge, "#${item.rank} ${item.type}")
            views.setTextViewText(R.id.widget_title, item.title)

            if (!item.imdbRating.isNullOrBlank()) {
                views.setTextViewText(R.id.widget_rating, "IMDb ${item.imdbRating}")
                views.setViewVisibility(R.id.widget_rating, VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_rating, GONE)
            }

            if (!item.year.isNullOrBlank()) {
                views.setTextViewText(R.id.widget_year, item.year)
                views.setViewVisibility(R.id.widget_year, VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_year, GONE)
            }

            // Poster thumbnail
            if (!item.posterUrl.isNullOrBlank()) {
                try {
                    val url = URL(item.posterUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    conn.disconnect()
                    if (bmp != null) {
                        views.setImageViewBitmap(R.id.widget_poster, bmp)
                        views.setViewVisibility(R.id.widget_poster, VISIBLE)
                        views.setViewVisibility(R.id.widget_poster_label, GONE)
                    }
                } catch (_: Exception) { /* keep placeholder */ }
            }

            // Featured section click → DetailActivity
            val detail = Intent(context, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_IMDB_ID, item.id)
                putExtra(DetailActivity.EXTRA_TITLE, item.title)
                putExtra(DetailActivity.EXTRA_IMAGE_URL, item.posterUrl)
                putExtra(DetailActivity.EXTRA_YEAR, item.year)
                putExtra(DetailActivity.EXTRA_TYPE, item.type)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val detailPi = PendingIntent.getActivity(
                context, 1002, detail,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_featured_section, detailPi)

            return views
        }

        private fun buildSearchPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, SearchActivity::class.java).apply {
                action = Intent.ACTION_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context, 1001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun thread(action: () -> Unit) {
            Thread(action).apply { isDaemon = true; start() }
        }

        private const val GONE = 8
        private const val VISIBLE = 0
    }
}
