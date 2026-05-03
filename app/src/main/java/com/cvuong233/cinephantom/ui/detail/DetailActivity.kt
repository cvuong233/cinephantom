package com.cvuong233.cinephantom.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.search.ShimmerView
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

        // Reference all skeleton views
        val heroSkeleton = findViewById<ShimmerView>(R.id.hero_skeleton)
        val titleSkeleton = findViewById<ShimmerView>(R.id.title_skeleton)
        val metaSkeleton = findViewById<ShimmerView>(R.id.meta_skeleton)
        val genreSkeleton = findViewById<ShimmerView>(R.id.genre_skeleton)
        val directorSkeleton = findViewById<ShimmerView>(R.id.director_skeleton)
        val descSkeleton = findViewById<LinearLayout>(R.id.desc_skeleton)
        val castSkeleton = findViewById<LinearLayout>(R.id.cast_skeleton)

        // Reference all real content views
        val heroImage = findViewById<ImageView>(R.id.detail_hero)
        val titleView = findViewById<TextView>(R.id.detail_title)
        val metaView = findViewById<TextView>(R.id.detail_meta)
        val ratingView = findViewById<TextView>(R.id.detail_rating)
        val genresContainer = findViewById<FlowLayout>(R.id.detail_genres_container)
        val directorView = findViewById<TextView>(R.id.detail_director)
        val aboutLabel = findViewById<TextView>(R.id.detail_about_label)
        val descView = findViewById<TextView>(R.id.detail_description)
        val castLabel = findViewById<TextView>(R.id.detail_cast_label)
        val castContainer = findViewById<LinearLayout>(R.id.detail_cast_container)

        // Load hero image immediately from intent
        if (imageUrl.isNotBlank()) {
            SimpleImageLoader.load(imageUrl, heroImage,
                onSuccess = {
                    heroSkeleton.visibility = View.GONE
                    heroImage.visibility = View.VISIBLE
                    heroImage.animate().alpha(1f).setDuration(400).start()
                },
                onError = {
                    heroSkeleton.visibility = View.GONE
                }
            )
        }

        // Fetch full metadata from Cinemeta
        val apiType = if (type == "Series" || type == "TV Show") "series" else "movie"

        thread {
            try {
                val jsonText = URL("https://v3-cinemeta.strem.io/meta/$apiType/$imdbId.json").readText()
                val meta = JSONObject(jsonText).optJSONObject("meta") ?: run {
                    runOnUiThread { hideSkeletonsOnError(heroSkeleton, titleSkeleton, metaSkeleton, genreSkeleton, directorSkeleton, descSkeleton, castSkeleton) }
                    return@thread
                }

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

                // Parse director
                val directorArr = meta.optJSONArray("director")
                val director = if (directorArr != null && directorArr.length() > 0) directorArr.optString(0) else ""

                // Parse credits_cast with photos
                val creditsCast = meta.optJSONArray("credits_cast")
                val castMembers = mutableListOf<CastMember>()
                if (creditsCast != null) {
                    for (i in 0 until creditsCast.length()) {
                        val c = creditsCast.optJSONObject(i) ?: continue
                        castMembers.add(CastMember(
                            name = c.optString("name", ""),
                            profilePath = c.optString("profile_path", null)?.takeIf { it.isNotBlank() }
                        ))
                    }
                }
                // Fallback to simple cast array if credits_cast is empty
                if (castMembers.isEmpty()) {
                    val castArr = meta.optJSONArray("cast")
                    if (castArr != null) {
                        for (i in 0 until castArr.length()) {
                            castMembers.add(CastMember(name = castArr.optString(i), profilePath = null))
                        }
                    }
                }

                runOnUiThread {
                    showContent(
                        heroSkeleton, titleSkeleton, metaSkeleton, genreSkeleton, directorSkeleton, descSkeleton, castSkeleton,
                        heroImage, titleView, metaView, ratingView, genresContainer, directorView,
                        aboutLabel, descView, castLabel, castContainer,
                        title, type, year, bgUrl, posterUrl, imageUrl,
                        runtime, imdbRating, description, genres, director, castMembers
                    )
                }
            } catch (_: Exception) {
                runOnUiThread {
                    hideSkeletonsOnError(heroSkeleton, titleSkeleton, metaSkeleton, genreSkeleton, directorSkeleton, descSkeleton, castSkeleton)
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

    private fun showContent(
        heroSkeleton: ShimmerView, titleSkeleton: ShimmerView, metaSkeleton: ShimmerView,
        genreSkeleton: ShimmerView, directorSkeleton: ShimmerView,
        descSkeleton: LinearLayout, castSkeleton: LinearLayout,
        heroImage: ImageView, titleView: TextView, metaView: TextView,
        ratingView: TextView, genresContainer: FlowLayout, directorView: TextView,
        aboutLabel: TextView, descView: TextView, castLabel: TextView,
        castContainer: LinearLayout,
        title: String, type: String, year: String,
        bgUrl: String, posterUrl: String, imageUrl: String,
        runtime: String, imdbRating: String, description: String,
        genres: List<String>, director: String,
        castMembers: List<CastMember>
    ) {
        // Hide all skeletons
        heroSkeleton.visibility = View.GONE
        titleSkeleton.visibility = View.GONE
        metaSkeleton.visibility = View.GONE
        genreSkeleton.visibility = View.GONE
        directorSkeleton.visibility = View.GONE
        descSkeleton.visibility = View.GONE
        castSkeleton.visibility = View.GONE

        // Hero image: fade in
        if (imageUrl.isBlank()) {
            if (bgUrl.isNotBlank()) {
                SimpleImageLoader.load(bgUrl, heroImage,
                    onSuccess = {
                        heroImage.visibility = View.VISIBLE
                        heroImage.animate().alpha(1f).setDuration(400).start()
                    }
                )
            } else if (posterUrl.isNotBlank()) {
                SimpleImageLoader.load(posterUrl, heroImage,
                    onSuccess = {
                        heroImage.visibility = View.VISIBLE
                        heroImage.animate().alpha(1f).setDuration(400).start()
                    }
                )
            }
        }

        // Title: slide up + fade
        titleView.text = title
        titleView.visibility = View.VISIBLE
        titleView.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_up_fade))

        // Meta: fade in delayed
        val metaParts = mutableListOf<String>()
        if (year.isNotBlank()) metaParts.add(year)
        if (runtime.isNotBlank()) metaParts.add(runtime)
        metaView.text = metaParts.joinToString(" · ").ifBlank { type }
        metaView.visibility = View.VISIBLE
        metaView.alpha = 0f
        metaView.animate().alpha(1f).setStartDelay(200).setDuration(300).start()

        // Rating: fade in delayed
        if (imdbRating.isNotBlank()) {
            ratingView.text = "★ $imdbRating IMDb"
            ratingView.visibility = View.VISIBLE
            ratingView.alpha = 0f
            ratingView.animate().alpha(1f).setStartDelay(350).setDuration(300).start()
        }

        // Director: fade in delayed
        if (director.isNotBlank()) {
            directorView.text = "Directed by $director"
            directorView.visibility = View.VISIBLE
            directorView.alpha = 0f
            directorView.animate().alpha(1f).setStartDelay(400).setDuration(300).start()
        }

        // Genres: pop in staggered
        if (genres.isNotEmpty()) {
            genresContainer.visibility = View.VISIBLE
            genresContainer.removeAllViews()
            for ((i, g) in genres.withIndex()) {
                val chip = layoutInflater.inflate(R.layout.item_genre_chip, genresContainer, false) as TextView
                chip.text = g
                chip.alpha = 0f
                chip.scaleX = 0.8f
                chip.scaleY = 0.8f
                genresContainer.addView(chip)
                chip.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay((100 + i * 60).toLong())
                    .setDuration(300).start()
            }
        }

        // Description: fade in
        if (description.isNotBlank()) {
            aboutLabel.visibility = View.VISIBLE
            aboutLabel.alpha = 0f
            aboutLabel.animate().alpha(1f).setStartDelay(450).setDuration(300).start()

            descView.text = description
            descView.visibility = View.VISIBLE
            descView.alpha = 0f
            descView.animate().alpha(1f).setStartDelay(500).setDuration(400).start()
        }

        // Cast: slide in from right, staggered
        if (castMembers.isNotEmpty()) {
            castLabel.visibility = View.VISIBLE
            castLabel.alpha = 0f
            castLabel.animate().alpha(1f).setStartDelay(500).setDuration(300).start()

            castContainer.removeAllViews()
            val avatarColors = listOf(
                "#6B7CFF", "#FFA723", "#E85D75", "#4ECDC4",
                "#A78BFA", "#34D399", "#F472B6", "#60A5FA"
            )

            for ((i, member) in castMembers.withIndex()) {
                val item = layoutInflater.inflate(R.layout.item_cast_member, castContainer, false)
                val frame = item.findViewById<FrameLayout>(R.id.cast_avatar_frame)
                val photoView = item.findViewById<ImageView>(R.id.cast_photo)
                val avatarView = item.findViewById<TextView>(R.id.cast_avatar)
                val nameView = item.findViewById<TextView>(R.id.cast_name)

                // Initial letter fallback
                avatarView.text = member.name.firstOrNull()?.uppercase() ?: "?"
                try {
                    avatarView.background.setTint(Color.parseColor(avatarColors[i % avatarColors.size]))
                } catch (_: Exception) {}

                nameView.text = member.name

                // Load photo if available
                if (member.profilePath != null) {
                    val photoUrl = "https://image.tmdb.org/t/p/w185${member.profilePath}"
                    frame.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
                    frame.clipToOutline = true

                    SimpleImageLoader.load(photoUrl, photoView,
                        onSuccess = {
                            photoView.visibility = View.VISIBLE
                            avatarView.visibility = View.GONE
                        }
                    )
                }

                castContainer.addView(item)

                // Staggered slide-in from right
                item.alpha = 0f
                item.translationX = 60f
                item.animate().alpha(1f).translationX(0f)
                    .setStartDelay((550 + i * 60).toLong())
                    .setDuration(350).start()
            }
        }
    }

    private fun hideSkeletonsOnError(
        heroSkeleton: ShimmerView, titleSkeleton: ShimmerView, metaSkeleton: ShimmerView,
        genreSkeleton: ShimmerView, directorSkeleton: ShimmerView,
        descSkeleton: LinearLayout, castSkeleton: LinearLayout
    ) {
        heroSkeleton.visibility = View.GONE
        titleSkeleton.visibility = View.GONE
        metaSkeleton.visibility = View.GONE
        genreSkeleton.visibility = View.GONE
        directorSkeleton.visibility = View.GONE
        descSkeleton.visibility = View.GONE
        castSkeleton.visibility = View.GONE
    }

    data class CastMember(val name: String, val profilePath: String?)
}
