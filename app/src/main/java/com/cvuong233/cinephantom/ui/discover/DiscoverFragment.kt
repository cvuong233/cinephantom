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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.RatingFetcher
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SearchResultsAdapter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class DiscoverFragment : Fragment() {

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
    private lateinit var adapter: SearchResultsAdapter
    private var recyclerView: RecyclerView? = null
    private var pendingFocusImdbId: String? = null
    private var pendingFocusType: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_discover, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filterMovies = view.findViewById<TextView>(R.id.discover_filter_movies)
        val filterTv = view.findViewById<TextView>(R.id.discover_filter_tv)
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.discover_swipe)
        val recycler = view.findViewById<RecyclerView>(R.id.discover_recycler)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recyclerView = recycler
        recycler.itemAnimator = null
        adapter = SearchResultsAdapter { posterView, title -> openImdbTitle(posterView, title) }
        adapter.onStremioClick = { openInStremio(it) }
        adapter.onRatingNeeded = { title -> loadVisibleRating(title) }
        recycler.adapter = adapter

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { updateContentState() }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { updateContentState() }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { updateContentState() }
        })

        filterMovies.setOnClickListener { if (currentFilter != "movies") setFilter("movies") }
        filterTv.setOnClickListener { if (currentFilter != "tv") setFilter("tv") }
        swipe.setOnRefreshListener { loadCharts(forceRefresh = true) }

        setFilter("movies")
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
        unselected.setTextColor(0xFF8F7E8F.toInt())

        selected.scaleX = 0.92f
        selected.scaleY = 0.92f
        selected.alpha = 0.82f
        selected.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(240)
            .start()

        unselected.animate()
            .scaleX(0.98f)
            .scaleY(0.98f)
            .alpha(0.9f)
            .setDuration(180)
            .withEndAction {
                unselected.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(140).start()
            }
            .start()

        showContent()
    }

    private fun loadCharts(forceRefresh: Boolean = false) {
        val view = view ?: return
        val error = view.findViewById<TextView>(R.id.discover_error)
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.discover_swipe)

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

                val movies = parseItems(root, "movies")
                val tvShows = parseItems(root, "tv")

                activity?.runOnUiThread {
                    swipe.isRefreshing = false
                    allMovies = movies
                    allTv = tvShows
                    isLoaded = true
                    showContent()
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    swipe.isRefreshing = false
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
        inFlightRatings.clear()
        recycler?.animate()?.cancel()
        recycler?.alpha = 0f
        recycler?.translationX = if (currentFilter == "movies") -28f else 28f
        recycler?.translationY = 14f
        recycler?.scaleX = 0.985f
        recycler?.scaleY = 0.985f
        adapter.hideLoading()
        adapter.submitList(titles)
        updateContentState(showError = items.isEmpty() && isLoaded)
        applyPendingFocus(items)
        recycler?.animate()
            ?.alpha(1f)
            ?.translationX(0f)
            ?.translationY(0f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(300)
            ?.start()
    }

    private fun loadVisibleRating(title: ImdbTitle) {
        if (!inFlightRatings.add(title.id)) return
        thread {
            try {
                val rating = ratingFetcher.fetchRating(title.id)
                if (rating != null && rating > 0f) {
                    activity?.runOnUiThread {
                        adapter.updateRating(title.copy(rating = rating, ratingText = String.format(java.util.Locale.US, "%.1f", rating)))
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
        recycler.post {
            (recycler.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 24)
            recycler.postDelayed({
                adapter.requestHighlight(imdbId, position)
            }, 180)
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
                cast = votes.ifBlank { "Top IMDb" },
                imageUrl = poster.ifBlank { "https://images.metahub.space/poster/small/$imdbId/img" },
                rating = rating.toFloatOrNull(),
                ratingText = rating.ifBlank { null },
                rankLabel = "#${rank}"
            )
        }
    }
}
