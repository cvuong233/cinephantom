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
import android.util.Log
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SearchActivity
import java.net.HttpURLConnection
import java.net.URL

/**
 * Data fetch & RemoteViews builder for both small and big widgets.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val result = goAsync()
        Thread {
            try {
                val action = intent?.action
                Log.d(TAG, "onReceive action=$action")

                if (ImdbSearchWidgetBigProvider.ACTION_IMMEDIATE_REFRESH == action) {
                    val ids = pendingBigWidgetIds
                    if (ids != null) {
                        pendingBigWidgetIds = null
                        updateBigWidgetsWithData(context, ids)
                    }
                } else {
                    // Hourly alarm – refresh ALL widget types
                    val manager = AppWidgetManager.getInstance(context)

                    // Small widgets: static search bar
                    val smallIds = manager.getAppWidgetIds(
                        ComponentName(context, ImdbSearchWidgetProvider::class.java)
                    )
                    for (id in smallIds) {
                        val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_small)
                        views.setOnClickPendingIntent(R.id.widget_root, searchPi(context))
                        manager.updateAppWidget(id, views)
                    }

                    // Big widgets: fetch fresh data
                    val bigIds = manager.getAppWidgetIds(
                        ComponentName(context, ImdbSearchWidgetBigProvider::class.java)
                    )
                    if (bigIds.isNotEmpty()) {
                        pendingBigWidgetIds = bigIds
                        updateBigWidgetsWithData(context, bigIds)
                    }
                }
            } finally {
                result.finish()
            }
        }.apply { isDaemon = false; start() }
    }

    companion object {

        private const val TAG = "WidgetRefresh"
        private const val ALARM_REQ = 2001

        /** Widgets waiting for their first data fetch. */
        var pendingBigWidgetIds: IntArray? = null

        /** Called directly from ImdbSearchWidgetBigProvider.onUpdate() with a non-daemon thread. */
        fun doImmediateBigRefresh(context: Context, ids: IntArray) {
            if (ids.isEmpty()) return
            pendingBigWidgetIds = null // alarm path will have its own if needed
            Thread {
                try {
                    updateBigWidgetsWithData(context, ids)
                } catch (e: Exception) {
                    Log.e(TAG, "immediate refresh failed", e)
                }
            }.apply { isDaemon = false; start() }
        }

        fun scheduleRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ,
                Intent(context, WidgetRefreshReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME, AlarmManager.INTERVAL_HOUR,
                AlarmManager.INTERVAL_HOUR, pi,
            )
        }

        fun cancelRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ,
                Intent(context, WidgetRefreshReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.cancel(pi)
        }

        // ── internal ──

        private fun updateBigWidgetsWithData(context: Context, ids: IntArray) {
            Log.d(TAG, "fetching featured item for ${ids.size} widget(s)")
            val item = WidgetDataFetcher.fetchRandomFeatured()
            Log.d(TAG, "fetched: ${item?.title ?: "null"}")
            for (id in ids) {
                try {
                    val views = buildBigViews(context, item)
                    AppWidgetManager.getInstance(context).updateAppWidget(id, views)
                } catch (e: Exception) {
                    Log.e(TAG, "update widget $id failed", e)
                }
            }
        }

        private fun buildBigViews(context: Context, item: WidgetFeaturedItem?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)
            views.setOnClickPendingIntent(R.id.widget_search_section, searchPi(context))

            if (item == null) {
                // Show failure state so user can see the fetch ran
                views.setTextViewText(R.id.widget_title, "Retry later")
                views.setTextViewText(R.id.widget_rank_badge, "Failed")
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

            // Download poster thumbnail
            if (!item.posterUrl.isNullOrBlank()) {
                try {
                    val conn = URL(item.posterUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    conn.disconnect()
                    if (bmp != null) {
                        views.setImageViewBitmap(R.id.widget_poster, bmp)
                        views.setViewVisibility(R.id.widget_poster, VISIBLE)
                        views.setViewVisibility(R.id.widget_poster_label, GONE)
                    }
                } catch (_: Exception) { /* keep placeholder */ }
            }

            // Tap featured → detail
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

        private fun searchPi(context: Context): PendingIntent {
            val intent = Intent(context, SearchActivity::class.java).apply {
                action = Intent.ACTION_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context, 1001, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val VISIBLE = 0
        private const val GONE = 8
    }
}
