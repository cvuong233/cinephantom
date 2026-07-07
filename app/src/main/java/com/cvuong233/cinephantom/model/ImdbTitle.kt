package com.cvuong233.cinephantom.model

data class ImdbTitle(
    val id: String,
    val title: String,
    val typeLabel: String?,
    val year: String?,
    val cast: String?,
    val imageUrl: String?,
    val backdropUrl: String? = null,
    val tmdbId: Int? = null,
    val rating: Float? = null,
    val ratingText: String? = null,
    val ratingSourceLabel: String? = null,
    val rankLabel: String? = null,
    val genreLabel: String? = null,
    val secondaryLabel: String? = null,
    val featuredMetricLabel: String? = null,
) {
    val imdbUrl: String
        get() = "https://www.imdb.com/title/$id/"

    // Landscape card art — only the real backdrop qualifies. Never fall back to the
    // portrait poster here: a poster stretched/cropped into a 16:9 card reads as the
    // "wrong orientation" bug. Callers show a dark placeholder while this is null.
    val landscapeImageUrl: String?
        get() = backdropUrl
}
