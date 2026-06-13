package com.cvuong233.cinephantom.data

import com.cvuong233.cinephantom.model.ImdbTitle
import org.json.JSONObject
import java.net.URLEncoder

data class TMDBNextEpisode(
    val name: String?,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val airDate: String?
)

data class TMDBShowDetails(
    val seasons: Int = 0,
    val episodes: Int = 0,
    val episodeRuntime: List<Int> = emptyList(),
    val status: String? = null,
    val firstAirDate: String? = null,
    val lastAirDate: String? = null,
    val nextEpisode: TMDBNextEpisode? = null
)

data class TMDBTitleDetails(
    val tmdbId: Int = 0,
    val mediaType: String,
    val title: String? = null,
    val overview: String? = null,
    val backdropPath: String? = null,
    val posterPath: String? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val rating: Float? = null,
    val year: String? = null,
    val releaseDate: String? = null,
    val showDetails: TMDBShowDetails? = null
)

data class TMDBVideo(
    val key: String,
    val name: String,
    val site: String,   // "YouTube"
    val type: String    // "Trailer", "Teaser", etc.
)

data class TMDBCastMember(
    val id: Int = 0,
    val name: String,
    val character: String? = null,
    val profilePath: String? = null
)

data class TMDBCrewMember(
    val id: Int = 0,
    val name: String,
    val job: String? = null,
    val department: String? = null,
    val profilePath: String? = null
)

data class TMDBPersonCredit(
    val id: Int,
    val mediaType: String,
    val title: String,
    val subtitle: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    var imdbId: String? = null
)

data class TMDBPersonDetails(
    val id: Int,
    val name: String,
    val biography: String? = null,
    val knownForDepartment: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    val placeOfBirth: String? = null,
    val profilePath: String? = null,
    val knownFor: List<TMDBPersonCredit> = emptyList()
)

