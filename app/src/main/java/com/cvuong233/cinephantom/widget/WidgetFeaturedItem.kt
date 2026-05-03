package com.cvuong233.cinephantom.widget

/** Data for one featured item displayed on the home screen widget. */
data class WidgetFeaturedItem(
    val id: String,
    val title: String,
    val type: String,          // "Movie" or "TV Show"
    val rank: Int,             // 1–10
    val imdbRating: String?,
    val posterUrl: String?,
    val year: String?,
) {
    fun toSeed() = WidgetDataFetcher.Seed(
        id = id,
        title = title,
        type = if (type == "TV Show") "series" else "movie",
        year = year ?: "",
        rank = rank,
        posterUrl = posterUrl ?: "",
    )
}
