package com.cvuong233.cinephantom.data

import com.cvuong233.cinephantom.model.ImdbTitle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Batch-fetches IMDb ratings for search results.
 *
 * Uses [RatingFetcher] which shares a global companion cache with the
 * detail page — so the first lookup (search card or detail page) caches
 * the result, and every subsequent lookup reads from the same cache.
 *
 * Only one Cinemeta request per IMDb ID is ever made, regardless of how
 * many times or from where the rating is accessed.
 */
class SearchRatingLoader(
    private val onRatingFetched: (ImdbTitle) -> Unit,
    private val onComplete: () -> Unit = {},
) {
    private val executor = Executors.newFixedThreadPool(4)
    private val fetcher = RatingFetcher()
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
                    val rating = fetcher.fetchRating(title.id)
                    if (rating != null && rating > 0) {
                        onRatingFetched(title.copy(rating = rating))
                    }
                } catch (_: Exception) {
                    // Silently skip — search works without ratings
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
