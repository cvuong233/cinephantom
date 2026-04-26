package com.cvuong233.cinephantom.data

import com.cvuong233.cinephantom.model.ImdbTitle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Batch-fetches IMDb ratings for search results using Cinemeta.
 * Runs up to 4 parallel requests, then calls back as each rating lands.
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
