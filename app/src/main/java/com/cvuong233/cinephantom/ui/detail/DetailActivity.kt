package com.cvuong233.cinephantom.ui.detail

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.RatingFetcher
import com.cvuong233.cinephantom.ui.search.ShimmerView
import kotlin.concurrent.thread

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMDB_ID = "extra_imdb_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_IMAGE_URL = "extra_image_url"
        const val EXTRA_CAST = "extra_cast"
        const val EXTRA_YEAR = "extra_year"
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_RATING = "extra_rating"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val imdbId = intent?.getStringExtra(EXTRA_IMDB_ID) ?: run { finish(); return }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Unknown"
        val type = intent?.getStringExtra(EXTRA_TYPE)
        val year = intent?.getStringExtra(EXTRA_YEAR)
        val cast = intent?.getStringExtra(EXTRA_CAST)
        val imageUrl = intent?.getStringExtra(EXTRA_IMAGE_URL)
        val passedRating = intent?.getFloatExtra(EXTRA_RATING, -1f)?.takeIf { it > 0f }

        // Back
        findViewById<View>(R.id.detail_back).setOnClickListener { finish() }

        // Title
        findViewById<TextView>(R.id.detail_title).text = title

        // Meta chip
        findViewById<TextView>(R.id.detail_meta).text = listOfNotNull(type, year).joinToString(" • ").ifBlank { "No info" }

        // Rating — use passed value (in sync with search card) or fetch
        val ratingText = findViewById<TextView>(R.id.detail_rating)
        if (passedRating != null) {
            ratingText.text = "IMDb %.1f".format(passedRating)
        } else {
            thread {
                try {
                    val rating = RatingFetcher().fetchRating(imdbId)
                    if (rating != null && rating > 0) {
                        runOnUiThread { ratingText.text = "IMDb %.1f".format(rating) }
                    } else {
                        runOnUiThread { ratingText.visibility = View.GONE }
                    }
                } catch (_: Exception) {
                    runOnUiThread { ratingText.visibility = View.GONE }
                }
            }
        }

        // Poster with shimmer
        val posterPlaceholder = findViewById<ShimmerView>(R.id.detail_poster_placeholder)
        if (!imageUrl.isNullOrBlank()) {
            posterPlaceholder.visibility = View.VISIBLE
            findViewById<ImageView>(R.id.detail_poster).apply {
                visibility = View.GONE
                setImageDrawable(null)
                com.cvuong233.cinephantom.ui.search.SimpleImageLoader.load(
                    url = imageUrl,
                    imageView = this,
                    onSuccess = {
                        visibility = View.VISIBLE
                        posterPlaceholder.visibility = View.GONE
                    },
                    onError = {
                        posterPlaceholder.visibility = View.VISIBLE
                    },
                )
            }
        }

        // Cast
        val castText = findViewById<TextView>(R.id.detail_cast)
        castText.text = cast ?: "Cast info not available"

        // Skeleton views
        val descSkeleton = findViewById<View>(R.id.detail_desc_skeleton)
        val genresSkeleton = findViewById<View>(R.id.detail_genres_skeleton)
        val loadingNotice = findViewById<TextView>(R.id.detail_loading_notice)

        // Start shimmers on skeletons
        val descSkeletonGroup = descSkeleton as? ViewGroup
        val genresSkeletonGroup = genresSkeleton as? ViewGroup
        descSkeleton.post {
            descSkeletonGroup?.let { g ->
                for (i in 0 until g.childCount) {
                    val child = g.getChildAt(i)
                    if (child is com.cvuong233.cinephantom.ui.search.ShimmerView) {
                        child.startShimmer()
                    }
                }
            }
            genresSkeletonGroup?.let { g ->
                for (i in 0 until g.childCount) {
                    val child = g.getChildAt(i)
                    if (child is com.cvuong233.cinephantom.ui.search.ShimmerView) {
                        child.startShimmer()
                    }
                }
            }
        }

        // Show loading notice after a delay
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed({ loadingNotice.visibility = View.VISIBLE }, 3000)

        // Fetch extra metadata from Cinemeta (description + genres)
        val genresContainer = findViewById<LinearLayout>(R.id.detail_genres_container)
        val descText = findViewById<TextView>(R.id.detail_description)
        thread {
            try {
                val json = java.net.URL("https://v3-cinemeta.strem.io/meta/movie/$imdbId.json").readText()
                val data = parseFields(json)
                val finalData = if (data == null) {
                    val seriesJson = java.net.URL("https://v3-cinemeta.strem.io/meta/series/$imdbId.json").readText()
                    parseFields(seriesJson)
                } else data
                runOnUiThread {
                    handler.removeCallbacksAndMessages(null)
                    loadingNotice.visibility = View.GONE
                    showExtraInfo(finalData, descText, genresContainer)
                    // Hide skeletons, show real content
                    descSkeleton.visibility = View.GONE
                    if (finalData?.description != null) descText.visibility = View.VISIBLE
                    if (finalData?.genres != null && finalData.genres!!.isNotEmpty()) {
                        genresSkeleton.visibility = View.GONE
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    handler.removeCallbacksAndMessages(null)
                    descSkeleton.visibility = View.GONE
                    genresSkeleton.visibility = View.GONE
                    loadingNotice.visibility = View.GONE
                }
            }
        }
    }

    private data class ExtraInfo(val description: String?, val genres: List<String>?)

    private fun parseFields(json: String): ExtraInfo? {
        val desc = """"description"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex().find(json)
            ?.groupValues?.getOrNull(1)?.replace("\\\"", "\"")?.replace("\\n", "\n")
        val genreMatch = """"genres"\s*:\s*\[([^\]]+)\]""".toRegex().find(json)
        val genres = genreMatch?.let { match ->
            """"(.+?)"""".toRegex().findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
        }
        return if (desc != null || (genres != null && genres.isNotEmpty())) {
            ExtraInfo(desc, genres)
        } else null
    }

    private fun showExtraInfo(data: ExtraInfo?, descText: TextView, genresContainer: LinearLayout) {
        if (data == null) return

        if (!data.description.isNullOrBlank()) {
            descText.text = data.description
            descText.visibility = View.VISIBLE
        }

        if (data.genres != null && data.genres.isNotEmpty()) {
            genresContainer.removeAllViews()
            for (g in data.genres) {
                val chip = layoutInflater.inflate(R.layout.item_genre_chip, genresContainer, false) as TextView
                chip.text = g
                genresContainer.addView(chip)
            }
        }
    }
}
