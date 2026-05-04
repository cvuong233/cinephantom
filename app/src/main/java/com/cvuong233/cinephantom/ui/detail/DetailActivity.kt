package com.cvuong233.cinephantom.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
        val intentCast = intent?.getStringExtra(EXTRA_CAST)

        // Views
        val backBtn = findViewById<TextView>(R.id.detail_back)
        val heroImage = findViewById<ImageView>(R.id.detail_hero)
        val titleView = findViewById<TextView>(R.id.detail_title)
        val metaView = findViewById<TextView>(R.id.detail_meta)
        val ratingView = findViewById<TextView>(R.id.detail_rating)
        val directorView = findViewById<TextView>(R.id.detail_director)
        val descView = findViewById<TextView>(R.id.detail_description)
        val genresContainer = findViewById<FlowLayout>(R.id.detail_genres_container)
        val castContainer = findViewById<LinearLayout>(R.id.detail_cast_container)
        val castScroll = findViewById<HorizontalScrollView>(R.id.detail_cast_scroll)
        val aboutLabel = findViewById<TextView>(R.id.detail_about_label)
        val castLabel = findViewById<TextView>(R.id.detail_cast_label)
        val divider = findViewById<View>(R.id.detail_divider)
        val stremioBtn = findViewById<View>(R.id.detail_stremio_button)

        // Back button — fade in
        backBtn.setOnClickListener { finish() }
        backBtn.animate().alpha(1f).setDuration(300).start()

        // Set title early (will animate in after data loads)
        titleView.text = title

        // Hero: start zoomed out, animate in when ready
        heroImage.scaleX = 0.92f
        heroImage.scaleY = 0.92f
        heroImage.alpha = 0f

        val heroIn = {
            heroImage.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(700)
                .setInterpolator(OvershootInterpolator(1.05f))
                .start()
        }

        if (imageUrl.isNotBlank()) {
            SimpleImageLoader.load(imageUrl, heroImage,
                onSuccess = { heroIn() },
                onError = { heroIn() }
            )
        } else {
            heroIn()
        }

        // Stremio button
        stremioBtn.setOnClickListener {
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

        // Fetch metadata
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

                // Genres
                val genresArr = meta.optJSONArray("genres")
                val genres = mutableListOf<String>()
                if (genresArr != null) {
                    for (i in 0 until genresArr.length()) genres.add(genresArr.optString(i))
                }

                // Director
                val directorArr = meta.optJSONArray("director")
                val directors = mutableListOf<String>()
                if (directorArr != null) {
                    for (i in 0 until directorArr.length()) directors.add(directorArr.optString(i))
                }

                // Cast — try credits_cast first, then cast
                val creditsCastArr = meta.optJSONArray("credits_cast")
                val castArr = if (creditsCastArr != null && creditsCastArr.length() > 0) {
                    creditsCastArr
                } else {
                    meta.optJSONArray("cast")
                }

                val castItems = mutableListOf<CastMember>()
                if (castArr != null) {
                    for (i in 0 until castArr.length()) {
                        val c = castArr.opt(i)
                        when (c) {
                            is JSONObject -> {
                                val pp = c.optString("profile_path", "")
                                castItems.add(CastMember(
                                    name = c.optString("name", ""),
                                    profilePath = pp.ifBlank { null }
                                ))
                            }
                            is String -> {
                                if (c.isNotBlank()) {
                                    castItems.add(CastMember(name = c, profilePath = null))
                                }
                            }
                        }
                    }
                }

                runOnUiThread {
                    // Prefer landscape background from Cinemeta, fall back to poster
                    val landscapeUrl = if (bgUrl.isNotBlank()) bgUrl else posterUrl
                    if (landscapeUrl.isNotBlank()) {
                        SimpleImageLoader.load(landscapeUrl, heroImage,
                            onSuccess = { heroIn() },
                            onError = { heroIn() }
                        )
                    }

                    populateContent(
                        metaView, ratingView, directorView, descView,
                        genresContainer, castContainer,
                        type, year, runtime, imdbRating,
                        description, directors, genres, castItems
                    )

                    val hasCast = castItems.isNotEmpty()

                    // Fallback: use cast string from search results if Cinemeta returned nothing
                    if (!hasCast && !intentCast.isNullOrBlank()) {
                        val names = intentCast.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        for (name in names) {
                            castItems.add(CastMember(name = name, profilePath = null))
                        }
                    }

                    val effectiveHasCast = castItems.isNotEmpty()

                    animateContent(
                        titleView, metaView, ratingView, directorView,
                        descView, genresContainer, castContainer, castScroll,
                        aboutLabel, castLabel, divider, stremioBtn,
                        effectiveHasCast
                    )
                }
            } catch (_: Exception) {
                runOnUiThread {
                    // Fallback: just show title with animation
                    titleView.visibility = View.VISIBLE
                    titleView.translationY = -60f
                    titleView.alpha = 0f
                    titleView.animate()
                        .translationY(0f).alpha(1f)
                        .setDuration(450)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    // Still show stremio button
                    animateStremioIn(stremioBtn, 200)
                }
            }
        }
    }

    private fun populateContent(
        metaView: TextView, ratingView: TextView, directorView: TextView,
        descView: TextView, genresContainer: FlowLayout,
        castContainer: LinearLayout,
        type: String, year: String,
        runtime: String, imdbRating: String, description: String,
        directors: List<String>, genres: List<String>,
        castItems: List<CastMember>
    ) {
        // Meta row
        val metaParts = mutableListOf<String>()
        if (year.isNotBlank()) metaParts.add(year)
        if (runtime.isNotBlank()) metaParts.add(runtime)
        metaView.text = metaParts.joinToString(" · ").ifBlank { type }

        // Rating
        if (imdbRating.isNotBlank()) {
            ratingView.text = "★ $imdbRating IMDb"
        } else {
            ratingView.text = "IMDb --"
        }

        // Director
        if (directors.isNotEmpty()) {
            directorView.text = "Directed by ${directors.joinToString(", ")}"
        }

        // Description
        if (description.isNotBlank()) {
            descView.text = description
        }

        // Genre chips
        if (genres.isNotEmpty()) {
            genresContainer.removeAllViews()
            for (g in genres) {
                val chip = layoutInflater.inflate(R.layout.item_genre_chip, genresContainer, false) as TextView
                chip.text = g
                genresContainer.addView(chip)
            }
        }

        // Cast with photos
        if (castItems.isNotEmpty()) {
            castContainer.removeAllViews()
            val avatarColors = listOf(
                "#6B7CFF", "#FFA723", "#E85D75", "#4ECDC4",
                "#A78BFA", "#34D399", "#F472B6", "#60A5FA"
            )

            for ((i, member) in castItems.withIndex()) {
                val item = layoutInflater.inflate(R.layout.item_cast_member, castContainer, false)
                val frame = item.findViewById<FrameLayout>(R.id.cast_avatar_frame)
                val photoView = item.findViewById<ImageView>(R.id.cast_photo)
                val avatarView = item.findViewById<TextView>(R.id.cast_avatar)
                val nameView = item.findViewById<TextView>(R.id.cast_name)

                frame.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                frame.clipToOutline = true

                avatarView.text = member.name.firstOrNull()?.uppercase() ?: "?"
                try {
                    avatarView.background.setTint(Color.parseColor(avatarColors[i % avatarColors.size]))
                } catch (_: Exception) {}

                nameView.text = member.name

                if (member.profilePath != null) {
                    val photoUrl = "https://image.tmdb.org/t/p/w185${member.profilePath}"
                    SimpleImageLoader.load(photoUrl, photoView,
                        onSuccess = {
                            photoView.visibility = View.VISIBLE
                            avatarView.visibility = View.GONE
                        }
                    )
                }

                castContainer.addView(item)
            }
        }
    }

    private fun animateContent(
        titleView: TextView, metaView: TextView, ratingView: TextView,
        directorView: TextView, descView: TextView,
        genresContainer: FlowLayout,
        castContainer: LinearLayout, castScroll: HorizontalScrollView,
        aboutLabel: TextView, castLabel: TextView,
        divider: View, stremioBtn: View,
        hasCast: Boolean
    ) {
        val decel = DecelerateInterpolator()
        val decelFast = DecelerateInterpolator(1.5f)
        val overshoot = OvershootInterpolator(1.1f)
        val overshootPop = OvershootInterpolator(1.25f)

        // ── Title: drop from above ──
        titleView.apply {
            visibility = View.VISIBLE
            translationY = -60f
            alpha = 0f
            animate()
                .translationY(0f).alpha(1f)
                .setDuration(450).setStartDelay(50)
                .setInterpolator(decel)
                .start()
        }

        // ── Meta: slide from left ──
        metaView.apply {
            visibility = View.VISIBLE
            translationX = -80f
            alpha = 0f
            animate()
                .translationX(0f).alpha(1f)
                .setDuration(400).setStartDelay(150)
                .setInterpolator(decelFast)
                .start()
        }

        // ── Rating: slide from right ──
        ratingView.apply {
            visibility = View.VISIBLE
            translationX = 80f
            alpha = 0f
            animate()
                .translationX(0f).alpha(1f)
                .setDuration(400).setStartDelay(200)
                .setInterpolator(decelFast)
                .start()
        }

        // ── Director: slide from left ──
        directorView.apply {
            visibility = View.VISIBLE
            translationX = -60f
            alpha = 0f
            animate()
                .translationX(0f).alpha(1f)
                .setDuration(350).setStartDelay(250)
                .setInterpolator(decel)
                .start()
        }

        // ── Genres: pop in staggered with overshoot ──
        genresContainer.apply {
            visibility = View.VISIBLE
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.scaleX = 0f
                child.scaleY = 0f
                child.alpha = 0f
                child.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(350)
                    .setStartDelay(300 + i * 60L)
                    .setInterpolator(overshootPop)
                    .start()
            }
        }

        // ── Divider: expand from center ──
        divider.apply {
            visibility = View.VISIBLE
            scaleX = 0f
            animate()
                .scaleX(1f)
                .setDuration(400).setStartDelay(400)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }

        // ── About label: slide up ──
        aboutLabel.apply {
            visibility = View.VISIBLE
            translationY = 25f
            alpha = 0f
            animate()
                .translationY(0f).alpha(1f)
                .setDuration(300).setStartDelay(480)
                .setInterpolator(decel)
                .start()
        }

        // ── Description: slide up ──
        descView.apply {
            visibility = View.VISIBLE
            translationY = 30f
            alpha = 0f
            animate()
                .translationY(0f).alpha(1f)
                .setDuration(400).setStartDelay(510)
                .setInterpolator(decel)
                .start()
        }

        // ── Cast section (only if cast data exists) ──
        if (hasCast) {
            castLabel.apply {
                visibility = View.VISIBLE
                translationX = -40f
                alpha = 0f
                animate()
                    .translationX(0f).alpha(1f)
                    .setDuration(300).setStartDelay(580)
                    .setInterpolator(decel)
                    .start()
            }

            castScroll.apply {
                visibility = View.VISIBLE
            }

            for (i in 0 until castContainer.childCount) {
                val child = castContainer.getChildAt(i)
                child.translationY = 50f
                child.alpha = 0f
                child.animate()
                    .translationY(0f).alpha(1f)
                    .setDuration(350)
                    .setStartDelay(600 + i * 55L)
                    .setInterpolator(overshoot)
                    .start()
            }

            // Stremio button delay: after last cast item
            animateStremioIn(stremioBtn, 600 + castContainer.childCount * 55L + 200)
        } else {
            // No cast: stremio comes in sooner
            animateStremioIn(stremioBtn, 580)
        }
    }

    private fun animateStremioIn(btn: View, delay: Long) {
        btn.apply {
            visibility = View.VISIBLE
            translationY = 40f
            alpha = 0f
            animate()
                .translationY(0f).alpha(1f)
                .setDuration(450).setStartDelay(delay)
                .setInterpolator(OvershootInterpolator(1.1f))
                .start()
        }
    }
}

private data class CastMember(val name: String, val profilePath: String?)
