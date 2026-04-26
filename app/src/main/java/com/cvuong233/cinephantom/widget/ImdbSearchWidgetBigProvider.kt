package com.cvuong233.cinephantom.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SearchActivity

/**
 * TEST H: Click handlers + real data fetch from Cinemeta.
 * Layout unchanged (flat, no nesting).
 */
class ImdbSearchWidgetBigProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        Thread {
            try {
                val item = WidgetDataFetcher.fetchRandomFeatured()
                for (id in appWidgetIds) {
                    val views = buildViews(context, item)
                    appWidgetManager.updateAppWidget(id, views)
                }
            } catch (_: Exception) {
                for (id in appWidgetIds) {
                    val views = buildViews(context, null)
                    appWidgetManager.updateAppWidget(id, views)
                }
            }
        }.apply { isDaemon = false; start() }
    }

    private fun buildViews(context: Context, item: WidgetFeaturedItem?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_imdb_search_big)

        // Search tap on brand area
        val searchPi = PendingIntent.getActivity(
            context, 1001,
            Intent(context, SearchActivity::class.java).apply {
                action = Intent.ACTION_SEARCH
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        views.setOnClickPendingIntent(R.id.widget_brand, searchPi)

        if (item == null) {
            views.setTextViewText(R.id.widget_title, "Tap to search")
            views.setTextViewText(R.id.widget_rank_badge, "")
            views.setTextViewText(R.id.widget_rating, "")
            views.setTextViewText(R.id.widget_year, "")
            return views
        }

        // Set text from fetched data
        views.setTextViewText(R.id.widget_rank_badge, "#${item.rank} ${item.type}")
        views.setTextViewText(R.id.widget_title, item.title)

        if (!item.imdbRating.isNullOrBlank()) {
            views.setTextViewText(R.id.widget_rating, "IMDb ${item.imdbRating}")
        } else {
            views.setTextViewText(R.id.widget_rating, "")
        }

        if (!item.year.isNullOrBlank()) {
            views.setTextViewText(R.id.widget_year, item.year)
        } else {
            views.setTextViewText(R.id.widget_year, "")
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
        views.setOnClickPendingIntent(R.id.widget_poster_label, detailPi)

        return views
    }

    companion object {
        private const val VISIBLE = 0
        private const val GONE = 8
    }
}
