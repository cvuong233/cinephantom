package com.cvuong233.cinephantom.widget

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Fetches featured items for the big widget.
 * Primary: live IMDb Most Popular charts from our API endpoint.
 * Fallback: hardcoded classic seeds if network is unavailable.
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

    // ── Hardcoded fallback (used when network is unavailable) ──

    private val SEEDS = listOf(
        Seed("tt0111161", "The Shawshank Redemption", "movie", "1994"),
        Seed("tt0068646", "The Godfather", "movie", "1972"),
        Seed("tt0468569", "The Dark Knight", "movie", "2008"),
        Seed("tt1375666", "Inception", "movie", "2010"),
        Seed("tt0133093", "The Matrix", "movie", "1999"),
        Seed("tt0167260", "The Lord of the Rings: The Return of the King", "movie", "2003"),
        Seed("tt0109830", "Forrest Gump", "movie", "1994"),
        Seed("tt0903747", "Breaking Bad", "series", "2008"),
        Seed("tt0944947", "Game of Thrones", "series", "2011"),
        Seed("tt7366338", "Chernobyl", "series", "2019"),
        Seed("tt1190634", "The Boys", "series", "2019"),
        Seed("tt3032476", "Better Call Saul", "series", "2015"),
        Seed("tt8111088", "The Mandalorian", "series", "2019"),
        Seed("tt5491994", "Planet Earth II", "series", "2016"),
        Seed("tt7920978", "Shōgun", "series", "2024"),
        Seed("tt4574334", "Stranger Things", "series", "2016"),
    )

    /** Pick a random seed — no network, instant. Used for phase-1 immediate display. */
    fun randomSeed(): Seed = SEEDS.random()

    /**
     * Fetch a random seed from live IMDb Most Popular charts.
     * Returns null on any failure (network, parse, etc.) so caller can fall back.
     */
    fun fetchLiveSeed(): Seed? {
        return try {
            val request = Request.Builder()
                .url(LIVE_CHARTS_URL)
                .header("User-Agent", "CinePhantom/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val root = JSONObject(body)

            // Combine movies and TV shows into one pool
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
                            year = item.optString("year", ""),
                            rank = item.optInt("rank", i + 1),
                            posterUrl = item.optString("poster", ""),
                            imdbRating = item.optDouble("rating", 0.0)
                                .takeIf { it > 0 }?.toString(),
                            votes = item.optString("votes", ""),
                        )
                    )
                }
            }

            if (allItems.isEmpty()) return null
            allItems.random()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetch a featured item using live IMDb charts data.
     * Returns a Seed from the live endpoint (with IMDb rating already populated),
     * or falls back to a random hardcoded seed.
     */
    fun fetchLiveOrFallbackSeed(): Seed {
        return fetchLiveSeed() ?: randomSeed()
    }

    /** Fetch rating for a pre-chosen seed. Returns full WidgetFeaturedItem. */
    fun fetchFeatured(seed: Seed): WidgetFeaturedItem {
        // If we already have an IMDb rating from live data, use it
        val rating = seed.imdbRating ?: fetchImdbRating(seed.id, seed.type)
        return WidgetFeaturedItem(
            id = seed.id,
            title = seed.title,
            type = if (seed.type == "movie") "Movie" else "TV Show",
            rank = seed.rank,
            imdbRating = rating,
            posterUrl = seed.posterUrlComputed,
            year = seed.year,
        )
    }

    /** Convenience: pick + fetch in one call (for simple use cases). */
    fun fetchRandomFeatured(): WidgetFeaturedItem = fetchFeatured(randomSeed())

    private fun fetchImdbRating(imdbId: String, contentType: String): String? {
        return try {
            val url = URL("https://v3-cinemeta.strem.io/meta/$contentType/$imdbId.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.instanceFollowRedirects = false
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val meta = JSONObject(text).optJSONObject("meta") ?: return null
            meta.optString("imdbRating").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
