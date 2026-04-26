package com.cvuong233.cinephantom.data

import java.util.concurrent.ConcurrentHashMap

/**
 * IMDb rating fetcher using Cinemeta.
 * Tries movie endpoint first, falls back to series if no rating found.
 * Cache is shared globally across all instances.
 */
class RatingFetcher {

    private val api = CinemetaApi()

    companion object {
        private val sharedCache = ConcurrentHashMap<String, Float>()
    }

    private val cache get() = sharedCache

    /**
     * Fetch rating for an IMDb ID. Returns the numeric rating, or null on failure.
     * Tries movie endpoint first, then series if movie returns no rating.
     */
    fun fetchRating(imdbId: String): Float? {
        // Check cache first
        sharedCache[imdbId]?.let { return it.takeIf { v -> v > 0f } }

        // Try movie
        val movieRating = fetchWithType(imdbId, "movie")
        if (movieRating != null) {
            sharedCache[imdbId] = movieRating
            return movieRating
        }

        // Try series
        val seriesRating = fetchWithType(imdbId, "series")
        if (seriesRating != null) {
            sharedCache[imdbId] = seriesRating
            return seriesRating
        }

        sharedCache[imdbId] = -1f
        return null
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
