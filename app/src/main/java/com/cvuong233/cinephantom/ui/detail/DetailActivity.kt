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
import com.cvuong233.cinephantom.data.RatingFetcher
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

        fun applyRating(rawRatingText: String?, fallbackRating: Float? = null) {
            val cleanText = rawRatingText?.trim().orEmpty()
            val numericText = cleanText.toFloatOrNull()?.let { String.format(java.util.Locale.US, "%.1f", it) }
            when {
                fallbackRating != null && fallbackRating > 0f -> ratingView.text = String.format(java.util.Locale.US, "★ %.1f IMDb", fallbackRating)
                numericText != null -> ratingView.text = "★ $numericText IMDb"
                else -> ratingView.text = "IMDb --"
            }
        }

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

        applyRating(null, null)

        thread {
            try {
                val fetched = RatingFetcher().fetchRating(imdbId)
                if (fetched != null && fetched > 0f) {
                    runOnUiThread {
                        applyRating(null, fetched)
                        ratingRow.visibility = View.VISIBLE
                        ratingView.visibility = View.VISIBLE
                    }
                }
            } catch (_: Exception) {}
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

        // Fetch metadata from TMDB

        // Shared state: whichever thread gets tmdbId first fetches credits
        var creditsFetched = false
        val creditsLock = Any()
        var cachedTmdbCast: List<TMDBCastMember>? = null
        var cachedTmdbDirectors: List<TMDBCrewMember>? = null
        var cachedTmdbShow: TMDBShowDetails? = null
        var tmdbCreditsApplied = false
        val fallbackHandler = Handler(Looper.getMainLooper())

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
            tmdbCreditsApplied = true
            fallbackHandler.removeCallbacksAndMessages(null)
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

        thread {
            try {
                val details = TMDBApi().fetchTitleDetailsByImdb(imdbId, preferSeries = isSeries)

                runOnUiThread {
                    val backdropPath = details?.backdropPath
                    val posterPath = details?.posterPath
                    val landscapeUrl = when {
                        !backdropPath.isNullOrBlank() -> "https://image.tmdb.org/t/p/w780$backdropPath"
                        !posterPath.isNullOrBlank() -> "https://image.tmdb.org/t/p/w780$posterPath"
                        else -> ""
                    }
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
                    posterIn()

                    val runtimeText = details?.runtimeMinutes?.takeIf { it > 0 }?.let { "$it min" }
                    val displayYear = details?.year ?: year
                    val metaParts = mutableListOf<String>()
                    if (displayYear.isNotBlank()) metaParts.add(displayYear)
                    if (!runtimeText.isNullOrBlank()) metaParts.add(runtimeText)
                    metaView.text = metaParts.joinToString(" · ").ifBlank { type }
                    metaView.visibility = View.VISIBLE
                    metaView.translationX = -80f
                    metaView.alpha = 0f
                    metaView.animate().translationX(0f).alpha(1f).setDuration(400).setStartDelay(150)
                        .setInterpolator(DecelerateInterpolator(1.5f)).start()

                    details?.rating?.let { applyRating(null, it) }
                    ratingRow.visibility = View.VISIBLE
                    ratingView.visibility = View.VISIBLE
                    ratingRow.translationX = 80f
                    ratingRow.alpha = 0f
                    ratingRow.animate().translationX(0f).alpha(1f).setDuration(400).setStartDelay(200)
                        .setInterpolator(DecelerateInterpolator(1.5f)).start()

                    val genres = details?.genres.orEmpty()
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

                    divider.visibility = View.VISIBLE
                    divider.scaleX = 0f
                    divider.animate().scaleX(1f).setDuration(400).setStartDelay(400)
                        .setInterpolator(DecelerateInterpolator(2f)).start()

                    aboutLabel.visibility = View.VISIBLE
                    aboutLabel.translationY = 25f; aboutLabel.alpha = 0f
                    aboutLabel.animate().translationY(0f).alpha(1f).setDuration(300).setStartDelay(480)
                        .setInterpolator(DecelerateInterpolator()).start()

                    val description = details?.overview.orEmpty()
                    if (description.isNotBlank()) {
                        descView.text = description
                    }
                    descView.visibility = View.VISIBLE
                    descView.translationY = 30f; descView.alpha = 0f
                    descView.animate().translationY(0f).alpha(1f).setDuration(400).setStartDelay(510)
                        .setInterpolator(DecelerateInterpolator()).start()

                    titleRow.visibility = View.VISIBLE
                    titleView.translationY = -60f; titleView.alpha = 0f
                    titleView.animate().translationY(0f).alpha(1f).setDuration(450).setStartDelay(50)
                        .setInterpolator(DecelerateInterpolator()).start()
                    stremioBtn.scaleX = 0f; stremioBtn.scaleY = 0f
                    stremioBtn.animate().scaleX(1f).scaleY(1f).setDuration(400).setStartDelay(300)
                        .setInterpolator(OvershootInterpolator(1.3f)).start()
                }

                details?.showDetails?.let { cachedTmdbShow = it }
                details?.tmdbId?.takeIf { it > 0 }?.let { fetchCreditsAndUpdatePhotos(it) }

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
