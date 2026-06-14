package com.cvuong233.cinephantom.ui.discover

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.KDramaChartsApi
import com.cvuong233.cinephantom.data.RatingFetcher
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DiscoverFragment : Fragment() {

    companion object {
        private const val ARG_INITIAL_IMDB_ID = "arg_initial_imdb_id"
        private const val ARG_INITIAL_TYPE = "arg_initial_type"

        val INSTANCE = DiscoverFragmentFactory()

        class DiscoverFragmentFactory {
            fun newInstance(imdbId: String? = null, type: String? = null): DiscoverFragment {
                return DiscoverFragment().apply {
                    arguments = Bundle().apply {
                        putString(ARG_INITIAL_IMDB_ID, imdbId)
                        putString(ARG_INITIAL_TYPE, type)
                    }
                }
            }
        }

        private val TAB_ORDER = listOf("imdb_movies", "imdb_tv", "kdrama")
    }

    private val imdbClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val kdramaApi = KDramaChartsApi()

    // "imdb_movies" | "imdb_tv" | "kdrama"
    private var currentTab = "imdb_movies"
    private var lastTab = "imdb_movies"

    // IMDb data
    private var allMovies = listOf<ChartItem>()
    private var allTv = listOf<ChartItem>()
    private var imdbLoaded = false
    private var imdbUpdatedLabel: String? = null

    // K-Drama data
    private var allKDramas = listOf<ImdbTitle>()
    private var kdramaLoaded = false
    private var kdramaUpdatedLabel: String? = null

    private val ratingFetcher = RatingFetcher()
    private val inFlightRatings = ConcurrentHashMap.newKeySet<String>()
    private lateinit var adapter: DiscoverResultsAdapter
    private var recyclerView: RecyclerView? = null
    private var pendingFocusImdbId: String? = null
    private var pendingFocusType: String? = null
    private var hasAnimatedFirstTabSwap = false
    private val titleHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialImdbId = arguments?.getString(ARG_INITIAL_IMDB_ID)
        val initialType = arguments?.getString(ARG_INITIAL_TYPE)
        if (!initialImdbId.isNullOrBlank()) {
            pendingFocusImdbId = initialImdbId
            pendingFocusType = initialType
            currentTab = if (initialType.equals("series", ignoreCase = true) ||
                initialType.equals("tv", ignoreCase = true)) "imdb_tv" else "imdb_movies"
            lastTab = currentTab
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_discover, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler = view.findViewById<RecyclerView>(R.id.discover_recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recyclerView = recycler
        recycler.itemAnimator = null

        adapter = DiscoverResultsAdapter(
            skeletonLayoutRes = R.layout.item_discover_skeleton,
            showRankLabel = true,
            showFeaturedMetricLabel = false,
            onClick = { posterView, title -> openTitle(posterView, title) }
        )
        adapter.onStremioClick = { openInStremio(it) }
        adapter.onRatingNeeded = { title -> loadVisibleRating(title) }
        recycler.adapter = adapter

        view.findViewById<TextView>(R.id.tab_imdb_movies).setOnClickListener {
            if (currentTab != "imdb_movies") setTab("imdb_movies")
        }
        view.findViewById<TextView>(R.id.tab_imdb_tv).setOnClickListener {
            if (currentTab != "imdb_tv") setTab("imdb_tv")
        }
        view.findViewById<TextView>(R.id.tab_kdrama).setOnClickListener {
            if (currentTab != "kdrama") setTab("kdrama")
        }

        applyTabUi(view, currentTab, animate = false)
        updateHeader(view, animate = false)

        val isLoaded = if (currentTab == "kdrama") kdramaLoaded else imdbLoaded
        if (isLoaded) showContent()
        else if (currentTab == "kdrama") loadKdramaCharts()
        else loadImdbCharts()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        titleHandler.removeCallbacksAndMessages(null)
        recyclerView = null
    }

    // Called from MainActivity when a deep-link or widget targets a specific IMDb title.
    fun focusOnTitle(imdbId: String, type: String?) {
        pendingFocusImdbId = imdbId
        pendingFocusType = type
        val targetTab = if (type.equals("series", ignoreCase = true) ||
            type.equals("tv", ignoreCase = true)) "imdb_tv" else "imdb_movies"
        if (currentTab != targetTab) {
            lastTab = currentTab
            currentTab = targetTab
            view?.let { v ->
                applyTabUi(v, targetTab, animate = false)
                updateHeader(v, animate = false)
            }
        }
        if (imdbLoaded) showContent() else loadImdbCharts()
    }

    // ── Tab switching ────────────────────────────────────────────────────────

    private fun setTab(tab: String) {
        lastTab = currentTab
        currentTab = tab
        val v = view ?: return

        applyTabUi(v, tab, animate = true)
        updateHeader(v, animate = true)

        adapter.onRatingNeeded = if (tab == "kdrama") null
            else { title -> loadVisibleRating(title) }

        val isLoaded = if (tab == "kdrama") kdramaLoaded else imdbLoaded
        if (isLoaded) {
            showContent()
        } else {
            if (tab == "kdrama") loadKdramaCharts() else loadImdbCharts()
        }
    }

    private fun applyTabUi(view: View, selectedTab: String, animate: Boolean) {
        val tabViews = mapOf(
            "imdb_movies" to view.findViewById<TextView>(R.id.tab_imdb_movies),
            "imdb_tv"     to view.findViewById<TextView>(R.id.tab_imdb_tv),
            "kdrama"      to view.findViewById<TextView>(R.id.tab_kdrama),
        )
        tabViews.forEach { (tab, tv) ->
            tv ?: return@forEach
            val selected = tab == selectedTab
            tv.animate().cancel()
            tv.setBackgroundResource(
                if (selected) R.drawable.bg_discover_tab_selected
                else R.drawable.bg_discover_tab_unselected
            )
            tv.setTextColor(
                if (selected) 0xFFFFFFFF.toInt()
                else resources.getColor(R.color.text_muted, null)
            )
            if (animate) {
                if (selected) {
                    tv.scaleX = 0.9f; tv.scaleY = 0.9f
                    tv.alpha = 0.78f; tv.translationY = 4f
                    tv.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
                        .setDuration(220).start()
                } else {
                    tv.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.88f).translationY(2f)
                        .setDuration(150)
                        .withEndAction {
                            tv.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f)
                                .setDuration(120).start()
                        }.start()
                }
            }
        }
    }

    // ── Header (subtitle / timestamp only) ──────────────────────────────────

    private fun tabSubtitle(tab: String): String? = when (tab) {
        "imdb_movies", "imdb_tv" -> imdbUpdatedLabel?.let { "Updated $it" }
        "kdrama"                  -> kdramaUpdatedLabel?.let { "Updated $it" }
        else                      -> null
    }

    private fun updateHeader(view: View, animate: Boolean) {
        val subtitleView = view.findViewById<TextView>(R.id.discover_tab_subtitle) ?: return
        val newSubtitle  = tabSubtitle(currentTab)
        if (newSubtitle == null) {
            subtitleView.visibility = View.GONE
            return
        }
        val changed = newSubtitle != subtitleView.text.toString()
        if (animate && changed) {
            typewriterAnimate(subtitleView, newSubtitle)
        } else {
            subtitleView.text = newSubtitle
            subtitleView.visibility = View.VISIBLE
        }
    }

    private fun typewriterAnimate(view: TextView, newText: String) {
        view.tag = newText
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(90)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (!isAdded || view.tag != newText) return@withEndAction
                view.text = ""
                view.alpha = 1f
                view.visibility = View.VISIBLE
                for (i in newText.indices) {
                    titleHandler.postDelayed({
                        if (isAdded && view.tag == newText)
                            view.text = newText.substring(0, i + 1)
                    }, i * 22L)
                }
            }.start()
    }

    // ── Data loading ─────────────────────────────────────────────────────────

    private fun loadImdbCharts() {
        val v = view ?: return
        adapter.showLoading()
        updateContentState()

        Thread {
            try {
                val req = Request.Builder()
                    .url("https://cvuong233.github.io/agent-presentation/imdb_charts.json")
                    .header("User-Agent", "CinePhantom/1.0")
                    .build()
                val resp = imdbClient.newCall(req).execute()
                val body = resp.body?.string() ?: throw Exception("Empty response")
                val root = JSONObject(body)

                val updated = root.optString("updated").trim().ifBlank { null }
                val movies  = parseImdbItems(root, "movies")
                val tvShows = parseImdbItems(root, "tv")

                activity?.runOnUiThread {
                    allMovies = movies
                    allTv = tvShows
                    imdbUpdatedLabel = formatUpdatedLabel(updated)
                    imdbLoaded = true
                    if (currentTab == "imdb_movies" || currentTab == "imdb_tv") {
                        updateHeader(v, animate = false)
                        showContent()
                    }
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    if (currentTab == "imdb_movies" || currentTab == "imdb_tv") {
                        adapter.hideLoading()
                        updateContentState(showError = !imdbLoaded)
                    }
                }
            }
        }.start()
    }

    private fun loadKdramaCharts() {
        val v = view ?: return
        adapter.showLoading()
        updateContentState()

        Thread {
            val result = kdramaApi.fetchTopKDramas()
            activity?.runOnUiThread {
                if (result.isSuccess) {
                    val (updated, _, list) = result.getOrThrow()
                    kdramaUpdatedLabel = formatUpdatedLabel(updated)
                    kdramaLoaded = true
                    allKDramas = list
                    if (currentTab == "kdrama") {
                        updateHeader(v, animate = false)
                        showContent()
                    }
                } else {
                    if (currentTab == "kdrama") {
                        adapter.hideLoading()
                        updateContentState(showError = !kdramaLoaded)
                    }
                }
            }
        }.start()
    }

    // ── Content display ──────────────────────────────────────────────────────

    private fun showContent() {
        val titles: List<ImdbTitle> = when (currentTab) {
            "imdb_movies" -> allMovies.map { it.toImdbTitle() }
            "imdb_tv"     -> allTv.map { it.toImdbTitle() }
            "kdrama"      -> allKDramas
            else          -> emptyList()
        }
        val recycler = view?.findViewById<RecyclerView>(R.id.discover_recycler)
        val hasPendingFocus = pendingFocusImdbId != null
        inFlightRatings.clear()
        recycler?.animate()?.cancel()

        val fromIdx = TAB_ORDER.indexOf(lastTab)
        val toIdx   = TAB_ORDER.indexOf(currentTab)
        val movingForward = toIdx >= fromIdx
        val shouldAnimate = !hasPendingFocus && lastTab != currentTab

        if (hasPendingFocus || !shouldAnimate) {
            recycler?.alpha = 1f; recycler?.translationX = 0f
            recycler?.scaleX = 1f; recycler?.scaleY = 1f
        } else {
            recycler?.alpha = 0f
            recycler?.translationX = if (movingForward) 34f else -34f
            recycler?.scaleX = 0.992f; recycler?.scaleY = 0.992f
        }

        adapter.hideLoading()
        adapter.submitList(titles)
        val isLoaded = if (currentTab == "kdrama") kdramaLoaded else imdbLoaded
        updateContentState(showError = titles.isEmpty() && isLoaded)

        if (hasPendingFocus) {
            recycler?.post { applyPendingFocus() }
        } else if (shouldAnimate) {
            recycler?.animate()
                ?.alpha(1f)?.translationX(0f)?.scaleX(1f)?.scaleY(1f)
                ?.setDuration(if (hasAnimatedFirstTabSwap) 180 else 220)
                ?.start()
            hasAnimatedFirstTabSwap = true
        }
    }

    private fun updateContentState(showError: Boolean = false) {
        val v = view ?: return
        val recycler = v.findViewById<RecyclerView>(R.id.discover_recycler)
        val error    = v.findViewById<TextView>(R.id.discover_error)
        if (showError) {
            error.text = if (currentTab == "kdrama") getString(R.string.kdrama_error)
                         else getString(R.string.discover_error)
            error.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            error.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    // ── Rating fetch (IMDb tabs only) ────────────────────────────────────────

    private fun loadVisibleRating(title: ImdbTitle) {
        if (!inFlightRatings.add(title.id)) return
        thread {
            try {
                val rating = ratingFetcher.fetchRating(title.id)
                if (rating != null && rating > 0f) {
                    activity?.runOnUiThread {
                        adapter.updateRating(title.copy(
                            rating = rating,
                            ratingText = String.format(Locale.US, "%.1f", rating),
                            ratingSourceLabel = "IMDb"
                        ))
                    }
                }
            } finally {
                inFlightRatings.remove(title.id)
            }
        }
    }

    // ── Deep-link scroll focus ────────────────────────────────────────────────

    private fun applyPendingFocus() {
        val imdbId = pendingFocusImdbId ?: return
        if (currentTab == "kdrama") return
        val items = if (currentTab == "imdb_movies") allMovies else allTv
        val expectedTab = if (pendingFocusType.equals("series", ignoreCase = true) ||
            pendingFocusType.equals("tv", ignoreCase = true)) "imdb_tv" else "imdb_movies"
        if (currentTab != expectedTab) return
        val position = items.indexOfFirst { it.imdbId == imdbId }
        if (position < 0) return

        pendingFocusImdbId = null
        pendingFocusType = null

        val recycler = recyclerView ?: return
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        val desiredTop = 120
        recycler.post {
            lm.scrollToPositionWithOffset(position, desiredTop)
            adapter.requestHighlight(imdbId, position)
            recycler.post {
                val delta = (lm.findViewByPosition(position)?.top ?: desiredTop) - desiredTop
                if (kotlin.math.abs(delta) > 2) recycler.scrollBy(0, delta)
            }
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun openTitle(posterView: View, title: ImdbTitle) {
        val intent = Intent(requireContext(), DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, title.id)
            putExtra(DetailActivity.EXTRA_TITLE, title.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, title.imageUrl)
            putExtra(DetailActivity.EXTRA_CAST, title.cast)
            putExtra(DetailActivity.EXTRA_YEAR, title.year)
            putExtra(DetailActivity.EXTRA_TYPE, title.typeLabel)
            title.tmdbId?.takeIf { it > 0 }?.let { putExtra(DetailActivity.EXTRA_TMDB_ID, it) }
            // Pass FUNdex rating for K-Drama items so DetailActivity displays it
            if (title.ratingSourceLabel == "FUNdex") {
                (title.ratingText ?: title.featuredMetricLabel)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { putExtra(DetailActivity.EXTRA_FUNDEX_RATING, it) }
            }
        }
        ViewCompat.setTransitionName(posterView, "poster_${title.id}")
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            requireActivity(), posterView, "poster_${title.id}"
        )
        startActivity(intent, options.toBundle())
    }

    private fun openInStremio(title: ImdbTitle) {
        val stremioType = when (title.typeLabel) {
            "TV Series", "TV Mini Series", "TV Series (mini)" -> "series"
            "TV Episode" -> "episode"
            else -> "movie"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("stremio://detail/$stremioType/${title.id}")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.stremio_not_installed, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatUpdatedLabel(rawValue: String?): String? {
        val value = rawValue?.trim().orEmpty()
        if (value.isBlank()) return null
        return try {
            val instant = Instant.parse(value)
            val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)
                .withZone(ZoneId.systemDefault())
            formatter.format(instant)
        } catch (_: Exception) { value }
    }

    private fun parseImdbItems(root: JSONObject, key: String): List<ChartItem> {
        val arr = root.optJSONArray(key) ?: return emptyList()
        val items = mutableListOf<ChartItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            items.add(ChartItem(
                imdbId = obj.optString("imdb_id", ""),
                title  = obj.optString("title", ""),
                rank   = obj.optInt("rank", i + 1),
                rating = obj.optString("rating", ""),
                votes  = obj.optString("votes", ""),
                poster = obj.optString("poster", ""),
                type   = key
            ))
        }
        return items
    }

    private data class ChartItem(
        val imdbId: String,
        val title: String,
        val rank: Int,
        val rating: String,
        val votes: String,
        val poster: String,
        val type: String
    ) {
        fun toImdbTitle(): ImdbTitle {
            val typeLabel = if (type == "tv") "TV Series" else "Movie"
            return ImdbTitle(
                id = imdbId,
                title = title,
                typeLabel = typeLabel,
                year = null,
                cast = votes.ifBlank { null },
                imageUrl = poster.ifBlank { "https://images.metahub.space/poster/small/$imdbId/img" },
                rating = rating.toFloatOrNull(),
                ratingText = rating.ifBlank { null },
                ratingSourceLabel = "IMDb",
                rankLabel = "#$rank",
            )
        }
    }
}