class TMDBApi {
    companion object {
        const val API_KEY = "1f54bd990f1cdfb230adb312546d765d"
        private const val BASE_URL = "https://api.themoviedb.org/3"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w92"
        const val PROFILE_IMAGE_LARGE_BASE = "https://image.tmdb.org/t/p/w185"

        fun profileImageUrl(path: String): String = "$IMAGE_BASE$path"
        fun profileImageLargeUrl(path: String): String = "$PROFILE_IMAGE_LARGE_BASE$path"
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
                lastAirDate = root.optString("last_air_date").ifBlank { null },
                nextEpisode = root.optJSONObject("next_episode_to_air")?.let { ep ->
                    val s = ep.optInt("season_number", 0)
                    val e = ep.optInt("episode_number", 0)
                    if (s <= 0 && e <= 0) null else TMDBNextEpisode(
                        name = ep.optString("name").ifBlank { null },
                        seasonNumber = s,
                        episodeNumber = e,
                        airDate = ep.optString("air_date").ifBlank { null }
                    )
                }
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
            val maxCast = Math.min(castArr.length(), 30)
            for (i in 0 until maxCast) {
                val c = castArr.optJSONObject(i) ?: continue
                cast.add(TMDBCastMember(
                    id = c.optInt("id", 0),
                    name = c.optString("name", ""),
                    character = c.optString("character", "").ifBlank { null },
                    profilePath = c.optString("profile_path", "").ifBlank { null }
                ))
            }
            cast
        } catch (_: Exception) { emptyList() }
    }

    fun fetchDirectors(tmdbId: Int, isSeries: Boolean): List<TMDBCrewMember> {
        return try {
            val endpoint = if (isSeries) "tv" else "movie"
            val json = java.net.URL("$BASE_URL/$endpoint/$tmdbId/credits?api_key=$API_KEY").readText()
            val root = JSONObject(json)
            if (root.has("success") && root.optBoolean("success") == false) return emptyList()
            val crewArr = root.optJSONArray("crew") ?: return emptyList()
            val directors = mutableListOf<TMDBCrewMember>()
            for (i in 0 until crewArr.length()) {
                val c = crewArr.optJSONObject(i) ?: continue
                val job = c.optString("job", "").ifBlank { null }
                val department = c.optString("department", "").ifBlank { null }
                val isDirector = job.equals("Director", ignoreCase = true) ||
                    job.equals("Series Director", ignoreCase = true) ||
                    department.equals("Directing", ignoreCase = true)
                if (!isDirector) continue
                directors.add(
                    TMDBCrewMember(
                        id = c.optInt("id", 0),
                        name = c.optString("name", ""),
                        job = job,
                        department = department,
                        profilePath = c.optString("profile_path", "").ifBlank { null }
                    )
                )
            }
            directors.distinctBy { it.id.takeIf { id -> id > 0 } ?: it.name }.take(6)
        } catch (_: Exception) { emptyList() }
    }

    fun fetchVideos(tmdbId: Int, isSeries: Boolean): List<TMDBVideo> {
        return try {
            val endpoint = if (isSeries) "tv" else "movie"
            val json = java.net.URL("$BASE_URL/$endpoint/$tmdbId/videos?api_key=$API_KEY").readText()
            val root = JSONObject(json)
            val results = root.optJSONArray("results") ?: return emptyList()
            val videos = mutableListOf<TMDBVideo>()
            for (i in 0 until results.length()) {
                val v = results.optJSONObject(i) ?: continue
                videos.add(TMDBVideo(
                    key = v.optString("key", ""),
                    name = v.optString("name", ""),
                    site = v.optString("site", ""),
                    type = v.optString("type", "")
                ))
            }
            videos
        } catch (_: Exception) { emptyList() }
    }

    fun fetchTitleDetailsByImdb(imdbId: String, preferSeries: Boolean): TMDBTitleDetails? {
        return try {
            val findJson = java.net.URL("$BASE_URL/find/$imdbId?api_key=$API_KEY&external_source=imdb_id").readText()
            val root = JSONObject(findJson)
            val results = if (preferSeries) root.optJSONArray("tv_results") else root.optJSONArray("movie_results")
            val fallbackResults = if (preferSeries) root.optJSONArray("movie_results") else root.optJSONArray("tv_results")
            val item = when {
                results != null && results.length() > 0 -> results.optJSONObject(0)
                fallbackResults != null && fallbackResults.length() > 0 -> fallbackResults.optJSONObject(0)
                else -> null
            } ?: return null

            val resolvedMediaType = item.optString("media_type").ifBlank {
                if (preferSeries) "tv" else "movie"
            }
            val tmdbId = item.optInt("id", 0)
            if (tmdbId <= 0) return null

            val title = item.optString("title").ifBlank { item.optString("name").ifBlank { null } }
            val overview = item.optString("overview").ifBlank { null }
            val backdropPath = item.optString("backdrop_path", "").ifBlank { null }
            val posterPath = item.optString("poster_path", "").ifBlank { null }
            val rating = item.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.toFloat()
            val year = item.optString("release_date").ifBlank { item.optString("first_air_date") }
                .takeIf { it.isNotBlank() }
                ?.take(4)

            val detailJson = java.net.URL("$BASE_URL/$resolvedMediaType/$tmdbId?api_key=$API_KEY").readText()
            val detailRoot = JSONObject(detailJson)
            if (detailRoot.has("success") && detailRoot.optBoolean("success") == false) return null

            val genres = buildList {
                val genresArr = detailRoot.optJSONArray("genres")
                if (genresArr != null) {
                    for (i in 0 until genresArr.length()) {
                        val genre = genresArr.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
                        if (genre.isNotBlank()) add(genre)
                    }
                }
            }

            val runtimeMinutes = if (resolvedMediaType == "tv") {
                val runtimes = detailRoot.optJSONArray("episode_run_time")
                if (runtimes != null && runtimes.length() > 0) runtimes.optInt(0).takeIf { it > 0 } else null
            } else {
                detailRoot.optInt("runtime", 0).takeIf { it > 0 }
            }

            val showDetails = if (resolvedMediaType == "tv") {
                TMDBShowDetails(
                    seasons = detailRoot.optInt("number_of_seasons", 0),
                    episodes = detailRoot.optInt("number_of_episodes", 0),
                    episodeRuntime = run {
                        val arr = detailRoot.optJSONArray("episode_run_time")
                        if (arr != null && arr.length() > 0) {
                            (0 until arr.length()).map { arr.optInt(it) }
                        } else emptyList()
                    },
                    status = detailRoot.optString("status").ifBlank { null },
                    firstAirDate = detailRoot.optString("first_air_date").ifBlank { null },
                    lastAirDate = detailRoot.optString("last_air_date").ifBlank { null },
                    nextEpisode = detailRoot.optJSONObject("next_episode_to_air")?.let { ep ->
                        val s = ep.optInt("season_number", 0)
                        val e = ep.optInt("episode_number", 0)
                        if (s <= 0 && e <= 0) null else TMDBNextEpisode(
                            name = ep.optString("name").ifBlank { null },
                            seasonNumber = s,
                            episodeNumber = e,
                            airDate = ep.optString("air_date").ifBlank { null }
                        )
                    }
                )
            } else null

            TMDBTitleDetails(
                tmdbId = tmdbId,
                mediaType = resolvedMediaType,
                title = title,
                overview = detailRoot.optString("overview").ifBlank { overview },
                backdropPath = detailRoot.optString("backdrop_path", "").ifBlank { backdropPath },
                posterPath = detailRoot.optString("poster_path", "").ifBlank { posterPath },
                genres = genres,
                runtimeMinutes = runtimeMinutes,
                rating = detailRoot.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.toFloat() ?: rating,
                year = detailRoot.optString("release_date").ifBlank { detailRoot.optString("first_air_date") }
                    .takeIf { it.isNotBlank() }
                    ?.take(4) ?: year,
                releaseDate = if (resolvedMediaType == "movie")
                    detailRoot.optString("release_date").ifBlank { null }
                else null,
                showDetails = showDetails
            )
        } catch (_: Exception) { null }
    }

    fun fetchPersonDetails(personId: Int): TMDBPersonDetails? {
        return try {
            val detailsJson = java.net.URL("$BASE_URL/person/$personId?api_key=$API_KEY").readText()
            val creditsJson = java.net.URL("$BASE_URL/person/$personId/combined_credits?api_key=$API_KEY").readText()
            val detailsRoot = JSONObject(detailsJson)
            if (detailsRoot.has("success") && detailsRoot.optBoolean("success") == false) return null
            val creditsRoot = JSONObject(creditsJson)
            val castArr = creditsRoot.optJSONArray("cast")
            val scoredCredits = mutableListOf<Pair<Double, TMDBPersonCredit>>()
            if (castArr != null) {
                for (i in 0 until castArr.length()) {
                    val item = castArr.optJSONObject(i) ?: continue
                    val title = item.optString("title").ifBlank { item.optString("name") }
                    if (title.isBlank()) continue

                    val mediaType = item.optString("media_type", "").ifBlank { "movie" }
                    if (mediaType != "movie" && mediaType != "tv") continue

                    val character = item.optString("character").ifBlank { null }
                    val subtitle = character ?: item.optString("media_type")
                    val posterPath = item.optString("poster_path", "").ifBlank { null }
                    val backdropPath = item.optString("backdrop_path", "").ifBlank { null }
                    val releaseDate = item.optString("release_date").ifBlank { item.optString("first_air_date").ifBlank { null } }

                    val voteCount = item.optInt("vote_count", 0)
                    val popularity = item.optDouble("popularity", 0.0)
                    val voteAverage = item.optDouble("vote_average", 0.0)
                    val isSelfCredit = character.equals("Self", ignoreCase = true) || character.equals("Himself", ignoreCase = true) || character.equals("Herself", ignoreCase = true)
                    val hasPoster = posterPath != null
                    val score =
                        (if (hasPoster) 2000.0 else 0.0) +
                        (if (isSelfCredit) -4000.0 else 0.0) +
                        (voteCount * 3.0) +
                        (popularity * 20.0) +
                        (voteAverage * 10.0) +
                        (if (mediaType == "tv") 120.0 else 0.0)

                    scoredCredits.add(
                        score to TMDBPersonCredit(
                            id = item.optInt("id", 0),
                            mediaType = mediaType,
                            title = title,
                            subtitle = subtitle.ifBlank { null },
                            posterPath = posterPath,
                            backdropPath = backdropPath,
                            releaseDate = releaseDate
                        )
                    )
                }
            }
            val credits = scoredCredits
                .sortedByDescending { it.first }
                .map { it.second }
                .distinctBy { "${it.mediaType}:${it.id}:${it.title}" }
                .take(12)
            TMDBPersonDetails(
                id = detailsRoot.optInt("id", personId),
                name = detailsRoot.optString("name", ""),
                biography = detailsRoot.optString("biography").ifBlank { null },
                knownForDepartment = detailsRoot.optString("known_for_department").ifBlank { null },
                birthday = detailsRoot.optString("birthday").ifBlank { null },
                deathday = detailsRoot.optString("deathday").ifBlank { null },
                placeOfBirth = detailsRoot.optString("place_of_birth").ifBlank { null },
                profilePath = detailsRoot.optString("profile_path", "").ifBlank { null },
                knownFor = credits
            )
        } catch (_: Exception) { null }
    }

    fun fetchImdbIdForTitle(tmdbId: Int, mediaType: String): String? {
        return try {
            val endpoint = if (mediaType == "tv") "tv" else "movie"
            val json = java.net.URL("$BASE_URL/$endpoint/$tmdbId/external_ids?api_key=$API_KEY").readText()
            val root = JSONObject(json)
            root.optString("imdb_id").ifBlank { null }
        } catch (_: Exception) { null }
    }

    fun searchTitles(query: String): Result<List<ImdbTitle>> {
        return runCatching {
            val trimmed = query.trim()
            require(trimmed.isNotEmpty()) { "Query cannot be empty" }

            val encoded = URLEncoder.encode(trimmed, Charsets.UTF_8.name())
            val json = java.net.URL(
                "$BASE_URL/search/multi?api_key=$API_KEY&query=$encoded&include_adult=false"
            ).readText()
            val root = JSONObject(json)
            val results = root.optJSONArray("results") ?: return@runCatching emptyList()

            buildList {
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val mediaType = item.optString("media_type", "")
                    if (mediaType != "movie" && mediaType != "tv") continue

                    val tmdbId = item.optInt("id", 0)
                    if (tmdbId <= 0) continue

                    val imdbId = fetchImdbIdForTitle(tmdbId, mediaType) ?: continue
                    val resolvedTitle = item.optString("title").ifBlank { item.optString("name") }
                    if (resolvedTitle.isBlank()) continue

                    val year = item.optString("release_date").ifBlank { item.optString("first_air_date") }
                        .takeIf { it.isNotBlank() }
                        ?.take(4)
                    val posterPath = item.optString("poster_path", "").ifBlank { null }
                    val overview = item.optString("overview", "").ifBlank { null }
                    val rating = item.optDouble("vote_average", 0.0).takeIf { it > 0.0 }?.toFloat()

                    add(
                        ImdbTitle(
                            id = imdbId,
                            title = resolvedTitle,
                            typeLabel = if (mediaType == "tv") "TV Series" else "Movie",
                            year = year,
                            cast = overview,
                            imageUrl = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                            rating = rating
                        )
                    )
                }
            }
        }
    }
}
