package com.cvuong233.cinephantom.model

/**
 * Lightweight model parsed from the Cinemeta Stremio add-on manifest response.
 * Only the fields we care about for UI enrichment.
 */
data class CinemetaMetadata(
    val rating: Float? = null,
    val genres: List<String>? = null,
    val description: String? = null,
    val runtime: String? = null,
    val imdbRating: String? = null,
) {
    /** Best numeric rating available (Cinemeta "rating" field, typically out of 10) */
    val displayRating: String? get() {
        val r = rating
        if (r != null && r > 0f) return "★ %.1f".format(r)
        return imdbRating
    }
}
