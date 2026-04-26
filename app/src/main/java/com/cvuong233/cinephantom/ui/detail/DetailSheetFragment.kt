package com.cvuong233.cinephantom.ui.detail

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import coil.size.ViewSizeResolver
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.search.ShimmerView
import kotlin.concurrent.thread

class DetailSheetFragment : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_TYPE = "type"
        private const val ARG_YEAR = "year"
        private const val ARG_CAST = "cast"
        private const val ARG_IMAGE_URL = "image_url"
        private const val ARG_IMDB_ID = "imdb_id"
        private const val ARG_GENRES = "genres"

        fun newInstance(
            title: String,
            type: String?,
            year: String?,
            cast: String?,
            imageUrl: String?,
            imdbId: String,
        ): DetailSheetFragment {
            val args = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_TYPE, type)
                putString(ARG_YEAR, year)
                putString(ARG_CAST, cast)
                putString(ARG_IMAGE_URL, imageUrl)
                putString(ARG_IMDB_ID, imdbId)
            }
            return DetailSheetFragment().apply { arguments = args }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        return inflater.inflate(R.layout.fragment_detail_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = arguments ?: return

        val imdbId = args.getString(ARG_IMDB_ID) ?: ""
        val title = args.getString(ARG_TITLE, "Unknown")
        val type = args.getString(ARG_TYPE)
        val year = args.getString(ARG_YEAR)
        val cast = args.getString(ARG_CAST)
        val imageUrl = args.getString(ARG_IMAGE_URL)

        // Title
        view.findViewById<TextView>(R.id.detail_title).text = title

        // Meta chip
        view.findViewById<TextView>(R.id.detail_meta).text = listOfNotNull(type, year).joinToString(" • ").ifBlank { "No info" }

        // Rating skeleton — show placeholder initially, fetch asynchronously
        val ratingText = view.findViewById<TextView>(R.id.detail_rating)
        ratingText.text = "IMDb --"
        thread {
            try {
                val fetcher = com.cvuong233.cinephantom.data.RatingFetcher()
                val rating = fetcher.fetchRating(imdbId)
                if (rating != null && rating > 0) {
                    view.post {
                        ratingText.text = "IMDb %.1f".format(rating)
                    }
                } else {
                    view.post { ratingText.visibility = View.GONE }
                }
            } catch (_: Exception) {
                view.post { ratingText.visibility = View.GONE }
            }
        }

        // Poster with shimmer
        val posterImage = view.findViewById<android.widget.ImageView>(R.id.detail_poster)
        val posterShimmer = view.findViewById<ShimmerView>(R.id.sheet_poster_shimmer)
        posterShimmer.startShimmer()
        if (!imageUrl.isNullOrBlank()) {
            posterImage.load(imageUrl) {
                crossfade(true)
                placeholder(android.R.color.transparent)
                error(android.R.color.transparent)
                listener(
                    onStart = { posterShimmer.startShimmer() },
                    onSuccess = { _, _ -> posterShimmer.stopAndHide() },
                    onError = { _, _ -> posterShimmer.stopAndHide() },
                )
            }
        }

        // Genres skeleton
        val genresContainer = view.findViewById<LinearLayout>(R.id.detail_genres_container)
        val genresSkeleton = view.findViewById<View>(R.id.detail_genres_skeleton)

        // Start shimmer on skeleton
        val genresSkeletonGroup = genresSkeleton as? ViewGroup
        genresSkeleton.post {
            genresSkeletonGroup?.let { g ->
                for (i in 0 until g.childCount) {
                    val child = g.getChildAt(i)
                    if (child is ShimmerView) {
                        child.startShimmer()
                    }
                }
            }
        }

        // Genres — fetch from Cinemeta
        thread {
            try {
                val json = java.net.URL("https://v3-cinemeta.strem.io/meta/movie/$imdbId.json").readText()
                var genres: List<String>? = parseGenres(json)
                if (genres == null) {
                    val seriesJson = java.net.URL("https://v3-cinemeta.strem.io/meta/series/$imdbId.json").readText()
                    genres = parseGenres(seriesJson)
                }
                val finalGenres = genres
                if (finalGenres != null && finalGenres.isNotEmpty()) {
                    view.post {
                        genresSkeleton.visibility = View.GONE
                        for (g in finalGenres) {
                            val chip = layoutInflater.inflate(
                                R.layout.item_genre_chip, genresContainer, false
                            ) as TextView
                            chip.text = g
                            genresContainer.addView(chip)
                        }
                    }
                } else {
                    view.post { genresSkeleton.visibility = View.GONE }
                }
            } catch (_: Exception) {
                view.post { genresSkeleton.visibility = View.GONE }
            }
        }

        // Cast
        val castText = view.findViewById<TextView>(R.id.detail_cast)
        if (!cast.isNullOrBlank()) {
            castText.text = cast
        } else {
            castText.text = "Cast info not available"
        }

        // IMDb link
        val imdbLink = view.findViewById<TextView>(R.id.detail_imdb_id)
        imdbLink.text = "Open on IMDb"
        imdbLink.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.imdb.com/title/$imdbId/")))
            } catch (_: Exception) { }
        }
    }

    private fun parseGenres(json: String): List<String>? {
        // Look for "genres":["Drama","Action",...]
        val pattern = """"genres"\s*:\s*\[([^\]]+)\]""".toRegex()
        val match = pattern.find(json) ?: return null
        val content = match.groupValues.getOrNull(1) ?: return null
        // Extract quoted strings
        return """"(.+?)"""".toRegex().findAll(content).map { it.groupValues[1] }.toList()
    }
}
