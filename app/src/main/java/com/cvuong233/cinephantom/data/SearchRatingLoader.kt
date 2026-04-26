package com.cvuong233.cinephantom.data

import com.cvuong233.cinephantom.model.ImdbTitle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Batch-fetches IMDb ratings for search results using the correct
 * Cinemeta endpoint per content type (series vs movie).
 *
 * Maps the IMDb suggestion type label to the right endpoint:
 * - TV Series, TV Mini Series, TV Episode → "series"
 * - Movie, TV Movie, Short → "movie"
 *
 * Falls back to the other type if the primary one returns no rating.
 */
class SearchRatingLoader(
    private val onRatingFetched: (ImdbTitle) -> Unit,
    private val onComplete: () -> Unit = {},
) {
    private val executor = Executors.newFixedThreadPool(4)
    private val api = CinemetaApi()
    private val finished = AtomicBoolean(false)

    fun load(titles: List<ImdbTitle>) {
        if (titles.isEmpty()) {
            onComplete()
            return
        }

        val latch = CountDownLatch(titles.size)

        for (title in titles) {
            executor.submit {
                try {
                    val rating = fetchRatingForTitle(title)
                    if (rating != null && rating > 0) {
                        onRatingFetched(title.copy(rating = rating))
                    }
                } catch (_: Exception) {
                    // Silently skip — search still works without ratings
                } finally {
                    latch.countDown()
                }
            }
        }

        executor.submit {
            latch.await(8, TimeUnit.SECONDS)
            if (finished.compareAndSet(false, true)) {
                onComplete()
            }
            shutdown()
        }
    }

    /**
     * Fetch rating using the correct content type based on the search result's type label.
     */
    private fun fetchRatingForTitle(title: ImdbTitle): Float? {
        val primaryType = mapToContentType(title.typeLabel)

        // Try primary type first
        val primaryResult = api.fetchMetadata(title.id, primaryType).getOrNull()
        val primaryRating = primaryResult?.rating
        if (primaryRating != null && primaryRating > 0) {
            return primaryRating
        }

        // No rating from primary — try the other type
        val fallbackType = if (primaryType == "movie") "series" else "movie"
        val fallbackResult = api.fetchMetadata(title.id, fallbackType).getOrNull()
        return fallbackResult?.rating?.takeIf { it > 0 }
    }

    /**
     * Map IMDb suggestion type labels to Cinemeta content types.
     */
    private fun mapToContentType(typeLabel: String?): String {
        return when (typeLabel) {
            "TV Series", "TV Mini Series", "TV Series (mini)",
            "TV Episode", "TV Special",
            "TV Movie" -> "series"
            else -> "movie"
        }
    }

    fun cancel() {
        if (finished.compareAndSet(false, true)) {
            onComplete()
            shutdown()
        }
    }

    private fun shutdown() {
        executor.shutdownNow()
    }
}
