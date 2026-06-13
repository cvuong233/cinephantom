package com.cvuong233.cinephantom.data

import com.cvuong233.cinephantom.model.ImdbTitle
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class KDramaChartsApi {

    fun fetchTopKDramas(): Result<Triple<String?, Nothing?, List<ImdbTitle>>> {
        return runCatching {
            val cacheBustedUrl = "https://cvuong233.github.io/agent-presentation/kdrama_charts.json?ts=${System.currentTimeMillis()}"
            val connection = URL(cacheBustedUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()

            val root = JSONObject(json)
            val updated = root.optString("updated").trim().ifBlank { null }
            val items = root.optJSONArray("kdramas")

            if (items == null) {
                return@runCatching Triple(updated, null, emptyList())
            }

            val titles = mutableListOf<ImdbTitle>()
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val imdbId = obj.optString("imdb_id").trim().ifBlank { null } ?: continue
                val title = obj.optString("title").trim().ifBlank { null } ?: continue
                val genre = obj.optString("genre").trim().ifBlank { "K-Drama" }
                val scoreLabel = obj.optString("fundex_score").trim().ifBlank { null }
                val year = obj.optString("year").trim().ifBlank { null }
                val poster = obj.optString("poster").trim().ifBlank { null }
                val tmdbId = obj.optInt("tmdb_id").takeIf { it > 0 }
                val rank = obj.optInt("rank", i + 1)

                titles.add(
                    ImdbTitle(
                        id = imdbId,
                        title = title,
                        typeLabel = "TV Series",
                        year = year,
                        cast = "Genres: $genre",
                        imageUrl = poster,
                        tmdbId = tmdbId,
                        rating = null,
                        ratingText = scoreLabel,
                        ratingSourceLabel = "FUNdex",
                        rankLabel = "#$rank",
                        genreLabel = genre,
                        secondaryLabel = null,
                        featuredMetricLabel = null,
                    )
                )
            }
            Triple(updated, null, titles)
        }
    }
}
