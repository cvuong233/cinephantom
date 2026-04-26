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
import java.net.HttpURLConnection
import java.net.URL

/**
 * Periodic refresh handler for the home screen widget.
 * Fetch top catalog, pick random item, update RemoteViews.
 *
 * Also exposes a static helper used by ImdbSearchWidgetProvider.onUpdate()
 * so both initial render and periodic refresh share the same logic.
 */
class WidgetRefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        updateAllWidgets(context)
    }

    companion object {

        private const val ALARM_REQUEST_CODE = 2001
        private const val ACTION_REFRESH = "com.cvuong233.cinephantom.REFRESH_WIDGET"

        /** Schedule hourly refresh via AlarmManager. */
        fun scheduleRefresh(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, WidgetRefreshReceiver::class.java).apply { action = ACTION_REFRESH }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                AlarmManager.INTERVAL_HOUR,
                AlarmManager.INTERVAL_HOUR,
                pendingIntent,
            )
        }

        /** Cancel any previously scheduled refresh. */
        fun cancelRefresh(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, WidgetRefreshReceiver::class.java).apply { action = ACTION_REFRESH }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(pendingIntent)
        }

        /** Update all widget instances. Called from onUpdate() and the receiver. */
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, ImdbSearchWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return

            // Fetch featured item in background
            thread {
                val item = WidgetDataFetcher.fetchRandomFeatured()
                for (appWidgetId in ids) {
                    val views = buildViews(context, item)
                    manager.updateAppWidget(appWidgetId, views)
                }
            }
        }

        private fun thread(action: () -> Unit) {
            Thread(action).apply { isDaemon = true; start() }
        }

        private fun buildViews(context: Context, item: WidgetFeaturedItem?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_imdb_search)

            if (item == null) {
                views.setTextViewText(R.id.widget_title, "No data")
                return views
            }

            // Rank badge: "#3 Movie" or "#2 TV Show"
            val rankLabel = "#${item.rank} ${item.type}"
            views.setTextViewText(R.id.widget_rank_badge, rankLabel)

            // Title
            views.setTextViewText(R.id.widget_title, item.title)

            // Rating
            if (!item.imdbRating.isNullOrBlank()) {
                views.setTextViewText(R.id.widget_rating, "IMDb ${item.imdbRating}")
                views.setViewVisibility(R.id.widget_rating, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_rating, View.GONE)
            }

            // Year
            if (!item.year.isNullOrBlank()) {
                views.setTextViewText(R.id.widget_year, item.year)
                views.setViewVisibility(R.id.widget_year, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.widget_year, View.GONE)
            }

            // Poster
            if (!item.posterUrl.isNullOrBlank()) {
                try {
                    val url = URL(item.posterUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val bitmap = BitmapFactory.decodeStream(conn.inputStream)
                    conn.disconnect()
                    if (bitmap != null) {
                        views.setImageViewBitmap(R.id.widget_poster, bitmap)
                        views.setViewVisibility(R.id.widget_poster, View.VISIBLE)
                        views.setViewVisibility(R.id.widget_poster_label, View.GONE)
                    }
                } catch (_: Exception) {
                    // Keep placeholder
                }
            }

            // Click handlers
            val searchIntent = Intent(context, com.cvuong233.cinephantom.ui.search.SearchActivity::class.java).apply {
                action = Intent.ACTION_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val searchPendingIntent = PendingIntent.getActivity(
                context,
                1001,
                searchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_search_section, searchPendingIntent)

            // Featured section tap → DetailActivity
            val detailIntent = Intent(context, com.cvuong233.cinephantom.ui.detail.DetailActivity::class.java).apply {
                putExtra(com.cvuong233.cinephantom.ui.detail.DetailActivity.EXTRA_IMDB_ID, item.id)
                putExtra(com.cvuong233.cinephantom.ui.detail.DetailActivity.EXTRA_TITLE, item.title)
                putExtra(com.cvuong233.cinephantom.ui.detail.DetailActivity.EXTRA_IMAGE_URL, item.posterUrl)
                putExtra(com.cvuong233.cinephantom.ui.detail.DetailActivity.EXTRA_YEAR, item.year)
                putExtra(com.cvuong233.cinephantom.ui.detail.DetailActivity.EXTRA_TYPE, item.type)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val detailPendingIntent = PendingIntent.getActivity(
                context,
                1002,
                detailIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_featured_section, detailPendingIntent)

            return views
        }

        // Need View.GONE/VISIBLE constants
        private object View {
            const val GONE = 8
            const val VISIBLE = 0
        }
    }
}
