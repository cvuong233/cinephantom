package com.cvuong233.cinephantom.model

data class ImdbTitle(
    val id: String,
    val title: String,
    val typeLabel: String?,
    val year: String?,
    val cast: String?,
    val imageUrl: String?,
    val rating: Float? = null,
) {
    val imdbUrl: String
        get() = "https://www.imdb.com/title/$id/"
}
