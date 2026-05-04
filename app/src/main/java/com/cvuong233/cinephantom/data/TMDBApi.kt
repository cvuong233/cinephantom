package com.cvuong233.cinephantom.data

import org.json.JSONObject

data class TMDBShowDetails(
    val seasons: Int = 0,
    val episodes: Int = 0,
    val episodeRuntime: List<Int> = emptyList(),
    val status: String? = null,
    val firstAirDate: String? = null,
    val lastAirDate: String? = null
)

data class TMDBCastMember(
    val name: String,
    val character: String? = null,
    val profilePath: String? = null
)

class TMDBApi {
    companion object {
        const val API_KEY = "1f54bd990f1cdfb230adb312546d765d"
        private const val BASE_URL = "https://api.themoviedb.org/3"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w92"

        fun profileImageUrl(path: String): String = "$IMAGE_BASE$path"
    }

    fun fetchShowDetails(tmdbId: Int): TMDBShowDetails? {
        return try {
            val json = java.net.URL("$BASE_URL/tv/$tmdbId?api_key=$API_KEY").readText()
            val root = JSONObject(json)
            if (root.has("success") && root.optBoolean("success") == false) return null
            TMDBShowDetails(
                seasons = root.optInt("number_of_seasons", 0),
                episodes = root.optInt("number_of_episodes", 0),
                episodeRuntime = run {
                    val arr = root.optJSONArray("episode_run_time")
                    if (arr != null && arr.length() > 0) {
                        (0 until arr.length()).map { arr.optInt(it) }
                    } else emptyList()
                },
                status = root.optString("status").ifBlank { null },
                firstAirDate = root.optString("first_air_date").ifBlank { null },
                lastAirDate = root.optString("last_air_date").ifBlank { null }
            )
        } catch (_: Exception) { null }
    }

    fun fetchCredits(tmdbId: Int, isSeries: Boolean): List<TMDBCastMember> {
        return try {
            val endpoint = if (isSeries) "tv" else "movie"
            val json = java.net.URL("$BASE_URL/$endpoint/$tmdbId/credits?api_key=$API_KEY").readText()
            val root = JSONObject(json)
            if (root.has("success") && root.optBoolean("success") == false) return emptyList()
            val castArr = root.optJSONArray("cast") ?: return emptyList()
            val cast = mutableListOf<TMDBCastMember>()
            val maxCast = Math.min(castArr.length(), 20)
            for (i in 0 until maxCast) {
                val c = castArr.optJSONObject(i) ?: continue
                cast.add(TMDBCastMember(
                    name = c.optString("name", ""),
                    character = c.optString("character", "").ifBlank { null },
                    profilePath = c.optString("profile_path", "").ifBlank { null }
                ))
            }
            cast
        } catch (_: Exception) { emptyList() }
    }

}
