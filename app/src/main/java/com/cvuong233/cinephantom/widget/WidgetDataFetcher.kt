package com.cvuong233.cinephantom.widget

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Fetches featured items from live IMDb Most Popular charts.
 * Caches the JSON locally for instant display and offline resilience.
 */
object WidgetDataFetcher {

    data class Seed(
        val id: String,
        val title: String,
        val type: String,   // "movie" or "series"
        val rank: Int,
        val posterUrl: String = "",
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
    private const val PREF_CACHE_JSON = "charts_json"
    private const val PREF_CACHE_TIME = "charts_time"

    /** Max cache age before forcing a refresh attempt. */
    private const val MAX_CACHE_MS = 48 * 60 * 60 * 1000L

    /** Pick a random seed from cache. Returns null if cache is empty or expired. */
    fun cachedSeed(context: Context): Seed? {
        val seeds = cachedSeeds(context) ?: return null
        return seeds.randomOrNull()
    }

    /** Get all cached seeds. Returns null if no valid cache. */
    fun cachedSeeds(context: Context): List<Seed>? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(PREF_CACHE_JSON, null) ?: return null
            val cacheTime = prefs.getLong(PREF_CACHE_TIME, 0)
            if (System.currentTimeMillis() - cacheTime > MAX_CACHE_MS) return null
            parseSeeds(json)
        } catch (_: Exception) { null }
    }

    /**
     * Fetch fresh charts, update cache, return seeds.
     * Falls back to cache on network failure.
     */
    fun fetchSeeds(context: Context): List<Seed>? {
        return try {
            val request = Request.Builder()
                .url(LIVE_CHARTS_URL)
                .header("User-Agent", "CinePhantom/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return cachedSeeds(context)

            val body = response.body?.string() ?: return cachedSeeds(context)
            val seeds = parseSeeds(body)
            if (seeds.isEmpty()) return cachedSeeds(context)

            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_CACHE_JSON, body)
                .putLong(PREF_CACHE_TIME, System.currentTimeMillis())
                .apply()

            seeds
        } catch (_: Exception) {
            cachedSeeds(context)
        }
    }

    private fun parseSeeds(json: String): List<Seed> {
        val root = JSONObject(json)
        val allItems = mutableListOf<Seed>()

        for (key in listOf("movies", "tv")) {
            val arr = root.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                allItems.add(
                    Seed(
                        id = item.optString("imdb_id", ""),
                        title = item.optString("title", ""),
                        type = if (key == "tv") "series" else "movie",
                        rank = item.optInt("rank", i + 1),
                        posterUrl = item.optString("poster", ""),
                    )
                )
            }
        }
        return allItems
    }
}
