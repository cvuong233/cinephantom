package com.cvuong233.cinephantom.data

import com.cvuong233.cinephantom.model.CinemetaMetadata

/**
 * Simple in-memory cache for Cinemeta enrichment results.
 * Keyed by IMDb ID (e.g. "tt1375666") and invalidated on size threshold.
 */
class CinemetaCache(
    private val maxSize: Int = 200,
) {
    private val cache = linkedMapOf<String, CinemetaMetadata>()

    @Synchronized
    fun get(imdbId: String): CinemetaMetadata? = cache[imdbId]

    @Synchronized
    fun put(imdbId: String, metadata: CinemetaMetadata) {
        if (cache.size >= maxSize) {
            // Evict oldest entry
            cache.remove(cache.keys.first())
        }
        cache[imdbId] = metadata
    }

    @Synchronized
    fun contains(imdbId: String): Boolean = cache.containsKey(imdbId)
}
