package com.cvuong233.cinephantom.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.search.SimpleImageLoader
import org.json.JSONObject
import java.net.URL
import kotlin.concurrent.thread

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMDB_ID = "extra_imdb_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_IMAGE_URL = "extra_image_url"
        const val EXTRA_CAST = "extra_cast"
        const val EXTRA_YEAR = "extra_year"
        const val EXTRA_TYPE = "extra_type"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        val imdbId = intent?.getStringExtra(EXTRA_IMDB_ID) ?: run { finish(); return }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Unknown"
        val type = intent?.getStringExtra(EXTRA_TYPE) ?: "Movie"
        val year = intent?.getStringExtra(EXTRA_YEAR) ?: ""
        val imageUrl = intent?.getStringExtra(EXTRA_IMAGE_URL) ?: ""

        // Back button
        findViewById<View>(R.id.detail_back).setOnClickListener { finish() }

        // Show initial title with animation
        val titleView = findViewById<TextView>(R.id.detail_title)
        titleView.text = title
        titleView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_up_fade))

        // Meta row from intent extras (instant)
        val metaView = findViewById<TextView>(R.id.detail_meta)
        metaView.text = if (year.isNotBlank()) "$type · $year" else type

        // Load hero image (try the provided URL first)
        val heroImage = findViewById<ImageView>(R.id.detail_hero)
        if (imageUrl.isNotBlank()) {
            SimpleImageLoader.load(imageUrl, heroImage)
        }

        // Start rating placeholder
        val ratingView = findViewById<TextView>(R.id.detail_rating)
        ratingView.text = "IMDb --"
        ratingView.visibility = View.VISIBLE

        // Fetch full metadata from Cinemeta
        val descView = findViewById<TextView>(R.id.detail_description)
        val genresContainer = findViewById<FlowLayout>(R.id.detail_genres_container)
        val castContainer = findViewById<LinearLayout>(R.id.detail_cast_container)

        val apiType = if (type == "Series" || type == "TV Show") "series" else "movie"

        thread {
            try {
                val jsonText = URL("https://v3-cinemeta.strem.io/meta/$apiType/$imdbId.json").readText()
                val meta = JSONObject(jsonText).optJSONObject("meta") ?: return@thread

                val runtime = meta.optString("runtime", "")
                val imdbRating = meta.optString("imdbRating", "")
                val description = meta.optString("description", "")
                val bgUrl = meta.optString("background", "")
                val posterUrl = meta.optString("poster", "")

                // Parse genres
                val genresArr = meta.optJSONArray("genres")
                val genres = mutableListOf<String>()
                if (genresArr != null) {
                    for (i in 0 until genresArr.length()) genres.add(genresArr.optString(i))
                }

                // Parse cast
                val castArr = meta.optJSONArray("cast")
                val cast = mutableListOf<String>()
                if (castArr != null) {
                    for (i in 0 until castArr.length()) cast.add(castArr.optString(i))
                }

                runOnUiThread {
                    // Update meta with runtime
                    val metaParts = mutableListOf<String>()
                    if (year.isNotBlank()) metaParts.add(year)
                    if (runtime.isNotBlank()) metaParts.add(runtime)
                    metaView.text = metaParts.joinToString(" · ").ifBlank { type }

                    // Update rating
                    if (imdbRating.isNotBlank()) {
                        ratingView.text = "★ $imdbRating IMDb"
                    } else {
                        ratingView.visibility = View.GONE
                    }

                    // Update hero with background (better quality)
                    if (bgUrl.isNotBlank()) {
                        SimpleImageLoader.load(bgUrl, heroImage)
                    } else if (posterUrl.isNotBlank() && imageUrl.isBlank()) {
                        SimpleImageLoader.load(posterUrl, heroImage)
                    }

                    // Description
                    if (description.isNotBlank()) {
                        descView.text = description
                        descView.visibility = View.VISIBLE
                    }

                    // Genres
                    if (genres.isNotEmpty()) {
                        genresContainer.removeAllViews()
                        for (g in genres) {
                            val chip = layoutInflater.inflate(R.layout.item_genre_chip, genresContainer, false) as TextView
                            chip.text = g
                            genresContainer.addView(chip)
                        }
                    }

                    // Cast
                    if (cast.isNotEmpty()) {
                        castContainer.removeAllViews()
                        val avatarColors = listOf(
                            "#6B7CFF", "#FFA723", "#E85D75", "#4ECDC4",
                            "#A78BFA", "#34D399", "#F472B6", "#60A5FA"
                        )
                        for ((i, actor) in cast.withIndex()) {
                            val item = layoutInflater.inflate(R.layout.item_cast_member, castContainer, false)
                            val avatar = item.findViewById<TextView>(R.id.cast_avatar)
                            val name = item.findViewById<TextView>(R.id.cast_name)
                            avatar.text = actor.firstOrNull()?.uppercase() ?: "?"
                            try {
                                avatar.background.setTint(Color.parseColor(avatarColors[i % avatarColors.size]))
                            } catch (_: Exception) {}
                            name.text = actor
                            castContainer.addView(item)
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    ratingView.text = "IMDb --"
                }
            }
        }

        // Stremio button
        findViewById<View>(R.id.detail_stremio_button).setOnClickListener {
            val stremioType = when (type) {
                "TV Series", "TV Mini Series", "TV Series (mini)", "TV Show", "Series" -> "series"
                "TV Episode" -> "episode"
                else -> "movie"
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("stremio://detail/$stremioType/$imdbId")))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.stremio_not_installed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
