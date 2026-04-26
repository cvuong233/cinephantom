package com.cvuong233.cinephantom.widget

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches top 10 movies + top 10 TV shows from the Cinemeta catalog
 * and returns one randomly selected item.
 */
object WidgetDataFetcher {

    private const val MOVIE_CATALOG = "https://cinemeta-catalogs.strem.io/top/catalog/movie/top/list=.json"
    private const val SERIES_CATALOG = "https://cinemeta-catalogs.strem.io/top/catalog/series/top/list=.json"
    private const val TOP_N = 10

    data class FetchResult(
        val featured: WidgetFeaturedItem,
        val mask: Int, // bitmap of visibilities, unused for now
    )

    /** Fetch both catalogs, combine, pick one random item. Returns null on failure. */
    fun fetchRandomFeatured(): WidgetFeaturedItem? {
        val movies = fetchCatalog(MOVIE_CATALOG, TOP_N, "Movie")
        val series = fetchCatalog(SERIES_CATALOG, TOP_N, "TV Show")
        val all = movies + series
        if (all.isEmpty()) return null
        return all.random()
    }

    private fun fetchCatalog(urlStr: String, limit: Int, typeLabel: String): List<WidgetFeaturedItem> {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "CinePhantom-Widget/0.1")
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val root = JSONObject(json)
            val metas = root.optJSONArray("metas") ?: JSONArray()
            val count = minOf(metas.length(), limit)
            (0 until count).mapNotNull { i ->
                val meta = metas.optJSONObject(i) ?: return@mapNotNull null
                parseMeta(meta, i + 1, typeLabel)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseMeta(meta: JSONObject, rank: Int, typeLabel: String): WidgetFeaturedItem? {
        val id = meta.optString("id").takeIf { it.startsWith("tt") } ?: return null
        val title = meta.optString("name") ?: return null
        val imdbRating = meta.optString("imdbRating").takeIf { it.isNotBlank() }
        val posterPrefix = meta.optString("poster").takeIf { it.isNotBlank() }
        val posterUrl = posterPrefix?.replace("/small/", "/small/") // keep small for widget
        val year = meta.optString("year").takeIf { it.isNotBlank() }
            ?: meta.optString("releaseInfo").takeIf { it.isNotBlank() }
        return WidgetFeaturedItem(id, title, typeLabel, rank, imdbRating, posterUrl, year)
    }
}
