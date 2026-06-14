package com.cvuong233.cinephantom.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
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
import com.cvuong233.cinephantom.widget.WidgetDataFetcher
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.MainActivity
import com.cvuong233.cinephantom.ui.search.SimpleImageLoader
import org.json.JSONObject
import java.net.URL
import com.cvuong233.cinephantom.data.RatingFetcher
import com.cvuong233.cinephantom.data.TMDBApi
import com.cvuong233.cinephantom.data.TMDBCastMember
import com.cvuong233.cinephantom.data.TMDBCrewMember
import com.cvuong233.cinephantom.data.TMDBPersonCredit
import com.cvuong233.cinephantom.data.TMDBShowDetails
import com.cvuong233.cinephantom.data.FavoritesRepository
import com.cvuong233.cinephantom.notifications.WishlistNotificationScheduler
import com.cvuong233.cinephantom.data.WatchlistDatabase
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.model.WatchlistItem
import com.cvuong233.cinephantom.ui.account.AuthActivity
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicReference

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
        const val EXTRA_FUNDEX_RATING = "extra_fundex_rating"
        const val EXTRA_TMDB_ID = "extra_tmdb_id"
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
        val fundexRatingFromIntent = intent?.getStringExtra(EXTRA_FUNDEX_RATING)
        val seedTmdbId = intent?.getIntExtra(EXTRA_TMDB_ID, -1)?.takeIf { it > 0 } ?: -1

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
        val nextEpCard = findViewById<LinearLayout>(R.id.detail_next_episode_card)
        val nextEpCodeView = findViewById<TextView>(R.id.detail_next_episode_code)
        val nextEpNameView = findViewById<TextView>(R.id.detail_next_episode_name)
        val nextEpDateView = findViewById<TextView>(R.id.detail_next_episode_date)
        val favBtn = findViewById<ImageView>(R.id.detail_favorite_button)

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
        ratingRow.visibility = View.VISIBLE
        ratingView.visibility = View.INVISIBLE

        val ratingFetcher = RatingFetcher()
        val preloadedRating = AtomicReference<Float?>(null)

        fun revealRating(ratingValue: Float?, delay: Long = 0L) {
            if (ratingValue == null || ratingValue <= 0f) return
            applyRating(null, ratingValue)
            ratingView.alpha = 0f
            ratingView.visibility = View.VISIBLE
            ratingView.animate().cancel()
            ratingView.animate()
                .alpha(1f)
                .setDuration(340)
                .setStartDelay(delay)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        fun revealFundexRating(text: String, delay: Long = 0L) {
            val numeric = text.trim().removeSuffix("%").toFloatOrNull()
            val formatted = if (numeric != null)
                "★ ${String.format(java.util.Locale.US, "%.1f", numeric)}% FUNdex"
            else "★ ${text.trim()} FUNdex"
            ratingView.text = formatted
            ratingView.alpha = 0f
            ratingView.visibility = View.VISIBLE
            ratingView.animate().cancel()
            ratingView.animate()
                .alpha(1f)
                .setDuration(340)
                .setStartDelay(delay)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        if (fundexRatingFromIntent == null) {
            thread {
                try {
                    val fetched = ratingFetcher.fetchRating(imdbId)
                    if (fetched != null && fetched > 0f) {
                        preloadedRating.set(fetched)
                    }
                } catch (_: Exception) {}
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
        var tmdbTrailerId = seedTmdbId  // pre-seeded if caller knows TMDB ID (e.g. K-drama)
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

        // Favorite/Wishlist button — captures TMDB air date once loaded (see below)
        val titleObj = ImdbTitle(
            id = imdbId, title = title, typeLabel = type, year = year,
            cast = null, imageUrl = imageUrl,
            tmdbId = seedTmdbId.takeIf { it > 0 },
            ratingSourceLabel = if (fundexRatingFromIntent != null) "FUNdex" else null
        )
        // Populated by TMDB callbacks below so the heart click can schedule the notification
        var wishlistMovieReleaseDate: String? = null
        var wishlistNextEpisode: com.cvuong233.cinephantom.data.TMDBNextEpisode? = null

        fun refreshFavIcon() {
            favBtn.setImageResource(
                if (FavoritesRepository.isFavorite(imdbId)) R.drawable.ic_heart_filled
                else R.drawable.ic_heart_outline
            )
        }
        refreshFavIcon()
        favBtn.setOnClickListener {
            if (FirebaseAuth.getInstance().currentUser == null) {
                Toast.makeText(this, "Sign in to save to Wishlist", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, AuthActivity::class.java))
                return@setOnClickListener
            }
            val wasInWishlist = FavoritesRepository.isFavorite(imdbId)
            FavoritesRepository.toggle(titleObj)
            refreshFavIcon()
            if (!wasInWishlist) {
                // Added — schedule notification if we already have the date
                val airDate = if (isSeries) wishlistNextEpisode?.airDate else wishlistMovieReleaseDate
                if (!airDate.isNullOrBlank()) {
                    WishlistNotificationScheduler.schedule(
                        context = this,
                        imdbId = imdbId,
                        title = title,
                        isTV = isSeries,
                        airDate = airDate,
                        season = wishlistNextEpisode?.seasonNumber ?: 0,
                        episode = wishlistNextEpisode?.episodeNumber ?: 0,
                        imageUrl = imageUrl,
                    )
                }
            } else {
                // Removed — cancel any scheduled notification
                WishlistNotificationScheduler.cancel(this, imdbId)
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
            fallbackHandler.removeCallbacksAndMessages(null)
            if (tmdbShow != null && tmdbShow.seasons > 0) {
                val showParts = mutableListOf<String>()
                showParts.add("${tmdbShow.seasons} season${if (tmdbShow.seasons != 1) "s" else ""}")
                if (tmdbShow.episodes > 0) {
                    showParts.add("${tmdbShow.episodes} episode${if (tmdbShow.episodes != 1) "s" else ""}")
                }
                metaView.text = showParts.joinToString(" · ")

                val nextEp = tmdbShow.nextEpisode
                wishlistNextEpisode = nextEp  // capture for notification scheduling
                val isReturning = tmdbShow.status?.lowercase()?.let {
                    it == "returning series" || it == "in production" || it == "planned"
                } == true
                if (isReturning && nextEp != null) {
                    nextEpCodeView.text = "S${nextEp.seasonNumber} · E${nextEp.episodeNumber}"
                    nextEpNameView.text = nextEp.name?.takeIf { it.isNotBlank() } ?: "Episode ${nextEp.episodeNumber}"
                    val airDateStr = nextEp.airDate
                    if (!airDateStr.isNullOrBlank()) {
                        try {
                            val airDate = java.time.LocalDate.parse(airDateStr)
                            val today = java.time.LocalDate.now()
                            val days = java.time.temporal.ChronoUnit.DAYS.between(today, airDate).toInt()
                            val displayDate = airDate.format(
                                java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)
                            )
                            val countdown = when {
                                days < -1 -> "aired"
                                days == -1 -> "yesterday"
                                days == 0 -> "Today"
                                days == 1 -> "Tomorrow"
                                else -> "in $days days"
                            }
                            nextEpDateView.text = "$displayDate  ·  $countdown"
                        } catch (_: Exception) {
                            nextEpDateView.text = airDateStr
                        }
                    } else {
                        nextEpDateView.visibility = View.GONE
                    }
                    nextEpCard.visibility = View.VISIBLE
                    nextEpCard.alpha = 0f
                    nextEpCard.translationY = 18f
                    nextEpCard.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(340)
                        .setStartDelay(60)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                } else {
                    nextEpCard.visibility = View.GONE
                }
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

        val recsSection = findViewById<LinearLayout>(R.id.detail_more_like_this_section)
        val recsContainer = findViewById<LinearLayout>(R.id.detail_recommendations_container)
        var recsFetched = false

        fun displayRecommendations(recs: List<TMDBPersonCredit>) {
            recsContainer.removeAllViews()
            for (rec in recs) {
                val card = layoutInflater.inflate(R.layout.item_recommendation_card, recsContainer, false)
                val poster = card.findViewById<ImageView>(R.id.rec_poster)
                val initial = card.findViewById<TextView>(R.id.rec_initial)
                val titleView = card.findViewById<TextView>(R.id.rec_title)
                val yearView = card.findViewById<TextView>(R.id.rec_year)

                titleView.text = rec.title
                initial.text = rec.title.firstOrNull()?.uppercaseChar()?.toString() ?: "?"

                val yearText = rec.releaseDate?.take(4).orEmpty()
                if (yearText.isNotBlank()) {
                    yearView.text = yearText
                    yearView.visibility = View.VISIBLE
                }

                val posterPath = rec.posterPath
                if (!posterPath.isNullOrBlank()) {
                    SimpleImageLoader.load(
                        url = "https://image.tmdb.org/t/p/w185$posterPath",
                        imageView = poster,
                        onSuccess = { poster.visibility = View.VISIBLE; initial.visibility = View.GONE }
                    )
                }

                card.setOnClickListener {
                    val imdbIdForRec = rec.imdbId
                    if (imdbIdForRec.isNullOrBlank()) {
                        Toast.makeText(this@DetailActivity, "Still loading — try again in a moment.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val typeLabel = if (rec.mediaType == "tv") "TV Series" else "Movie"
                    val posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w185$it" } ?: ""
                    startActivity(Intent(this@DetailActivity, DetailActivity::class.java).apply {
                        putExtra(EXTRA_IMDB_ID, imdbIdForRec)
                        putExtra(EXTRA_TITLE, rec.title)
                        putExtra(EXTRA_IMAGE_URL, posterUrl)
                        putExtra(EXTRA_YEAR, yearText)
                        putExtra(EXTRA_TYPE, typeLabel)
                        putExtra(EXTRA_TMDB_ID, rec.id)
                    })
                }

                recsContainer.addView(card)
            }

            recsSection.visibility = View.VISIBLE
            recsSection.alpha = 0f
            recsSection.translationY = 28f
            recsSection.animate()
                .alpha(1f).translationY(0f)
                .setDuration(380).setStartDelay(120)
                .setInterpolator(DecelerateInterpolator(1.3f))
                .start()

            // Lazy-resolve IMDb IDs so taps work once they complete
            thread {
                for (rec in recs) {
                    if (rec.imdbId.isNullOrBlank()) {
                        rec.imdbId = TMDBApi().fetchImdbIdForTitle(rec.id, rec.mediaType)
                    }
                }
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
                runOnUiThread { applyCreditsToUi(tmdbCast, tmdbDirectors, tmdbShow) }

                if (!recsFetched) {
                    recsFetched = true
                    val recs = tmdbApi.fetchRecommendations(tmdbId, isSeries)
                    if (recs.isNotEmpty()) runOnUiThread { displayRecommendations(recs) }
                }
            } catch (_: Exception) {
                synchronized(creditsLock) { creditsFetched = false }
            }
        }

        // If the caller already knows the TMDB ID (e.g., K-drama list), fetch credits immediately
        // in parallel — don't wait for the IMDb→TMDB lookup to complete first.
        if (seedTmdbId > 0) thread { fetchCreditsAndUpdatePhotos(seedTmdbId) }

        thread {
            try {
                val details = TMDBApi().fetchTitleDetailsByImdb(imdbId, preferSeries = isSeries)
                val effectiveFundexRating = fundexRatingFromIntent
                    ?: WidgetDataFetcher.findKdramaSeed(this@DetailActivity, imdbId)
                        ?.ratingText?.takeIf { it.isNotBlank() }

                runOnUiThread {
                    // Capture release date for movie wishlist notification scheduling
                    if (!isSeries) wishlistMovieReleaseDate = details?.releaseDate

                    // Movie release date card
                    if (!isSeries) {
                        val movieReleaseCard = findViewById<LinearLayout>(R.id.detail_movie_release_card)
                        val movieReleaseLabelView = findViewById<android.widget.TextView>(R.id.detail_movie_release_label)
                        val movieReleaseDateView = findViewById<android.widget.TextView>(R.id.detail_movie_release_date)
                        val relDate = details?.releaseDate
                        if (!relDate.isNullOrBlank()) {
                            try {
                                val releaseDate = java.time.LocalDate.parse(relDate)
                                val today = java.time.LocalDate.now()
                                val days = java.time.temporal.ChronoUnit.DAYS.between(today, releaseDate).toInt()
                                val displayDate = releaseDate.format(
                                    java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US)
                                )
                                val countdown = when {
                                    days < 0 -> "Released"
                                    days == 0 -> "Today"
                                    days == 1 -> "Tomorrow"
                                    else -> "in $days days"
                                }
                                movieReleaseLabelView.text = if (days < 0) "RELEASED" else "RELEASES"
                                movieReleaseDateView.text = "$displayDate  ·  $countdown"
                                movieReleaseCard.visibility = View.VISIBLE
                                movieReleaseCard.alpha = 0f
                                movieReleaseCard.translationY = 18f
                                movieReleaseCard.animate()
                                    .alpha(1f).translationY(0f)
                                    .setDuration(340).setStartDelay(60)
                                    .setInterpolator(DecelerateInterpolator())
                                    .start()
                            } catch (_: Exception) { /* no-op */ }
                        }
                    }

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

                    if (effectiveFundexRating != null) {
                        revealFundexRating(effectiveFundexRating, 180)
                    } else {
                        val preferredRating = preloadedRating.get() ?: ratingFetcher.fetchCachedOrChartRating(imdbId) ?: details?.rating
                        revealRating(preferredRating, 180)
                    }

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
