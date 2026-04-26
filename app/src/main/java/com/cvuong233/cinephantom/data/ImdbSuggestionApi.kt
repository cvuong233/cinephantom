package com.cvuong233.cinephantom.data

import com.cvuong233.cinephantom.model.ImdbTitle
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ImdbSuggestionApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun search(query: String): Result<List<ImdbTitle>> {
        return runCatching {
            val trimmed = query.trim()
            require(trimmed.isNotEmpty()) { "Query cannot be empty" }

            val firstChar = trimmed.first().lowercaseChar()
            val encoded = URLEncoder.encode(trimmed, Charsets.UTF_8.name())
            val url = "https://v2.sg.media-imdb.com/suggestion/$firstChar/$encoded.json"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) CinePhantom/0.1")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("IMDb suggestion request failed: ${response.code}")
                }

                val body = response.body?.string().orEmpty()
                val root = JSONObject(body)
                val data = root.optJSONArray("d") ?: return@use emptyList()

                buildList {
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        if (!id.startsWith("tt")) continue
                        add(
                            ImdbTitle(
                                id = id,
                                title = item.optString("l"),
                                typeLabel = item.optString("q").ifBlank { null },
                                year = item.opt("yr")?.toString() ?: item.opt("y")?.toString(),
                                cast = item.optString("s").ifBlank { null },
                                imageUrl = item.optJSONObject("i")?.optString("imageUrl")?.ifBlank { null },
                            )
                        )
                    }
                }
            }
        }
    }
}
