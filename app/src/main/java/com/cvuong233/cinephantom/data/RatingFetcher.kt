package com.cvuong233.cinephantom.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Lightweight IMDb rating fetcher using Cinemeta.
 * Returns raw numeric rating for proper rolling digit animation.
 */
class RatingFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val cache = ConcurrentHashMap<String, Float>()

    /**
     * Fetch rating for an IMDb ID. Returns the numeric rating, or null on failure.
     */
    fun fetchRating(imdbId: String): Float? {
        cache[imdbId]?.let { return it.takeIf { it > 0f } }

        return try {
            val url = "https://v3-cinemeta.strem.io/meta/movie/$imdbId.json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "CinePhantom/0.1")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Try series type
                    val seriesUrl = "https://v3-cinemeta.strem.io/meta/series/$imdbId.json"
                    val seriesRequest = Request.Builder()
                        .url(seriesUrl)
                        .header("User-Agent", "CinePhantom/0.1")
                        .build()

                    client.newCall(seriesRequest).execute().use { seriesResponse ->
                        if (!seriesResponse.isSuccessful) {
                            cache[imdbId] = -1f
                            return null
                        }
                        parseRating(seriesResponse.body?.string().orEmpty())
                    }
                } else {
                    parseRating(response.body?.string().orEmpty())
                }
            }
        } catch (e: Exception) {
            cache[imdbId] = -1f
            null
        }
    }

    private fun parseRating(json: String): Float? {
        // Find "rating" : number field
        val ratingPattern = """"rating"\s*:\s*([\d.]+)""".toRegex()
        val match = ratingPattern.find(json)
        val ratingValue = match?.groupValues?.getOrNull(1)

        if (ratingValue != null) {
            val rating = ratingValue.toFloatOrNull()
            if (rating != null && rating > 0) {
                return rating
            }
        }

        // Try imdbRating string field
        val imdbPattern = """"imdbRating"\s*:\s*"([\d.]+)"""".toRegex()
        val imdbMatch = imdbPattern.find(json)
        val imdbValue = imdbMatch?.groupValues?.getOrNull(1)
        if (imdbValue != null) {
            val rating = imdbValue.toFloatOrNull()
            if (rating != null && rating > 0) {
                return rating
            }
        }

        return null
    }
}
