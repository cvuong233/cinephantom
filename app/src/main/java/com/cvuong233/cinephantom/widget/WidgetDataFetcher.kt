package com.cvuong233.cinephantom.widget

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Fetches featured items for the big widget.
 * Uses live IMDb Most Popular charts from our API endpoint.
 * Caches the last successful fetch for instant phase-1 display.
 */
object WidgetDataFetcher {

    data class Seed(
        val id: String,
        val title: String,
        val type: String,   // "movie" or "series"
        val year: String,
        val rank: Int = Random.nextInt(1, 11),
        val posterUrl: String = "",
        val imdbRating: String? = null,
        val votes: String? = null,
    ) {
        val posterUrlComputed: String get() =
            if (posterUrl.isNotBlank()) posterUrl
            else "https://images.metahub.space/poster/small/${id}/img"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private const val LIVE_CHARTS_URL =
        "https://cvuong233.github.io/agent-presentation/imdb_charts.json"
    private const val PREFS_NAME = "cinephantom_imdb_cache"
    private const val PREF_CACHE_JSON = "last_charts_json"
    private const val PREF_CACHE_TIME = "last_charts_time"

    /** Max cache age: 24 hours — refresh after that even if fetch fails. */
    private const val MAX_CACHE_MS = 24 * 60 * 60 * 1000L

    /** Read a random seed from the cached live data. Returns null if no valid cache. */
    fun cachedSeed(context: Context): Seed? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(PREF_CACHE_JSON, null) ?: return null
            val cacheTime = prefs.getLong(PREF_CACHE_TIME, 0)
            if (System.currentTimeMillis() - cacheTime > MAX_CACHE_MS) return null
            parseSeedsFromJson(json).randomOrNull()
        } catch (_: Exception) { null }
    }

    /**
     * Fetch live charts and update the cache.
     * Returns a list of parsed seeds or null on failure.
     */
    fun fetchAndCacheSeeds(context: Context): List<Seed>? {
        return try {
            val request = Request.Builder()
                .url(LIVE_CHARTS_URL)
                .header("User-Agent", "CinePhantom/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val seeds = parseSeedsFromJson(body)
            if (seeds.isEmpty()) return null

            // Cache the raw JSON and timestamp
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_CACHE_JSON, body)
                .putLong(PREF_CACHE_TIME, System.currentTimeMillis())
                .apply()

            seeds
        } catch (_: Exception) { null }
    }

    /**
     * Phase 1: get a seed from cache instantly (no network).
     * Falls back to null if no cache — caller shows a simple label.
     */
    fun instantSeed(context: Context): Seed? = cachedSeed(context)

    /**
     * Phase 2: fetch fresh data, update cache, pick a random seed.
     * Returns null if both fetch and cache fail.
     */
    fun liveSeed(context: Context): Seed? {
        val seeds = fetchAndCacheSeeds(context)
        return seeds?.randomOrNull() ?: cachedSeed(context)
    }

    private fun parseSeedsFromJson(json: String): List<Seed> {
        val root = JSONObject(json)
        val allItems = mutableListOf<Seed>()

        for (key in listOf("movies", "tv")) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val rating = item.optDouble("rating", 0.0)
                allItems.add(
                    Seed(
                        id = item.optString("imdb_id", ""),
                        title = item.optString("title", ""),
                        type = if (key == "tv") "series" else "movie",
                        year = item.optString("year", ""),
                        rank = item.optInt("rank", i + 1),
                        posterUrl = item.optString("poster", ""),
                        imdbRating = if (rating > 0) rating.toString() else null,
                        votes = item.optString("votes", ""),
                    )
                )
            }
        }
        return allItems
    }
}
