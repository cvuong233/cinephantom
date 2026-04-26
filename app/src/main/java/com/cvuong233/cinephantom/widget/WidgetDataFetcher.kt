package com.cvuong233.cinephantom.widget

import com.cvuong233.cinephantom.data.CinemetaApi
import kotlin.random.Random

/**
 * Reliable featured-item provider for the big widget.
 * Uses a curated list of popular movies/TV shows and fetches
 * real-time metadata (rating, poster) from Cinemeta — the same
 * API the main app uses for search enrichment.
 */
object WidgetDataFetcher {

    private val api = CinemetaApi()

    private data class Seed(
        val id: String,
        val title: String,
        val type: String,   // "movie" or "series"
        val typeLabel: String, // "Movie" or "TV Show"
        val year: String,
    )

    /** Curated list of popular/classic items across genres. */
    private val SEEDS = listOf(
        Seed("tt0111161", "The Shawshank Redemption", "movie", "Movie", "1994"),
        Seed("tt0068646", "The Godfather", "movie", "Movie", "1972"),
        Seed("tt0468569", "The Dark Knight", "movie", "Movie", "2008"),
        Seed("tt1375666", "Inception", "movie", "Movie", "2010"),
        Seed("tt0133093", "The Matrix", "movie", "Movie", "1999"),
        Seed("tt0167260", "The Lord of the Rings: The Return of the King", "movie", "Movie", "2003"),
        Seed("tt0109830", "Forrest Gump", "movie", "Movie", "1994"),
        Seed("tt0071562", "The Godfather Part II", "movie", "Movie", "1974"),
        Seed("tt0099685", "Goodfellas", "movie", "Movie", "1990"),
        Seed("tt0120737", "The Lord of the Rings: The Fellowship of the Ring", "movie", "Movie", "2001"),
        Seed("tt0903747", "Breaking Bad", "series", "TV Show", "2008"),
        Seed("tt0944947", "Game of Thrones", "series", "TV Show", "2011"),
        Seed("tt7366338", "Chernobyl", "series", "TV Show", "2019"),
        Seed("tt1190634", "The Boys", "series", "TV Show", "2019"),
        Seed("tt3032476", "Better Call Saul", "series", "TV Show", "2015"),
        Seed("tt8111088", "The Mandalorian", "series", "TV Show", "2019"),
        Seed("tt5491994", "Planet Earth II", "series", "TV Show", "2016"),
        Seed("tt7920978", "Shōgun", "series", "TV Show", "2024"),
        Seed("tt4574334", "Stranger Things", "series", "TV Show", "2016"),
        Seed("tt2802850", "Fargo", "series", "TV Show", "2014"),
    )

    /** Pick a random seed, fetch its live metadata from Cinemeta. */
    fun fetchRandomFeatured(): WidgetFeaturedItem? {
        val seed = SEEDS.random()
        val meta = api.fetchMetadata(seed.id, seed.type).getOrNull()

        val rating = meta?.imdbRating?.takeIf { it.isNotBlank() }
            ?: meta?.rating?.let { String.format("%.1f", it) }

        val posterUrl = "https://images.metahub.space/poster/small/${seed.id}/img"
        val year = seed.year

        return WidgetFeaturedItem(
            id = seed.id,
            title = seed.title,
            type = seed.typeLabel,
            rank = Random.nextInt(1, 11),
            imdbRating = rating,
            posterUrl = posterUrl,
            year = year,
        )
    }
}
