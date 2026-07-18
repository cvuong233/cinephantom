package com.cvuong233.cinephantom.widget

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Fetches featured items from live IMDb Most Popular charts and K-Drama FUNdex charts.
 * Caches the JSON locally for instant display and offline resilience.
 */
object WidgetDataFetcher {

    data class Seed(
        val id: String,
        val title: String,
        val type: String,   // "movie" or "series"
        val rank: Int,
        val posterUrl: String = "",
        val backdropUrl: String = "",
        val ratingText: String = "",
        val source: String = "imdb",  // "imdb" or "kdrama"
    ) {
        val posterUrlComputed: String get() =
            if (posterUrl.isNotBlank()) posterUrl
            else "https://images.metahub.space/poster/small/${id}/img"

        /** Full rating label for widget display. */
        val displayRating: String get() = when (source) {
            "kdrama" -> if (ratingText.isNotBlank()) "FUNdex $ratingText" else "FUNdex --"
            else     -> if (ratingText.isNotBlank()) "IMDb $ratingText" else "IMDb --"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private const val IMDB_CHARTS_URL =
        "https://cvuong233.github.io/agent-presentation/imdb_charts.json"
    private const val KDRAMA_CHARTS_URL =
        "https://cvuong233.github.io/agent-presentation/kdrama_charts.json"

    private const val PREFS_NAME = "cinephantom_imdb_cache"
    private const val PREF_CACHE_JSON = "charts_json"
    private const val PREF_CACHE_TIME = "charts_time"
    private const val PREF_KDRAMA_JSON = "kdrama_charts_json"
    private const val PREF_KDRAMA_TIME = "kdrama_charts_time"

    /** Max cache age before forcing a refresh attempt. */
    private const val MAX_CACHE_MS = 48 * 60 * 60 * 1000L

    /** Pick a random seed from cache. Returns null if cache is empty or expired. */
    fun cachedSeed(context: Context): Seed? {
        val seeds = cachedSeeds(context) ?: return null
        return seeds.randomOrNull()
    }

    /** Get all cached seeds (IMDb + K-Drama). Returns null if no valid cache at all. */
    fun cachedSeeds(context: Context): List<Seed>? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val combined = mutableListOf<Seed>()

            val imdbJson = prefs.getString(PREF_CACHE_JSON, null)
            val imdbTime = prefs.getLong(PREF_CACHE_TIME, 0)
            if (imdbJson != null && now - imdbTime <= MAX_CACHE_MS) {
                combined += parseImdbSeeds(imdbJson)
            }

            val kdramaJson = prefs.getString(PREF_KDRAMA_JSON, null)
            val kdramaTime = prefs.getLong(PREF_KDRAMA_TIME, 0)
            if (kdramaJson != null && now - kdramaTime <= MAX_CACHE_MS) {
                combined += parseKdramaSeeds(kdramaJson)
            }

            combined.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /**
     * Fetch fresh charts (IMDb + K-Drama), update caches, return merged seed list.
     * Falls back to cache on network failure.
     */
    fun fetchSeeds(context: Context): List<Seed>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val combined = mutableListOf<Seed>()

        // IMDb charts
        try {
            val response = client.newCall(
                Request.Builder().url(IMDB_CHARTS_URL)
                    .header("User-Agent", "CinePhantom/1.0").build()
            ).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val seeds = parseImdbSeeds(body)
                    if (seeds.isNotEmpty()) {
                        prefs.edit()
                            .putString(PREF_CACHE_JSON, body)
                            .putLong(PREF_CACHE_TIME, System.currentTimeMillis())
                            .apply()
                        combined += seeds
                    }
                }
            }
        } catch (_: Exception) { /* fall through to cache below */ }

        // K-Drama charts
        try {
            val response = client.newCall(
                Request.Builder().url(KDRAMA_CHARTS_URL)
                    .header("User-Agent", "CinePhantom/1.0").build()
            ).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val seeds = parseKdramaSeeds(body)
                    if (seeds.isNotEmpty()) {
                        prefs.edit()
                            .putString(PREF_KDRAMA_JSON, body)
                            .putLong(PREF_KDRAMA_TIME, System.currentTimeMillis())
                            .apply()
                        combined += seeds
                    }
                }
            }
        } catch (_: Exception) { /* fall through to cache below */ }

        return combined.takeIf { it.isNotEmpty() } ?: cachedSeeds(context)
    }

    private fun parseImdbSeeds(json: String): List<Seed> {
        val root = JSONObject(json)
        val allItems = mutableListOf<Seed>()
        for (key in listOf("movies", "tv")) {
            val arr = root.optJSONArray(key) ?: continue
            val limit = minOf(arr.length(), 20)
            for (i in 0 until limit) {
                val item = arr.optJSONObject(i) ?: continue
                val imdbId = item.optString("imdb_id", "")
                // Skip items with no resolved IMDb id — the widget click intent has no
                // TMDB id fallback, so a blank id here means a dead tap on the home screen.
                if (imdbId.isBlank()) continue
                val ratingStr = item.optString("rating", "")
                // Skip IMDb items rated below 7.0
                if (ratingStr.toFloatOrNull()?.let { it < 7.0f } == true) continue
                val backdropPath = item.optString("backdropPath", "")
                allItems.add(
                    Seed(
                        id = imdbId,
                        title = item.optString("title", ""),
                        type = if (key == "tv") "series" else "movie",
                        rank = item.optInt("rank", i + 1),
                        posterUrl = item.optString("poster", ""),
                        backdropUrl = if (backdropPath.isNotBlank()) "https://image.tmdb.org/t/p/w780$backdropPath" else "",
                        ratingText = ratingStr,
                        source = "imdb",
                    )
                )
            }
        }
        return allItems
    }

    fun findKdramaSeed(context: Context, imdbId: String): Seed? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val kdramaJson = prefs.getString(PREF_KDRAMA_JSON, null) ?: return null
            parseKdramaSeeds(kdramaJson).firstOrNull { it.id == imdbId }
        } catch (_: Exception) { null }
    }

    private fun parseKdramaSeeds(json: String): List<Seed> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("kdramas") ?: return emptyList()
        val allItems = mutableListOf<Seed>()
        val limit = minOf(arr.length(), 20)
        for (i in 0 until limit) {
            val item = arr.optJSONObject(i) ?: continue
            val imdbId = item.optString("imdb_id", "")
            if (imdbId.isBlank()) continue
            val backdropPath = item.optString("backdropPath", "")
            allItems.add(
                Seed(
                    id = imdbId,
                    title = item.optString("english_title", "").ifBlank {
                        item.optString("title", "")
                    },
                    type = "series",
                    rank = item.optInt("rank", i + 1),
                    posterUrl = item.optString("poster", ""),
                    backdropUrl = if (backdropPath.isNotBlank()) "https://image.tmdb.org/t/p/w780$backdropPath" else "",
                    ratingText = item.optString("fundex_score", ""),
                    source = "kdrama",
                )
            )
        }
        return allItems
    }
}
