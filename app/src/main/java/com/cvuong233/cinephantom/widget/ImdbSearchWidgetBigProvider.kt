package com.cvuong233.cinephantom.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SearchActivity

/**
 * Big widget: hero poster with rank overlay + compact search bar.
 * Delegates async data fetch to WidgetRefreshReceiver for guaranteed lifecycle.
 * Uses setImageViewUri so the launcher loads the poster — no bitmap IPC.
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
        // Delegate to WidgetRefreshReceiver — it has a proper BroadcastReceiver lifecycle
        // and uses goAsync() correctly. Direct threads + goAsync() here are flaky across launchers.
        WidgetRefreshReceiver.refreshBigWidgets(context)
    }

    companion object {
        private const val ALARM_REQ = 2003

        /**
         * Builds the RemoteViews for a big widget. Called by both onUpdate (via refresh)
         * and WidgetRefreshReceiver when data is ready.
         */
        internal fun buildBigViews(context: Context, item: WidgetFeaturedItem): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)

            // Search bar → search activity
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

            // Rank badge
            val typeLabel = if (item.type == "Movie") "Movie" else "TV Show"
            views.setTextViewText(R.id.widget_rank_badge, "#${item.rank} $typeLabel")

            // Poster — setImageViewUri tells the launcher to load the URL directly.
            // No bitmap IPC, no thread lifecycle issues.
            if (!item.posterUrl.isNullOrBlank()) {
                views.setImageViewUri(R.id.widget_poster, Uri.parse(item.posterUrl))
            }

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

        private fun scheduleHourlyRefresh(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            // Must include ACTION_APPWIDGET_UPDATE or onUpdate() never fires
            val pi = PendingIntent.getBroadcast(
                context, ALARM_REQ,
                Intent(context, ImdbSearchWidgetBigProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                },
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
                Intent(context, ImdbSearchWidgetBigProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarm.cancel(pi)
        }
    }
}
