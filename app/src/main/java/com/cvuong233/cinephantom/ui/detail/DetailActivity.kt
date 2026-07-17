package com.cvuong233.cinephantom.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.ChangeBounds
import android.transition.ChangeImageTransform
import android.transition.Fade
import android.transition.TransitionSet
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
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.cvuong233.cinephantom.ui.FuturisticAnim
import com.cvuong233.cinephantom.widget.WidgetDataFetcher
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.MainActivity
import com.cvuong233.cinephantom.ui.search.SimpleImageLoader
import org.json.JSONObject
import java.net.URL
import com.cvuong233.cinephantom.data.JustWatchLinkResolver
import com.cvuong233.cinephantom.data.RatingFetcher
import com.cvuong233.cinephantom.data.TMDBApi
import com.cvuong233.cinephantom.data.TMDBCastMember
import com.cvuong233.cinephantom.data.TMDBCrewMember
import com.cvuong233.cinephantom.data.TMDBPersonCredit
import com.cvuong233.cinephantom.data.TMDBShowDetails
import com.cvuong233.cinephantom.data.TMDBWatchProvider
import com.cvuong233.cinephantom.data.WatchProviderPreferences
import com.cvuong233.cinephantom.data.FavoritesRepository
import com.cvuong233.cinephantom.notifications.WatchlistNotificationScheduler
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.account.AuthActivity
import com.google.firebase.auth.FirebaseAuth
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
        const val EXTRA_BACKDROP_URL = "extra_backdrop_url"
        const val EXTRA_FROM_WIDGET = "extra_from_widget"
        const val EXTRA_RETURN_DISCOVER_TYPE = "extra_return_discover_type"
        const val EXTRA_FUNDEX_RATING = "extra_fundex_rating"
        const val EXTRA_TMDB_ID = "extra_tmdb_id"
        // Which ImageView the incoming shared-element transition should land on: "poster" or
        // "backdrop". Callers that launch from a vertical poster card (Watchlist, widget) should
        // pass "poster"; landscape backdrop cards (Discover, Search, K-Drama) can omit this.
        const val EXTRA_TRANSITION_TARGET = "extra_transition_target"
        const val TRANSITION_TARGET_POSTER = "poster"
        const val TRANSITION_TARGET_BACKDROP = "backdrop"

        private fun buildHeroSharedElementTransition(): TransitionSet =
            TransitionSet()
                .addTransition(ChangeBounds())
                .addTransition(ChangeImageTransform())
                .setDuration(300)
                .setInterpolator(FastOutSlowInInterpolator())
    }

    // Backdrop loads that must wait until the incoming shared-element flight (poster -> hero)
    // has finished, so the backdrop never flashes the poster's image mid-flight (see
    // onEnterAnimationComplete below).
    private var enterAnimationCompleted = false
    private val pendingBackdropActions = mutableListOf<() -> Unit>()
    private var enterTransitionStarted = false

    private fun runAfterEnterTransition(action: () -> Unit) {
        if (enterAnimationCompleted) action() else pendingBackdropActions.add(action)
    }

    private fun startPostponedEnterTransitionSafely() {
        if (enterTransitionStarted) return
        enterTransitionStarted = true
        startPostponedEnterTransition()
    }

    // Runs the deferred entrance actions (backdrop ease-in, poster spring, streaming row) exactly
    // once. Normally driven by onEnterAnimationComplete, but that callback only fires when there is
    // an actual shared-element/window transition — launches without one (e.g. a recommendation tap)
    // would otherwise never animate the hero companion in, so a Handler fallback also calls this.
    private fun flushPendingEnterActions() {
        if (enterAnimationCompleted) return
        enterAnimationCompleted = true
        val actions = pendingBackdropActions.toList()
        pendingBackdropActions.clear()
        actions.forEach { it() }
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        flushPendingEnterActions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)

        // Shared element (poster or backdrop, decided below) scales smoothly into place
        // instead of snapping to its destination bounds; ChangeImageTransform keeps the
        // bitmap itself scaling rather than stretching.
        window.sharedElementEnterTransition = buildHeroSharedElementTransition()
        window.sharedElementReturnTransition = buildHeroSharedElementTransition()
        // The hero images arrive via the shared element (or load directly) — they must not
        // also play the activity's own enter fade, or they'd double-animate.
        window.enterTransition = Fade().apply {
            duration = 220
            excludeTarget(R.id.detail_backdrop, true)
            excludeTarget(R.id.detail_hero, true)
            excludeTarget(android.R.id.statusBarBackground, true)
            excludeTarget(android.R.id.navigationBarBackground, true)
        }
        postponeEnterTransition()
        Handler(Looper.getMainLooper()).postDelayed({ startPostponedEnterTransitionSafely() }, 500)
        // Safety net: if no shared-element/window transition ever reports completion, run the
        // deferred hero-companion + streaming-row entrance anyway so nothing is stuck hidden.
        Handler(Looper.getMainLooper()).postDelayed({ flushPendingEnterActions() }, 600)

        // EXTRA_TMDB_ID may be the only id a caller has (e.g. a chart item whose IMDb id
        // failed to resolve at scrape time) — fall back to it before giving up on the intent.
        val seedTmdbId = intent?.getIntExtra(EXTRA_TMDB_ID, -1)?.takeIf { it > 0 } ?: -1
        val imdbId = intent?.getStringExtra(EXTRA_IMDB_ID)?.takeIf { it.isNotBlank() }
            ?: seedTmdbId.takeIf { it > 0 }?.toString()
            ?: run { finish(); return }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Unknown"
        val type = intent?.getStringExtra(EXTRA_TYPE) ?: "Movie"
        val year = intent?.getStringExtra(EXTRA_YEAR) ?: ""
        val imageUrl = intent?.getStringExtra(EXTRA_IMAGE_URL) ?: ""
        val intentCast = intent?.getStringExtra(EXTRA_CAST)
        val launchedFromWidget = intent?.getBooleanExtra(EXTRA_FROM_WIDGET, false) == true
        val fundexRatingFromIntent = intent?.getStringExtra(EXTRA_FUNDEX_RATING)
        // True when opened from Search — only a TMDB ID is known, no real IMDb ID yet.
        // Real IMDb IDs always start with "tt"; numeric strings are TMDB IDs.
        val isTmdbOnly = !imdbId.startsWith("tt") && seedTmdbId > 0
        // Holds the real IMDb ID once resolved; null until then for TMDB-only opens.
        var resolvedImdbId: String? = if (isTmdbOnly) null else imdbId

        // Normalize type early — needed by trailer button and metadata threads
        val typeLower = type?.lowercase() ?: ""
        val isSeries = typeLower.contains("series") ||
                       typeLower.contains("tv episode") ||
                       typeLower == "mini series"
        val apiType = if (isSeries) "series" else "movie"

        // Views
        val backBtn = findViewById<ImageView>(R.id.detail_back)
        val posterImage = findViewById<ImageView>(R.id.detail_hero)
        val posterContainer = findViewById<LinearLayout>(R.id.detail_poster_container)
        val backdropImage = findViewById<ImageView>(R.id.detail_backdrop)
        val incomingTransitionName = intent?.getStringExtra(EXTRA_TRANSITION_NAME)
        // Discover/Search/K-Drama launch with a landscape backdrop card, so the backdrop is the
        // shared-element landing target by default. Callers with a vertical poster card (widget,
        // Watchlist) pass EXTRA_TRANSITION_TARGET = "poster" so the poster is the target instead.
        val transitionTarget = intent?.getStringExtra(EXTRA_TRANSITION_TARGET)
            ?: if (launchedFromWidget) TRANSITION_TARGET_POSTER else TRANSITION_TARGET_BACKDROP
        if (transitionTarget == TRANSITION_TARGET_POSTER) {
            ViewCompat.setTransitionName(posterImage, incomingTransitionName ?: "poster_$imdbId")
            ViewCompat.setTransitionName(backdropImage, "backdrop_$imdbId")
        } else {
            ViewCompat.setTransitionName(backdropImage, incomingTransitionName ?: "backdrop_$imdbId")
            ViewCompat.setTransitionName(posterImage, "poster_$imdbId")
        }
        val heroShimmer = findViewById<View>(R.id.detail_hero_shimmer)
        val titleView = findViewById<TextView>(R.id.detail_title)
        val titleRow = findViewById<LinearLayout>(R.id.detail_title_row)
        val metaView = findViewById<TextView>(R.id.detail_meta)
        val ratingView = findViewById<TextView>(R.id.detail_rating)
        val streamingScroll = findViewById<HorizontalScrollView>(R.id.detail_streaming_scroll)
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
        val trailerBtn = findViewById<TextView>(R.id.detail_trailer_button)
        val nextEpCard = findViewById<LinearLayout>(R.id.detail_next_episode_card)
        val nextEpCodeView = findViewById<TextView>(R.id.detail_next_episode_code)
        val nextEpNameView = findViewById<TextView>(R.id.detail_next_episode_name)
        val nextEpDateView = findViewById<TextView>(R.id.detail_next_episode_date)
        val favBtn = findViewById<ImageView>(R.id.detail_favorite_button)
        val watchProviderPrefs = WatchProviderPreferences.get(this)
        if (!watchProviderPrefs.isEnabled(WatchProviderPreferences.STREMIO_SENTINEL_ID)) {
            stremioBtn.visibility = View.GONE
        }

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
        // The whole streaming row fades/slides in as part of the entrance choreography (see the
        // runAfterEnterTransition below) — keep it laid out but transparent until then so it never
        // flashes before the hero settles.
        streamingScroll.visibility = View.VISIBLE
        streamingScroll.alpha = 0f
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
            val shareText = "$title — https://www.imdb.com/title/${resolvedImdbId ?: imdbId}/"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                setType("text/plain")
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_title)))
        }

        // Trailer button: fetch TMDB video and open YouTube
        var tmdbTrailerId = seedTmdbId  // pre-seeded if caller knows TMDB ID
        trailerBtn.setOnClickListener {
            trailerBtn.text = "Loading..."
            thread {
                try {
                    if (tmdbTrailerId <= 0 && !isTmdbOnly) {
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

        // Two hero images sit at the top: a landscape backdrop and a portrait poster. Exactly
        // one of them is the incoming shared element (it flies in from the tapped card); the
        // other has no counterpart on the previous screen, so it plays its own enter animation
        // once the shared-element flight has landed (see runAfterEnterTransition).
        //   • Case A — landscape card (Discover/Search/K-Drama): backdrop is the shared element;
        //     the poster springs up from below on its own.
        //   • Case B — portrait card (Watchlist/widget): poster is the shared element; the
        //     backdrop eases down from the top + fades in on its own.
        val isSharedPoster = transitionTarget == TRANSITION_TARGET_POSTER
        // A portrait poster URL is NOT a valid landscape backdrop — Watchlist passes no
        // EXTRA_BACKDROP_URL, so only an explicitly-provided one may go in the backdrop slot;
        // otherwise the backdrop waits (shimmering) for TMDB to resolve a real one below.
        val explicitBackdropUrl = intent?.getStringExtra(EXTRA_BACKDROP_URL)?.ifBlank { null }

        fun stopHeroShimmer() {
            heroShimmer.animate().cancel()
            heroShimmer.visibility = View.GONE
        }

        var posterLoaded = false
        val posterIn = {
            if (!posterLoaded) {
                posterLoaded = true
                posterImage.visibility = View.VISIBLE
            }
        }

        // Loads a landscape image into the backdrop slot. animateIn = true plays the backdrop's
        // own entrance (ease down from above + fade) for Case B; false just reveals it because
        // it's the shared element in Case A and the flight already moved it into place.
        fun loadBackdropInto(url: String, animateIn: Boolean) {
            if (url.isBlank()) {
                stopHeroShimmer()
                return
            }
            if (animateIn) backdropImage.alpha = 0f
            SimpleImageLoader.load(url, backdropImage,
                onSuccess = {
                    stopHeroShimmer()
                    if (animateIn) {
                        backdropImage.translationY = -40f
                        backdropImage.animate()
                            .alpha(1f).translationY(0f)
                            .setDuration(350)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    } else {
                        backdropImage.alpha = 1f
                    }
                },
                onError = {
                    stopHeroShimmer()
                    backdropImage.alpha = 1f
                }
            )
        }

        // The poster bitmap is loaded the same way in both cases; only its entrance differs.
        fun loadPoster() {
            if (imageUrl.isNotBlank()) {
                SimpleImageLoader.load(imageUrl, posterImage,
                    onSuccess = { posterIn() },
                    onError = { posterIn() }
                )
            } else {
                posterIn()
            }
        }

        if (isSharedPoster) {
            // CASE B — poster is the shared element. Keep its container at rest so the flight
            // lands cleanly; any pre-set alpha/translation/scale here would fight ChangeBounds
            // and make the flight invisible (the old bug).
            posterImage.alpha = 1f
            posterContainer.alpha = 1f
            posterContainer.translationY = 0f
            posterContainer.scaleX = 1f
            posterContainer.scaleY = 1f
            // The poster URL was just loaded by the source card, so it's already in Coil's
            // memory cache — don't wait on the (always-async, even on a cache hit) load
            // callback to release the transition. Start it after one frame instead, once the
            // bitmap has had a chance to be set synchronously from cache.
            loadPoster()
            window.decorView.post { startPostponedEnterTransitionSafely() }

            // Backdrop plays its own entrance after the flight lands. Hold a shimmer until then.
            backdropImage.alpha = 0f
            heroShimmer.visibility = View.VISIBLE
            shimmerPulse(heroShimmer)
            if (explicitBackdropUrl != null) {
                runAfterEnterTransition {
                    loadBackdropInto(explicitBackdropUrl, animateIn = true)
                }
            }
            // else: no landscape URL yet — the TMDB landscapeUrl load below fades it in.
        } else {
            // CASE A — backdrop is the shared element: visible + loaded immediately so the
            // flight has a bitmap. Same reasoning as above — don't gate the transition start on
            // the async Coil callback; release it after one frame.
            backdropImage.alpha = 1f
            heroShimmer.visibility = View.GONE
            loadBackdropInto(explicitBackdropUrl ?: imageUrl, animateIn = false)
            window.decorView.post { startPostponedEnterTransitionSafely() }

            // Poster springs up from below once the flight has landed. Load it now (hidden by
            // the container's alpha) so the bitmap is ready by the time it animates in.
            posterImage.alpha = 1f
            posterContainer.alpha = 0f
            posterContainer.translationY = 140f
            posterContainer.scaleX = 0.7f
            posterContainer.scaleY = 0.7f
            loadPoster()
            runAfterEnterTransition {
                // A small startDelay keeps the poster spring visibly *after* the hero flight has
                // landed — without it, a cache-fast transition finishes at the same instant and the
                // poster appears to just snap into place instead of springing up.
                posterContainer.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(100)
                    .setDuration(340)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }
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

        // Favorite/Watchlist button — captures TMDB air date once loaded (see below)
        val titleObj = ImdbTitle(
            id = imdbId, title = title, typeLabel = type, year = year,
            cast = null, imageUrl = imageUrl,
            tmdbId = seedTmdbId.takeIf { it > 0 },
        )
        // Rating actually shown on screen, kept in sync with revealFundexRating/revealRating
        // below so a heart-tap saves the same score the user is looking at — previously
        // titleObj never carried a rating at all, so every Watchlist save landed with a blank
        // badge, and any later re-open without EXTRA_FUNDEX_RATING (e.g. from the Watchlist
        // itself) could resolve a real IMDb decimal for what is actually a K-Drama.
        var watchlistRating: Float? = null
        var watchlistRatingText: String? = fundexRatingFromIntent
        var watchlistRatingSourceLabel: String? = if (fundexRatingFromIntent != null) "FUNdex" else null
        // Populated by TMDB callbacks below so the heart click can schedule the notification
        var watchlistMovieReleaseDate: String? = null
        var watchlistNextEpisode: com.cvuong233.cinephantom.data.TMDBNextEpisode? = null

        fun refreshFavIcon() {
            val id = resolvedImdbId ?: imdbId
            favBtn.setImageResource(
                if (FavoritesRepository.isFavorite(id)) R.drawable.ic_heart_filled
                else R.drawable.ic_heart_outline
            )
        }
        refreshFavIcon()
        favBtn.setOnClickListener {
            if (FirebaseAuth.getInstance().currentUser == null) {
                Toast.makeText(this, "Sign in to save to Watchlist", Toast.LENGTH_SHORT).show()
                startActivity(Intent(this, AuthActivity::class.java))
                return@setOnClickListener
            }
            val watchlistId = resolvedImdbId ?: imdbId
            val wasInWatchlist = FavoritesRepository.isFavorite(watchlistId)
            FavoritesRepository.toggle(
                titleObj.copy(
                    id = watchlistId,
                    rating = watchlistRating,
                    ratingText = watchlistRatingText,
                    ratingSourceLabel = watchlistRatingSourceLabel,
                )
            )
            refreshFavIcon()
            if (!wasInWatchlist) {
                // Added — schedule notification if we already have the date
                val airDate = if (isSeries) watchlistNextEpisode?.airDate else watchlistMovieReleaseDate
                if (!airDate.isNullOrBlank()) {
                    WatchlistNotificationScheduler.schedule(
                        context = this,
                        imdbId = watchlistId,
                        title = title,
                        isTV = isSeries,
                        airDate = airDate,
                        season = watchlistNextEpisode?.seasonNumber ?: 0,
                        episode = watchlistNextEpisode?.episodeNumber ?: 0,
                        imageUrl = imageUrl,
                    )
                }
            } else {
                // Removed — cancel any scheduled notification
                WatchlistNotificationScheduler.cancel(this, watchlistId)
            }
        }

        // Stremio button
        stremioBtn.setOnClickListener {
            val stremioId = resolvedImdbId ?: run {
                Toast.makeText(this, "Still loading — try again in a moment", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val stremioType = when (type) {
                "TV Series", "TV Mini Series", "TV Series (mini)", "TV Show", "Series" -> "series"
                "TV Episode" -> "episode"
                else -> "movie"
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("stremio://detail/$stremioType/$stremioId")))
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
                watchlistNextEpisode = nextEp  // capture for notification scheduling
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

        val providersContainer = findViewById<LinearLayout>(R.id.detail_providers_container)

        // Provider buttons only ever get built from the real per-title TMDB response
        // (applyProvidersToUi) — no optimistic pre-population from the cached global list, since
        // that list isn't necessarily where THIS title is available and caused wrong/extra icons
        // to flash before the real fetch resolved.
        val providerButtons = mutableMapOf<Int, ImageView>()

        fun openJustWatchSearchFallback() {
            // Used until (or unless) a real per-platform deep link resolves — searches JustWatch for
            // this title so the tap still does something useful.
            val query = java.net.URLEncoder.encode(title, "UTF-8")
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.justwatch.com/us/search?q=$query")))
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't open JustWatch", Toast.LENGTH_SHORT).show()
            }
        }

        fun openNetflixSearchFallback() {
            // Netflix-specific fallback — a generic JustWatch search isn't useful for a title we
            // already know is on Netflix but couldn't resolve an exact /title/{id} deep link for.
            val query = java.net.URLEncoder.encode(title, "UTF-8")
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.netflix.com/search?q=$query")))
            } catch (_: Exception) {
                Toast.makeText(this, "Couldn't open Netflix", Toast.LENGTH_SHORT).show()
            }
        }

        fun addProviderButton(provider: TMDBWatchProvider, onTap: () -> Unit): ImageView {
            val item = layoutInflater.inflate(R.layout.item_provider_button, providersContainer, false) as ImageView
            if (provider.id == WatchProviderPreferences.NETFLIX_PROVIDER_ID && provider.logoPath == null) {
                item.setImageResource(R.drawable.ic_netflix_brand)
            } else {
                provider.logoUrl?.let { url -> SimpleImageLoader.load(url = url, imageView = item) }
            }
            item.contentDescription = provider.name
            item.setOnClickListener { onTap() }
            providerButtons[provider.id] = item
            providersContainer.addView(item)
            return item
        }

        // Fades + slides the whole streaming row (Stremio + provider buttons) into place, and gives
        // Stremio its overshoot pop, all timed to land just after the hero companion animation.
        fun animateStreamingRowIn() {
            streamingScroll.visibility = View.VISIBLE
            streamingScroll.alpha = 0f
            streamingScroll.translationY = 30f * resources.displayMetrics.density
            streamingScroll.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setStartDelay(100)
                .setInterpolator(DecelerateInterpolator())
                .start()
            if (stremioBtn.visibility == View.VISIBLE) {
                stremioBtn.scaleX = 0f; stremioBtn.scaleY = 0f
                stremioBtn.animate().scaleX(1f).scaleY(1f)
                    .setStartDelay(160).setDuration(360)
                    .setInterpolator(OvershootInterpolator(1.3f)).start()
            }
        }

        fun applyProvidersToUi(resolvedProviders: List<Pair<TMDBWatchProvider, String?>>) {
            if (resolvedProviders.isEmpty()) {
                // Only hide the whole row if Stremio isn't showing either — otherwise the
                // row still needs to display the Stremio button on its own.
                if (stremioBtn.visibility != View.VISIBLE) streamingScroll.visibility = View.GONE
                return
            }

            streamingScroll.visibility = View.VISIBLE
            providersContainer.alpha = 0f
            providersContainer.translationX = 24f
            providersContainer.removeAllViews()
            providerButtons.clear()
            for ((provider, platformUrl) in resolvedProviders) {
                addProviderButton(provider) {
                    when {
                        platformUrl != null -> openWatchProvider(platformUrl)
                        provider.id == WatchProviderPreferences.NETFLIX_PROVIDER_ID -> openNetflixSearchFallback()
                        else -> openJustWatchSearchFallback()
                    }
                }
            }
            providersContainer.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(260)
                .setStartDelay(50)
                .setInterpolator(DecelerateInterpolator())
                .start()
            animateHorizontalItems(providersContainer, 70)
        }

        // The Stremio button (not per-title data) still enters with the hero choreography;
        // provider buttons only appear once applyProvidersToUi resolves the real per-title fetch.
        runAfterEnterTransition { animateStreamingRowIn() }

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
                    val transName = "poster_$imdbIdForRec"
                    ViewCompat.setTransitionName(poster, transName)
                    val intent = Intent(this@DetailActivity, DetailActivity::class.java).apply {
                        putExtra(EXTRA_IMDB_ID, imdbIdForRec)
                        putExtra(EXTRA_TITLE, rec.title)
                        putExtra(EXTRA_IMAGE_URL, posterUrl)
                        putExtra(EXTRA_YEAR, yearText)
                        putExtra(EXTRA_TYPE, typeLabel)
                        putExtra(EXTRA_TMDB_ID, rec.id)
                        // Recommendation cards are vertical posters, not landscape backdrops —
                        // land the shared-element transition on the poster, not the backdrop.
                        putExtra(EXTRA_TRANSITION_TARGET, TRANSITION_TARGET_POSTER)
                        putExtra(EXTRA_TRANSITION_NAME, transName)
                    }
                    val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                        this@DetailActivity, poster, transName
                    )
                    startActivity(intent, options.toBundle())
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

                val watchProviders = tmdbApi.fetchWatchProviders(tmdbId, isSeries)
                // TMDB's per-title watch/providers endpoint reliably includes major platforms
                // (Netflix, Disney+, Apple TV+...) that the bulk catalog endpoint powering the
                // Settings list sometimes omits. Feed every provider seen on a title back into
                // the shared cache so Settings accumulates the real catalog over time.
                if (watchProviders != null) watchProviderPrefs.mergeProviders(watchProviders.flatrate)
                val justWatchLink = watchProviders?.justWatchLink
                val resolvedPlatformUrls = if (!justWatchLink.isNullOrBlank())
                    JustWatchLinkResolver.resolvePlatformUrls(justWatchLink)
                else emptyMap()
                // A provider TMDB confirmed for this title still gets a button even when the
                // JustWatch deep link fails to resolve (no match, resolver error, etc.) — the
                // resolved link is a bonus tap target, not a requirement for showing the button.
                val baseProviders = watchProviders?.flatrate.orEmpty()
                    .filter { watchProviderPrefs.isEnabled(it.id, it.name) }
                    .map { provider ->
                        val url = resolvedPlatformUrls.entries
                            .firstOrNull { (key, _) -> provider.name.contains(key, ignoreCase = true) }
                            ?.value
                        provider to url
                    }
                // TMDB's watch-provider data (sourced from JustWatch) has dropped Netflix almost
                // everywhere, so flatrate above essentially never has a real Netflix entry — even
                // for titles that ARE on Netflix. Relying on the JustWatch HTML resolver to find a
                // netflix.com link was unreliable (it depends on JustWatch's page still listing
                // Netflix and the scrape pattern matching). Instead: if the user has Netflix enabled,
                // always show the button — use TMDB's own confirmation (provider 8 present in
                // flatrate) plus the per-title JustWatch link when we have it, and otherwise fall
                // back to a Netflix title search deep link (see openNetflixSearchFallback) so the
                // button always does something useful.
                val hasNetflixAlready = baseProviders.any { it.first.id == WatchProviderPreferences.NETFLIX_PROVIDER_ID }
                val netflixConfirmedByTmdb = watchProviders?.flatrate.orEmpty()
                    .any { it.id == WatchProviderPreferences.NETFLIX_PROVIDER_ID }
                val netflixUrl = resolvedPlatformUrls["netflix"]
                    ?: justWatchLink?.takeIf { netflixConfirmedByTmdb }
                val resolvedProviders = if (!hasNetflixAlready &&
                    watchProviderPrefs.isEnabled(WatchProviderPreferences.NETFLIX_PROVIDER_ID, "Netflix")) {
                    baseProviders + (TMDBWatchProvider(WatchProviderPreferences.NETFLIX_PROVIDER_ID, "Netflix", null) to netflixUrl)
                } else {
                    baseProviders
                }
                runOnUiThread { applyProvidersToUi(resolvedProviders) }

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
                val tmdbApi = TMDBApi()
                val details = if (isTmdbOnly) {
                    // Opened from Search — load directly via TMDB ID, no /find/ round-trip needed.
                    val d = tmdbApi.fetchTitleDetailsByTmdbId(seedTmdbId, isSeries)
                    if (d != null && d.tmdbId > 0) {
                        // Resolve real IMDb ID in background so Stremio / share work once ready.
                        val realId = tmdbApi.fetchImdbIdForTitle(d.tmdbId, if (isSeries) "tv" else "movie")
                        if (!realId.isNullOrBlank()) resolvedImdbId = realId
                        if (tmdbTrailerId <= 0) tmdbTrailerId = d.tmdbId
                    }
                    d
                } else {
                    tmdbApi.fetchTitleDetailsByImdb(imdbId, preferSeries = isSeries)
                        ?: if (seedTmdbId > 0) tmdbApi.fetchTitleDetailsByTmdbId(seedTmdbId, isSeries) else null
                }
                val effectiveFundexRating = fundexRatingFromIntent
                    ?: WidgetDataFetcher.findKdramaSeed(this@DetailActivity, imdbId)
                        ?.ratingText?.takeIf { it.isNotBlank() }
                // Pre-fetch chart rating on background thread so main thread stays clear of network
                val chartRating = if (effectiveFundexRating == null)
                    ratingFetcher.fetchCachedOrChartRating(imdbId)
                else null

                runOnUiThread {
                    // Capture release date for movie watchlist notification scheduling
                    if (!isSeries) watchlistMovieReleaseDate = details?.releaseDate

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
                    // If the source card already gave us a backdrop, keep it — TMDB's own
                    // backdrop_path can point to a different image than the chart feed used,
                    // which caused a visible mismatch/swap right after the detail API loaded.
                    if (landscapeUrl.isNotBlank() && explicitBackdropUrl == null) {
                        val loadHigherResBackdrop: () -> Unit = {
                            SimpleImageLoader.load(landscapeUrl, backdropImage,
                                onSuccess = {
                                    stopHeroShimmer()
                                    backdropImage.animate().alpha(1f).setDuration(350).start()
                                },
                                onError = {
                                    if (imageUrl.isNotBlank()) {
                                        SimpleImageLoader.load(imageUrl, backdropImage,
                                            onSuccess = { stopHeroShimmer(); backdropImage.animate().alpha(1f).setDuration(350).start() },
                                            onError = { stopHeroShimmer(); backdropImage.alpha = 1f }
                                        )
                                    } else {
                                        stopHeroShimmer()
                                        backdropImage.alpha = 1f
                                    }
                                }
                            )
                        }
                        // Same rule as the initial backdrop load above: if the poster is the
                        // shared element, don't swap the backdrop until the flight animation
                        // has landed, or this higher-res image causes the exact same flash.
                        if (isSharedPoster) runAfterEnterTransition(loadHigherResBackdrop) else loadHigherResBackdrop()
                    }

                    // Poster extra missing (e.g. an older entry point) — fall back to TMDB's poster.
                    if (imageUrl.isBlank() && !posterPath.isNullOrBlank()) {
                        SimpleImageLoader.load(
                            "https://image.tmdb.org/t/p/w342$posterPath", posterImage,
                            onSuccess = { posterIn() },
                            onError = { posterIn() }
                        )
                    } else {
                        posterIn()
                    }

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
                        watchlistRating = null
                        watchlistRatingText = effectiveFundexRating
                        watchlistRatingSourceLabel = "FUNdex"
                    } else {
                        val preferredRating = preloadedRating.get() ?: chartRating ?: details?.rating
                        revealRating(preferredRating, 180)
                        if (preferredRating != null && preferredRating > 0f) {
                            watchlistRating = preferredRating
                            watchlistRatingText = String.format(java.util.Locale.US, "%.1f", preferredRating)
                            watchlistRatingSourceLabel = "IMDb"
                        }
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

                    val description = details?.overview.orEmpty()
                    if (description.isNotBlank()) {
                        aboutLabel.visibility = View.VISIBLE
                        aboutLabel.translationY = 25f; aboutLabel.alpha = 0f
                        aboutLabel.animate().translationY(0f).alpha(1f).setDuration(300).setStartDelay(480)
                            .setInterpolator(DecelerateInterpolator()).start()
                        descView.text = description
                        descView.visibility = View.VISIBLE
                        descView.translationY = 30f; descView.alpha = 0f
                        descView.animate().translationY(0f).alpha(1f).setDuration(400).setStartDelay(510)
                            .setInterpolator(DecelerateInterpolator()).start()
                    }

                    titleRow.visibility = View.VISIBLE
                    titleView.translationY = -60f; titleView.alpha = 0f
                    titleView.animate().translationY(0f).alpha(1f).setDuration(450).setStartDelay(50)
                        .setInterpolator(DecelerateInterpolator()).start()
                    // (Stremio + the rest of the streaming row now animate in with the entrance
                    // choreography — see animateStreamingRowIn — instead of waiting for TMDB.)
                }

                details?.tmdbId?.takeIf { it > 0 }?.let { fetchCreditsAndUpdatePhotos(it) }

            } catch (_: Exception) {
                runOnUiThread {
                    // No TMDB data — if the backdrop is still shimmering (Case B with no explicit
                    // backdrop URL), retire it once the flight lands so it doesn't pulse forever.
                    runAfterEnterTransition { stopHeroShimmer() }
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

    // Opens the real per-title platform URL resolved from JustWatch (see
    // JustWatchLinkResolver). App not installed just shows a toast — no browser fallback.
    private fun openWatchProvider(platformUrl: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(platformUrl)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "App not installed", Toast.LENGTH_SHORT).show()
        }
    }
}
