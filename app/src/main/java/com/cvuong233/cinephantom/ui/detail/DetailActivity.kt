package com.cvuong233.cinephantom.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.ScrollView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import com.cvuong233.cinephantom.ui.FuturisticAnim
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.MainActivity
import com.cvuong233.cinephantom.ui.search.SimpleImageLoader
import org.json.JSONObject
import java.net.URL
import com.cvuong233.cinephantom.data.TMDBApi
import com.cvuong233.cinephantom.data.TMDBCastMember
import com.cvuong233.cinephantom.data.TMDBCrewMember
import com.cvuong233.cinephantom.data.TMDBShowDetails
import com.cvuong233.cinephantom.data.WatchlistDatabase
import com.cvuong233.cinephantom.model.WatchlistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

class DetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMDB_ID = "extra_imdb_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_IMAGE_URL = "extra_image_url"
        const val EXTRA_CAST = "extra_cast"
        const val EXTRA_YEAR = "extra_year"
        const val EXTRA_TYPE = "extra_type"
        const val EXTRA_TRANSITION_NAME = "extra_transition_name"
        const val EXTRA_FROM_WIDGET = "extra_from_widget"
        const val EXTRA_RETURN_DISCOVER_TYPE = "extra_return_discover_type"
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
        val launchedFromWidget = intent?.getBooleanExtra(EXTRA_FROM_WIDGET, false) == true

        // Normalize type early — needed by trailer button and metadata threads
        val typeLower = type?.lowercase() ?: ""
        val isSeries = typeLower.contains("series") ||
                       typeLower.contains("tv episode") ||
                       typeLower == "mini series"
        val apiType = if (isSeries) "series" else "movie"

        // Views
        val backBtn = findViewById<ImageView>(R.id.detail_back)
        val posterImage = findViewById<ImageView>(R.id.detail_hero)
        val backdropImage = findViewById<ImageView>(R.id.detail_backdrop)
        val incomingTransitionName = intent?.getStringExtra(EXTRA_TRANSITION_NAME)
        ViewCompat.setTransitionName(posterImage, incomingTransitionName ?: "poster_$imdbId")
        val heroShimmer = findViewById<View>(R.id.detail_hero_shimmer)
        val titleView = findViewById<TextView>(R.id.detail_title)
        val titleRow = findViewById<LinearLayout>(R.id.detail_title_row)
        val metaView = findViewById<TextView>(R.id.detail_meta)
        val ratingView = findViewById<TextView>(R.id.detail_rating)
        val ratingRow = findViewById<LinearLayout>(R.id.detail_rating_row)
        val directorHeader = findViewById<LinearLayout>(R.id.detail_director_header)
        val directorLabelView = findViewById<TextView>(R.id.detail_director_label)
        val directorScroll = findViewById<HorizontalScrollView>(R.id.detail_director_scroll)
        val directorContainer = findViewById<LinearLayout>(R.id.detail_director_container)
        val descView = findViewById<TextView>(R.id.detail_description)
        val genresContainer = findViewById<FlowLayout>(R.id.detail_genres_container)
        val castContainer = findViewById<LinearLayout>(R.id.detail_cast_container)
        val castScroll = findViewById<HorizontalScrollView>(R.id.detail_cast_scroll)
        val aboutLabel = findViewById<TextView>(R.id.detail_about_label)
        val castHeader = findViewById<LinearLayout>(R.id.detail_cast_header)
        val castLabel = findViewById<TextView>(R.id.detail_cast_label)
        val castViewAll = findViewById<TextView>(R.id.detail_cast_view_all)
        val divider = findViewById<View>(R.id.detail_divider)
        val shareBtn = findViewById<ImageView>(R.id.detail_share_button)
        val stremioBtn = findViewById<ImageView>(R.id.detail_stremio_button)
        val db = WatchlistDatabase.get(this) // still used for auto-history
        val trailerBtn = findViewById<TextView>(R.id.detail_trailer_button)
        val tvCard = findViewById<LinearLayout>(R.id.detail_tv_card)
        val tvSeasons = findViewById<TextView>(R.id.detail_tv_seasons)
        val tvEpisodes = findViewById<TextView>(R.id.detail_tv_episodes)
        val tvStatus = findViewById<TextView>(R.id.detail_tv_status)

        // Back button — if launched from widget, return to Top IMDb and focus the title.
        backBtn.setOnClickListener {
            if (launchedFromWidget) {
                navigateBackToDiscover(imdbId)
            } else if (isTaskRoot) {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            } else {
                finishAfterTransition()
            }
        }
        backBtn.animate().alpha(1f).setDuration(300).start()

        onBackPressedDispatcher.addCallback(this) {
            if (launchedFromWidget) {
                navigateBackToDiscover(imdbId)
            } else if (isTaskRoot) {
                startActivity(Intent(this@DetailActivity, MainActivity::class.java))
                finish()
            } else {
                isEnabled = false
                finishAfterTransition()
            }
        }

        // Share button
        shareBtn.setOnClickListener {
            val shareText = "$title — https://www.imdb.com/title/$imdbId/"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                setType("text/plain")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_title)))
        }

        // Auto-history: record view on detail page open
        CoroutineScope(Dispatchers.IO).launch {
            if (!db.dao().isSaved(imdbId)) {
                db.dao().insert(WatchlistItem(
                    imdbId = imdbId, title = title, type = type,
                    year = year.takeIf { it.isNotBlank() },
                    imageUrl = imageUrl, cast = intentCast,
                    watchedAt = System.currentTimeMillis()
                ))
            } else {
                db.dao().markWatched(imdbId)
            }
        }

        // Trailer button: fetch TMDB video and open YouTube
        var tmdbTrailerId = -1
        trailerBtn.setOnClickListener {
            trailerBtn.text = "Loading..."
            thread {
                try {
                    if (tmdbTrailerId <= 0) {
                        val findJson = URL("https://api.themoviedb.org/3/find/$imdbId?api_key=${TMDBApi.API_KEY}&external_source=imdb_id").readText()
                        val root = JSONObject(findJson)
                        val results = if (isSeries) root.optJSONArray("tv_results") else root.optJSONArray("movie_results")
                        tmdbTrailerId = results?.optJSONObject(0)?.optInt("id", -1) ?: -1
                    }
                    if (tmdbTrailerId > 0) {
                        val videos = TMDBApi().fetchVideos(tmdbTrailerId, isSeries)
                        val trailer = videos.firstOrNull { it.site == "YouTube" && it.type in listOf("Trailer", "Teaser") }
                            ?: videos.firstOrNull { it.site == "YouTube" }
                        runOnUiThread {
                            trailerBtn.text = "▶ Trailer"
                            if (trailer != null) {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${trailer.key}")))
                            } else {
                                openTrailerFallback(title, year)
                            }
                        }
                    } else {
                        runOnUiThread { trailerBtn.text = "▶ Trailer"; openTrailerFallback(title, year) }
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        trailerBtn.text = "▶ Trailer"
                        Toast.makeText(this@DetailActivity, "Couldn't load trailer", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Set title early (will animate in after data loads)
        titleView.text = title

        // Shared element target must exist immediately using the incoming portrait image.
        // Backdrop stays independent and can load later.
        posterImage.alpha = 1f
        posterImage.scaleX = 1f
        posterImage.scaleY = 1f
        backdropImage.alpha = 0f
        heroShimmer.visibility = View.GONE

        var posterLoaded = false

        val posterIn = {
            if (!posterLoaded) {
                posterLoaded = true
                posterImage.visibility = View.VISIBLE
            }
        }

        if (imageUrl.isNotBlank()) {
            SimpleImageLoader.load(imageUrl, posterImage,
                onSuccess = { posterIn() },
                onError = { posterIn() }
            )
        } else {
            posterIn()
        }

        var currentCastForViewAll: List<TMDBCastMember> = emptyList()

        castViewAll.setOnClickListener {
            if (currentCastForViewAll.isNotEmpty()) {
                startActivity(Intent(this, FullCastActivity::class.java).apply {
                    putExtra(FullCastActivity.EXTRA_TITLE, title)
                    putStringArrayListExtra(FullCastActivity.EXTRA_NAMES, ArrayList(currentCastForViewAll.map { it.name }))
                    putStringArrayListExtra(FullCastActivity.EXTRA_CHARACTERS, ArrayList(currentCastForViewAll.map { it.character ?: "" }))
                    putStringArrayListExtra(FullCastActivity.EXTRA_PROFILES, ArrayList(currentCastForViewAll.map { it.profilePath ?: "" }))
                    putIntegerArrayListExtra(FullCastActivity.EXTRA_IDS, ArrayList(currentCastForViewAll.map { it.id }))
                })
            }
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

        // Parallax hero on scroll
        val scrollView = findViewById<ScrollView>(R.id.detail_scroll)
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val parallax = scrollY * 0.4f
            backdropImage.translationY = parallax
            posterImage.translationY = parallax * 0.18f
            // Fade out back button + hero as you scroll
            val fadeThreshold = 300f
            val alpha = (1f - scrollY / fadeThreshold).coerceIn(0f, 1f)
            backBtn.alpha = alpha
        }

        // Glow pulse on action buttons
        thread {
            Thread.sleep(800)
            runOnUiThread {
                FuturisticAnim.glowPulse(stremioBtn, 0.97f, 1.03f)
            }
        }

        // Fetch metadata — Cinemeta AND TMDB find in parallel
        // (type normalization now at top of onCreate)

        // Shared state: whichever thread gets tmdbId first fetches credits
        var creditsFetched = false
        val creditsLock = Any()
        var cachedTmdbCast: List<TMDBCastMember>? = null
        var cachedTmdbDirectors: List<TMDBCrewMember>? = null
        var cachedTmdbShow: TMDBShowDetails? = null

        fun animateSectionHeader(view: View, delay: Long = 0L) {
            view.visibility = View.VISIBLE
            view.translationY = 20f
            view.alpha = 0f
            view.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(320)
                .setStartDelay(delay)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        fun animateHorizontalItems(container: LinearLayout, delay: Long = 0L) {
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                child.translationY = 28f
                child.scaleX = 0.94f
                child.scaleY = 0.94f
                child.alpha = 0f
                child.animate()
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(360)
                    .setStartDelay(delay + i * 55L)
                    .setInterpolator(OvershootInterpolator(1.08f))
                    .start()
            }
        }

        // Apply cached TMDB credits to cast views (idempotent, called from UI thread)
        fun applyCreditsToUi(tmdbCast: List<TMDBCastMember>, tmdbDirectors: List<TMDBCrewMember>, tmdbShow: TMDBShowDetails?) {
            if (tmdbShow != null && tmdbShow.seasons > 0) {
                val showParts = mutableListOf<String>()
                showParts.add("${tmdbShow.seasons} season${if (tmdbShow.seasons != 1) "s" else ""}")
                if (tmdbShow.episodes > 0) {
                    showParts.add("${tmdbShow.episodes} episode${if (tmdbShow.episodes != 1) "s" else ""}")
                }
                metaView.text = showParts.joinToString(" · ")

                // Populate TV details card
                tvSeasons.text = "${tmdbShow.seasons}"
                tvEpisodes.text = "${tmdbShow.episodes}"
                val statusText = when (tmdbShow.status?.lowercase()) {
                    "returning series" -> "Returning"
                    "ended" -> "Ended"
                    "canceled" -> "Canceled"
                    else -> tmdbShow.status ?: "-"
                }
                tvStatus.text = statusText
                tvStatus.setTextColor(if (statusText == "Returning") Color.parseColor("#4CAF50") else Color.parseColor("#4DA6FF"))
                tvCard.visibility = View.VISIBLE
            }
            if (tmdbCast.isNotEmpty()) {
                currentCastForViewAll = tmdbCast
                animateSectionHeader(castHeader, 90)
                castScroll.visibility = View.VISIBLE
                castScroll.alpha = 0f
                castScroll.translationX = 24f
                castContainer.removeAllViews()
                for (member in tmdbCast.take(3)) {
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
                    nameView.text = member.name

                    if (!member.profilePath.isNullOrBlank()) {
                        val photoUrl = TMDBApi.profileImageLargeUrl(member.profilePath)
                        item.setOnClickListener {
                            if (member.id > 0) {
                                startActivity(Intent(this@DetailActivity, CastDetailActivity::class.java).apply {
                                    putExtra(CastDetailActivity.EXTRA_PERSON_ID, member.id)
                                    putExtra(CastDetailActivity.EXTRA_PERSON_NAME, member.name)
                                    putExtra(CastDetailActivity.EXTRA_PERSON_PHOTO, member.profilePath)
                                })
                            }
                        }
                        SimpleImageLoader.loadCastPhoto(photoUrl, photoView,
                            onSuccess = {
                                photoView.visibility = View.VISIBLE
                                avatarView.visibility = View.GONE
                            }
                        )
                    } else {
                        item.isClickable = false
                        item.isFocusable = false
                    }

                    castContainer.addView(item)
                }
                castScroll.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(260)
                    .setStartDelay(110)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                animateHorizontalItems(castContainer, 130)
            }

            if (tmdbDirectors.isNotEmpty()) {
                animateSectionHeader(directorHeader, 40)
                directorScroll.visibility = View.VISIBLE
                directorScroll.alpha = 0f
                directorScroll.translationX = 24f
                directorContainer.removeAllViews()
                for (member in tmdbDirectors.take(3)) {
                    val item = layoutInflater.inflate(R.layout.item_cast_member, directorContainer, false)
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
                    nameView.text = member.name

                    if (!member.profilePath.isNullOrBlank()) {
                        val photoUrl = TMDBApi.profileImageLargeUrl(member.profilePath)
                        SimpleImageLoader.loadCastPhoto(photoUrl, photoView,
                            onSuccess = {
                                photoView.visibility = View.VISIBLE
                                avatarView.visibility = View.GONE
                            }
                        )
                    }

                    if (member.id > 0) {
                        item.setOnClickListener {
                            startActivity(Intent(this@DetailActivity, CastDetailActivity::class.java).apply {
                                putExtra(CastDetailActivity.EXTRA_PERSON_ID, member.id)
                                putExtra(CastDetailActivity.EXTRA_PERSON_NAME, member.name)
                                putExtra(CastDetailActivity.EXTRA_PERSON_PHOTO, member.profilePath)
                            })
                        }
                    } else {
                        item.isClickable = false
                        item.isFocusable = false
                    }

                    directorContainer.addView(item)
                }
                directorScroll.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(260)
                    .setStartDelay(70)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
                animateHorizontalItems(directorContainer, 90)
            }
        }

        // Helper: fetch TMDB credits once, cache result, apply to UI when cast views are ready
        fun fetchCreditsAndUpdatePhotos(tmdbId: Int) {
            synchronized(creditsLock) {
                if (creditsFetched || tmdbId <= 0) return
                creditsFetched = true
            }
            try {
                val tmdbApi = TMDBApi()
                val tmdbCast = tmdbApi.fetchCredits(tmdbId, isSeries)
                val tmdbDirectors = tmdbApi.fetchDirectors(tmdbId, isSeries)
                val tmdbShow = if (isSeries) tmdbApi.fetchShowDetails(tmdbId) else null
                cachedTmdbCast = tmdbCast
                cachedTmdbDirectors = tmdbDirectors
                cachedTmdbShow = tmdbShow
                runOnUiThread { applyCreditsToUi(tmdbCast, tmdbDirectors, tmdbShow) }
            } catch (_: Exception) {
                synchronized(creditsLock) { creditsFetched = false }
            }
        }

        // Thread 1: TMDB find by IMDb ID (runs in parallel with Cinemeta)
        thread {
            try {
                val findJson = URL("https://api.themoviedb.org/3/find/$imdbId?api_key=${TMDBApi.API_KEY}&external_source=imdb_id").readText()
                val root = JSONObject(findJson)
                val results = if (isSeries) root.optJSONArray("tv_results") else root.optJSONArray("movie_results")
                if (results != null && results.length() > 0) {
                    val foundId = results.optJSONObject(0)?.optInt("id", -1) ?: -1
                    if (foundId > 0) fetchCreditsAndUpdatePhotos(foundId)
                }
            } catch (_: Exception) {}
        }

        // Thread 2: Cinemeta (title, rating, genres, description, cast initials)
        thread {
            try {
                val jsonText = URL("https://v3-cinemeta.strem.io/meta/$apiType/$imdbId.json").readText()
                val meta = JSONObject(jsonText).optJSONObject("meta") ?: return@thread

                val runtime = meta.optString("runtime", "")
                val imdbRating = meta.optString("imdbRating", "")
                val description = meta.optString("description", "")
                val bgUrl = meta.optString("background", "")
                val posterUrl = meta.optString("poster", "")
                val tmdbId = meta.optInt("moviedb_id", -1)

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

                // Cast from Cinemeta (show initials now, TMDB photos update later)
                val creditsCastArr = meta.optJSONArray("credits_cast")
                val castArr = if (creditsCastArr != null && creditsCastArr.length() > 0) {
                    creditsCastArr
                } else {
                    meta.optJSONArray("cast")
                }
                val cinemetaCast = mutableListOf<CastMember>()
                if (castArr != null) {
                    for (i in 0 until castArr.length()) {
                        val c = castArr.opt(i)
                        when (c) {
                            is JSONObject -> {
                                val pp = c.optString("profile_path", "")
                                cinemetaCast.add(CastMember(name = c.optString("name", ""), profilePath = pp.ifBlank { null }))
                            }
                            is String -> {
                                if (c.isNotBlank()) cinemetaCast.add(CastMember(name = c, profilePath = null))
                            }
                        }
                    }
                }
                if (cinemetaCast.isEmpty() && !intentCast.isNullOrBlank()) {
                    intentCast.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        .forEach { cinemetaCast.add(CastMember(name = it, profilePath = null)) }
                }

                runOnUiThread {
                    // Show Cinemeta data immediately
                    val landscapeUrl = if (bgUrl.isNotBlank()) bgUrl else posterUrl
                    if (landscapeUrl.isNotBlank()) {
                        SimpleImageLoader.load(landscapeUrl, backdropImage,
                            onSuccess = {
                                backdropImage.animate().alpha(1f).setDuration(350).start()
                            },
                            onError = {
                                if (imageUrl.isNotBlank()) {
                                    SimpleImageLoader.load(imageUrl, backdropImage,
                                        onSuccess = { backdropImage.animate().alpha(1f).setDuration(350).start() },
                                        onError = { backdropImage.alpha = 1f }
                                    )
                                } else {
                                    backdropImage.alpha = 1f
                                }
                            }
                        )
                    }
                    // Keep the portrait poster locked to the incoming source image so
                    // shared-element transitions from search/discover stay visually identical.
                    posterIn()

                    // Meta: show basic info now (TMDB seasons may update later)
                    val metaParts = mutableListOf<String>()
                    if (year.isNotBlank()) metaParts.add(year)
                    if (runtime.isNotBlank()) metaParts.add(runtime)
                    metaView.text = metaParts.joinToString(" · ").ifBlank { type }
                    metaView.visibility = View.VISIBLE
                    metaView.translationX = -80f
                    metaView.alpha = 0f
                    metaView.animate().translationX(0f).alpha(1f).setDuration(400).setStartDelay(150)
                        .setInterpolator(DecelerateInterpolator(1.5f)).start()

                    // Rating
                    if (imdbRating.isNotBlank()) {
                        ratingView.text = "★ $imdbRating IMDb"
                    } else {
                        ratingView.text = "IMDb --"
                    }
                    ratingRow.visibility = View.VISIBLE
                    ratingView.visibility = View.VISIBLE
                    ratingRow.translationX = 80f
                    ratingRow.alpha = 0f
                    ratingRow.animate().translationX(0f).alpha(1f).setDuration(400).setStartDelay(200)
                        .setInterpolator(DecelerateInterpolator(1.5f)).start()

                    if (directors.isNotEmpty()) {
                        animateSectionHeader(directorHeader, 540)
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
                    genresContainer.visibility = View.VISIBLE
                    for (i in 0 until genresContainer.childCount) {
                        val child = genresContainer.getChildAt(i)
                        child.scaleX = 0f; child.scaleY = 0f; child.alpha = 0f
                        child.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(350).setStartDelay(300 + i * 60L)
                            .setInterpolator(OvershootInterpolator(1.25f)).start()
                    }

                    // Divider + About
                    divider.visibility = View.VISIBLE
                    divider.scaleX = 0f
                    divider.animate().scaleX(1f).setDuration(400).setStartDelay(400)
                        .setInterpolator(DecelerateInterpolator(2f)).start()

                    aboutLabel.visibility = View.VISIBLE
                    aboutLabel.translationY = 25f; aboutLabel.alpha = 0f
                    aboutLabel.animate().translationY(0f).alpha(1f).setDuration(300).setStartDelay(480)
                        .setInterpolator(DecelerateInterpolator()).start()

                    // Description
                    if (description.isNotBlank()) {
                        descView.text = description
                    }
                    descView.visibility = View.VISIBLE
                    descView.translationY = 30f; descView.alpha = 0f
                    descView.animate().translationY(0f).alpha(1f).setDuration(400).setStartDelay(510)
                        .setInterpolator(DecelerateInterpolator()).start()

                    // Show cast with initials immediately (photos update when TMDB arrives)
                    if (cinemetaCast.isNotEmpty()) {
                        castContainer.removeAllViews()
                        val avatarColors = listOf(
                            "#5B6E9A", "#9E7A3E", "#9B5268", "#42989E",
                            "#7C6BA0", "#439A6E", "#9B5E7E", "#5B82B0"
                        )
                        for ((i, member) in cinemetaCast.withIndex()) {
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
                            item.isClickable = false
                            item.isFocusable = false

                            castContainer.addView(item)
                        }

                        animateSectionHeader(castHeader, 560)

                        castScroll.visibility = View.VISIBLE
                        castScroll.alpha = 0f
                        castScroll.translationX = 24f
                        castScroll.animate().translationX(0f).alpha(1f)
                            .setDuration(260).setStartDelay(575)
                            .setInterpolator(DecelerateInterpolator()).start()
                        animateHorizontalItems(castContainer, 580)
                    }

                    // Animate title row
                    titleRow.visibility = View.VISIBLE
                    titleView.translationY = -60f; titleView.alpha = 0f
                    titleView.animate().translationY(0f).alpha(1f).setDuration(450).setStartDelay(50)
                        .setInterpolator(DecelerateInterpolator()).start()
                    // Stremio icon pops in with bounce
                    stremioBtn.scaleX = 0f; stremioBtn.scaleY = 0f
                    stremioBtn.animate().scaleX(1f).scaleY(1f).setDuration(400).setStartDelay(300)
                        .setInterpolator(OvershootInterpolator(1.3f)).start()
                }

                // Apply TMDB credits if parallel thread already fetched them
                cachedTmdbCast?.let { cast -> runOnUiThread { applyCreditsToUi(cast, cachedTmdbDirectors ?: emptyList(), cachedTmdbShow) } }
                // Fire TMDB credits using Cinemeta's tmdb_id (other thread may have already done it)
                if (tmdbId > 0) fetchCreditsAndUpdatePhotos(tmdbId)
            } catch (_: Exception) {
                runOnUiThread {
                    titleRow.visibility = View.VISIBLE
                    titleView.translationY = -60f; titleView.alpha = 0f
                    titleView.animate().translationY(0f).alpha(1f)
                        .setDuration(450).setInterpolator(DecelerateInterpolator()).start()
                    stremioBtn.scaleX = 0f; stremioBtn.scaleY = 0f
                    stremioBtn.animate().scaleX(1f).scaleY(1f).setDuration(400).setStartDelay(300)
                        .setInterpolator(OvershootInterpolator(1.3f)).start()
                }
            }
        }
    }

    private fun navigateBackToDiscover(imdbId: String) {
        val discoverType = intent?.getStringExtra(EXTRA_RETURN_DISCOVER_TYPE).orEmpty()
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_DISCOVER_TITLE
            putExtra(MainActivity.EXTRA_DISCOVER_IMDB_ID, imdbId)
            putExtra(MainActivity.EXTRA_DISCOVER_TYPE, discoverType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    private fun shimmerPulse(view: View) {
        view.animate().cancel()
        view.alpha = 0.15f
        view.animate()
            .alpha(0.5f)
            .setDuration(800)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .alpha(0.15f)
                    .setDuration(800)
                    .withEndAction { shimmerPulse(view) }
                    .start()
            }
            .start()
    }

    private fun openTrailerFallback(title: String, year: String) {
        val query = "$title ${if (year.isNotBlank()) year else ""} official trailer"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
            "https://www.youtube.com/results?search_query=${java.net.URLEncoder.encode(query, "UTF-8")}"
        )))
    }
}

private data class CastMember(val name: String, val profilePath: String?)
