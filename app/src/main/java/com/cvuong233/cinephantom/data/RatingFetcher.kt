package com.cvuong233.cinephantom.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * IMDb rating fetcher.
 *
 * Order:
 * 1. Shared in-memory cache
 * 2. Our hosted Top IMDb charts dataset (movies + tv top 100)
 * 3. Cinemeta fallback for titles outside the chart dataset
 */
class RatingFetcher {

    private val api = CinemetaApi()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val LIVE_CHARTS_URL =
            "https://cvuong233.github.io/agent-presentation/imdb_charts.json"
        private const val CHART_CACHE_MS = 6 * 60 * 60 * 1000L

        private val sharedCache = ConcurrentHashMap<String, Float>()
        private val chartRatings = ConcurrentHashMap<String, Float>()

        @Volatile private var chartCacheLoadedAt = 0L
    }

    /**
     * Fetch rating for an IMDb ID. Returns the numeric rating, or null on failure.
     */
    fun fetchRating(imdbId: String): Float? {
        sharedCache[imdbId]?.let { return it.takeIf { v -> v > 0f } }

        loadChartRatingsIfNeeded()
        chartRatings[imdbId]?.takeIf { it > 0f }?.let {
            sharedCache[imdbId] = it
            return it
        }

        val movieRating = fetchWithType(imdbId, "movie")
        if (movieRating != null) {
            sharedCache[imdbId] = movieRating
            return movieRating
        }

        val seriesRating = fetchWithType(imdbId, "series")
        if (seriesRating != null) {
            sharedCache[imdbId] = seriesRating
            return seriesRating
        }

        sharedCache[imdbId] = -1f
        return null
    }

    private fun loadChartRatingsIfNeeded() {
        val now = System.currentTimeMillis()
        if (chartRatings.isNotEmpty() && now - chartCacheLoadedAt < CHART_CACHE_MS) return

        synchronized(chartRatings) {
            val insideNow = System.currentTimeMillis()
            if (chartRatings.isNotEmpty() && insideNow - chartCacheLoadedAt < CHART_CACHE_MS) return

            try {
                val request = Request.Builder()
                    .url(LIVE_CHARTS_URL)
                    .header("User-Agent", "CinePhantom/1.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return
                    val body = response.body?.string().orEmpty()
                    if (body.isBlank()) return

                    val root = JSONObject(body)
                    val fresh = ConcurrentHashMap<String, Float>()
                    listOf("movies", "tv").forEach { key ->
                        val arr = root.optJSONArray(key) ?: return@forEach
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val imdbId = item.optString("imdb_id", "").trim()
                            val rating = item.optString("rating", "").trim().toFloatOrNull()
                            if (imdbId.isNotBlank() && rating != null && rating > 0f) {
                                fresh[imdbId] = rating
                            }
                        }
                    }

                    if (fresh.isNotEmpty()) {
                        chartRatings.clear()
                        chartRatings.putAll(fresh)
                        chartCacheLoadedAt = insideNow
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun fetchWithType(imdbId: String, contentType: String): Float? {
        return try {
            val result = api.fetchMetadata(imdbId, contentType)
            result.getOrNull()?.rating?.takeIf { it > 0 }
        } catch (_: Exception) {
            null
        }
    }
}
