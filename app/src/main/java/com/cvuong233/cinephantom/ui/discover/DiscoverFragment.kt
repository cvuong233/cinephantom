package com.cvuong233.cinephantom.ui.discover

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.FavoritesRepository
import com.cvuong233.cinephantom.data.RatingFetcher
import com.cvuong233.cinephantom.notifications.WishlistNotificationScheduler
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.account.AuthActivity
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.google.firebase.auth.FirebaseAuth
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
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var currentFilter: String = "movies"
    private var allMovies = listOf<ChartItem>()
    private var allTv = listOf<ChartItem>()
    private var isLoaded = false
    private val ratingFetcher = RatingFetcher()
    private val inFlightRatings = ConcurrentHashMap.newKeySet<String>()
    private lateinit var adapter: DiscoverResultsAdapter
    private var recyclerView: RecyclerView? = null
    private var pendingFocusImdbId: String? = null
    private var pendingFocusType: String? = null
    private var lastFilter: String = "movies"
    private var hasAnimatedFirstFilterSwap = false
    private var lastUpdatedLabel: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialImdbId = arguments?.getString(ARG_INITIAL_IMDB_ID)
        val initialType = arguments?.getString(ARG_INITIAL_TYPE)
        if (!initialImdbId.isNullOrBlank()) {
            pendingFocusImdbId = initialImdbId
            pendingFocusType = initialType
            currentFilter = if (initialType.equals("series", ignoreCase = true) || initialType.equals("tv", ignoreCase = true)) "tv" else "movies"
            lastFilter = currentFilter
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_discover, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filterMovies = view.findViewById<TextView>(R.id.discover_filter_movies)
        val filterTv = view.findViewById<TextView>(R.id.discover_filter_tv)
        val recycler = view.findViewById<RecyclerView>(R.id.discover_recycler)

        updateTimestamp(view)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recyclerView = recycler
        recycler.itemAnimator = null

        adapter = DiscoverResultsAdapter(
            skeletonLayoutRes = R.layout.item_discover_skeleton,
            showRankLabel = true,
            showFeaturedMetricLabel = false,
            onClick = { posterView, title -> openImdbTitle(posterView, title) }
        )
        adapter.onStremioClick = { openInStremio(it) }
        adapter.onFavoriteClick = { toggleFavorite(it, adapter) }
        adapter.onRatingNeeded = { title -> loadVisibleRating(title) }
        recycler.adapter = adapter

        filterMovies.setOnClickListener { if (currentFilter != "movies") setFilter("movies") }
        filterTv.setOnClickListener { if (currentFilter != "tv") setFilter("tv") }

        setFilter(currentFilter)
        if (!isLoaded) loadCharts() else showContent()
    }

    fun focusOnTitle(imdbId: String, type: String?) {
        pendingFocusImdbId = imdbId
        pendingFocusType = type
        val targetFilter = if (type.equals("series", ignoreCase = true) || type.equals("tv", ignoreCase = true)) "tv" else "movies"
        if (currentFilter != targetFilter) {
            setFilter(targetFilter)
        } else if (isLoaded) {
            showContent()
        } else {
            loadCharts()
        }
    }

    private fun openImdbTitle(posterView: View, title: ImdbTitle) {
        val intent = Intent(requireContext(), DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, title.id)
            putExtra(DetailActivity.EXTRA_TITLE, title.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, title.imageUrl)
            putExtra(DetailActivity.EXTRA_CAST, title.cast)
            putExtra(DetailActivity.EXTRA_YEAR, title.year)
            putExtra(DetailActivity.EXTRA_TYPE, title.typeLabel)
        }
        ViewCompat.setTransitionName(posterView, "poster_${title.id}")
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            requireActivity(), posterView, "poster_${title.id}"
        )
        startActivity(intent, options.toBundle())
    }

    private fun toggleFavorite(title: ImdbTitle, adapter: DiscoverResultsAdapter) {
        if (FirebaseAuth.getInstance().currentUser == null) {
            Toast.makeText(requireContext(), "Sign in to save to Wishlist", Toast.LENGTH_SHORT).show()
            startActivity(Intent(requireContext(), AuthActivity::class.java))
            return
        }
        val wasInWishlist = FavoritesRepository.isFavorite(title.id)
        FavoritesRepository.toggle(title)
        adapter.notifyFavoriteChanged(title.id)
        if (wasInWishlist) WishlistNotificationScheduler.cancel(requireContext(), title.id)
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

    private fun setFilter(type: String) {
        val previousFilter = currentFilter
        currentFilter = type
        val view = view ?: return
        val filterMovies = view.findViewById<TextView>(R.id.discover_filter_movies)
        val filterTv = view.findViewById<TextView>(R.id.discover_filter_tv)
        val selected = if (type == "movies") filterMovies else filterTv
        val unselected = if (type == "movies") filterTv else filterMovies

        selected.animate().cancel()
        unselected.animate().cancel()

        selected.setBackgroundResource(R.drawable.bg_discover_tab_selected)
        selected.setTextColor(0xFFFFFFFF.toInt())
        unselected.setBackgroundResource(R.drawable.bg_discover_tab_unselected)
        unselected.setTextColor(resources.getColor(R.color.text_muted, null))

        selected.scaleX = 0.9f
        selected.scaleY = 0.9f
        selected.alpha = 0.78f
        selected.translationY = 4f
        selected.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(220).start()

        unselected.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.88f).translationY(2f).setDuration(150)
            .withEndAction { unselected.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(120).start() }
            .start()

        lastFilter = previousFilter
        showContent()
    }

    private fun loadCharts(forceRefresh: Boolean = false) {
        val view = view ?: return
        val error = view.findViewById<TextView>(R.id.discover_error)
        error.visibility = View.GONE
        if (!forceRefresh && !isLoaded) {
            adapter.showLoading()
            updateContentState()
        }

        Thread {
            try {
                val req = Request.Builder()
                    .url("https://cvuong233.github.io/agent-presentation/imdb_charts.json")
                    .header("User-Agent", "CinePhantom/1.0")
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: throw Exception("Empty response")
                val root = JSONObject(body)

                val updated = root.optString("updated").trim().ifBlank { null }
                val movies = parseItems(root, "movies")
                val tvShows = parseItems(root, "tv")

                activity?.runOnUiThread {
                    allMovies = movies
                    allTv = tvShows
                    lastUpdatedLabel = formatUpdatedLabel(updated)
                    isLoaded = true
                    view.let { updateTimestamp(it) }
                    showContent()
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    adapter.hideLoading()
                    updateContentState(showError = !isLoaded)
                }
            }
        }.start()
    }

    private fun showContent() {
        val items = if (currentFilter == "movies") allMovies else allTv
        val titles = items.map { it.toImdbTitle() }
        val recycler = view?.findViewById<RecyclerView>(R.id.discover_recycler)
        val hasPendingFocus = pendingFocusImdbId != null
        inFlightRatings.clear()
        recycler?.animate()?.cancel()

        val movingToTv = currentFilter == "tv"
        val movingForward = lastFilter != currentFilter && movingToTv
        val shouldAnimateSwap = !hasPendingFocus && lastFilter != currentFilter && isLoaded
        if (hasPendingFocus || !shouldAnimateSwap) {
            recycler?.alpha = 1f
            recycler?.translationX = 0f
            recycler?.translationY = 0f
            recycler?.scaleX = 1f
            recycler?.scaleY = 1f
        } else {
            recycler?.alpha = 0f
            recycler?.translationX = if (movingForward) 34f else -34f
            recycler?.translationY = 0f
            recycler?.scaleX = 0.992f
            recycler?.scaleY = 0.992f
        }

        adapter.hideLoading()
        adapter.submitList(titles)
        updateContentState(showError = items.isEmpty() && isLoaded)

        if (hasPendingFocus) {
            recycler?.post { applyPendingFocus(items) }
        } else if (shouldAnimateSwap) {
            recycler?.animate()?.alpha(1f)?.translationX(0f)?.translationY(0f)?.scaleX(1f)?.scaleY(1f)
                ?.setDuration(if (hasAnimatedFirstFilterSwap) 180 else 220)?.start()
            hasAnimatedFirstFilterSwap = true
        }
    }

    private fun loadVisibleRating(title: ImdbTitle) {
        if (!inFlightRatings.add(title.id)) return
        thread {
            try {
                val rating = ratingFetcher.fetchRating(title.id)
                if (rating != null && rating > 0f) {
                    activity?.runOnUiThread {
                        adapter.updateRating(
                            title.copy(
                                rating = rating,
                                ratingText = String.format(Locale.US, "%.1f", rating),
                                ratingSourceLabel = "IMDb"
                            )
                        )
                    }
                }
            } finally {
                inFlightRatings.remove(title.id)
            }
        }
    }

    private fun applyPendingFocus(items: List<ChartItem>) {
        val imdbId = pendingFocusImdbId ?: return
        val expectedFilter = if (pendingFocusType.equals("series", ignoreCase = true) || pendingFocusType.equals("tv", ignoreCase = true)) "tv" else "movies"
        if (currentFilter != expectedFilter) return
        val position = items.indexOfFirst { it.imdbId == imdbId }
        if (position < 0) return

        pendingFocusImdbId = null
        pendingFocusType = null

        val recycler = recyclerView ?: return
        val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return
        val desiredTop = 120

        recycler.post {
            layoutManager.scrollToPositionWithOffset(position, desiredTop)
            adapter.requestHighlight(imdbId, position)
            recycler.post {
                val targetView = layoutManager.findViewByPosition(position)
                val finalDelta = (targetView?.top ?: desiredTop) - desiredTop
                if (kotlin.math.abs(finalDelta) > 2) recycler.scrollBy(0, finalDelta)
            }
        }
    }

    private fun updateContentState(showError: Boolean = false) {
        val view = view ?: return
        val recycler = view.findViewById<RecyclerView>(R.id.discover_recycler)
        val error = view.findViewById<TextView>(R.id.discover_error)
        if (showError) {
            error.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            error.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    private fun updateTimestamp(rootView: View) {
        val updatedView = rootView.findViewById<TextView>(R.id.discover_updated) ?: return
        val label = lastUpdatedLabel
        if (label.isNullOrBlank()) {
            updatedView.visibility = View.GONE
        } else {
            updatedView.text = getString(R.string.discover_updated, label)
            updatedView.visibility = View.VISIBLE
        }
    }

    private fun formatUpdatedLabel(rawValue: String?): String? {
        val value = rawValue?.trim().orEmpty()
        if (value.isBlank()) return null
        return try {
            val instant = Instant.parse(value)
            val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US).withZone(ZoneId.systemDefault())
            formatter.format(instant)
        } catch (_: Exception) {
            value
        }
    }

    private fun parseItems(root: JSONObject, key: String): List<ChartItem> {
        val arr = root.optJSONArray(key) ?: return emptyList()
        val items = mutableListOf<ChartItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            items.add(
                ChartItem(
                    imdbId = obj.optString("imdb_id", ""),
                    title = obj.optString("title", ""),
                    rank = obj.optInt("rank", i + 1),
                    rating = obj.optString("rating", ""),
                    votes = obj.optString("votes", ""),
                    poster = obj.optString("poster", ""),
                    type = key
                )
            )
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
