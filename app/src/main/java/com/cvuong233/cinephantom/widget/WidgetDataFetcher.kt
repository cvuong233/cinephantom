package com.cvuong233.cinephantom.widget

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Fetches a random featured item for the big widget.
 * Uses raw HttpURLConnection — no OkHttp dependency.
 */
object WidgetDataFetcher {

    data class Seed(
        val id: String,
        val title: String,
        val type: String,   // "movie" or "series"
        val year: String,
        val rank: Int = Random.nextInt(1, 11),
        val posterUrl: String = "",
    ) {
        val posterUrlComputed: String get() =
            if (posterUrl.isNotBlank()) posterUrl
            else "https://images.metahub.space/poster/small/${id}/img"
    }

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

    /** Fetch rating for a pre-chosen seed. Returns full WidgetFeaturedItem. */
    fun fetchFeatured(seed: Seed): WidgetFeaturedItem {
        val imdbRating = fetchImdbRating(seed.id, seed.type)
        return WidgetFeaturedItem(
            id = seed.id,
            title = seed.title,
            type = if (seed.type == "movie") "Movie" else "TV Show",
            rank = seed.rank,
            imdbRating = imdbRating,
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
