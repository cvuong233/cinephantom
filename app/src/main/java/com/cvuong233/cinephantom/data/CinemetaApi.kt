package com.cvuong233.cinephantom.data

import com.cvuong233.cinephantom.model.CinemetaMetadata
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches enrichment metadata from the Stremio Cinemeta add-on.
 * No API key required. Uses public Cinemeta endpoints.
 *
 * Two endpoints are tried:
 * 1. Manifest-based lookup via catalog (series or movie)
 * 2. Direct meta query (fallback)
 */
class CinemetaApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Fetch enrichment metadata for a given IMDb ID and content type.
     * Returns null gracefully on failure so basic results are unaffected.
     */
    fun fetchMetadata(imdbId: String, contentType: String): Result<CinemetaMetadata> {
        return runCatching {
            // Try direct meta endpoint first (most reliable)
            val url = "https://v3-cinemeta.strem.io/meta/$contentType/$imdbId.json"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "CinePhantom/0.1")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Cinemeta request failed: ${response.code}")
                }

                val body = response.body?.string().orEmpty()
                val root = JSONObject(body)
                val meta = root.optJSONObject("meta") ?: error("No meta object in response")

                // Parse rating: prefer numeric "rating", otherwise fall back to imdbRating string.
                val numericRating = meta.optDouble("rating", -1.0).let { v ->
                    if (v > 0.0) v.toFloat() else null
                }

                val imdbRating = meta.optString("imdbRating")
                    .takeIf { it.isNotBlank() }
                    ?.toFloatOrNull()

                val rating = numericRating ?: imdbRating

                // Parse genres: "genres" is an array of strings, or object with "name" fields
                val genres = mutableListOf<String>()
                val genresArr = meta.optJSONArray("genres")
                if (genresArr != null) {
                    for (i in 0 until genresArr.length()) {
                        val g = genresArr.opt(i)
                        when (g) {
                            is String -> genres.add(g)
                            is JSONObject -> {
                                val name = g.optString("name").takeIf { it.isNotBlank() }
                                if (name != null) genres.add(name)
                            }
                        }
                        if (genres.size >= 3) break // Keep max 3 for compact UI
                    }
                }

                // Parse description: "description" or "plot" field
                val description = meta.optString("description")
                    .takeIf { it.isNotBlank() }
                    ?: meta.optString("plot").takeIf { it.isNotBlank() }
                    ?: meta.optString("plotLocal").takeIf { it.isNotBlank() }

                // Parse runtime if available
                val runtime = meta.optString("runtime")
                    .takeIf { it.isNotBlank() }

                CinemetaMetadata(
                    rating = rating,
                    genres = genres.takeIf { it.isNotEmpty() },
                    description = description?.takeIf { it.isNotBlank() },
                    runtime = runtime,
                    imdbRating = imdbRating?.toString(),
                )
            }
        }
    }
}
