package com.cvuong233.cinephantom.ui.watchlist

import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.content.Intent
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.FavoritesRepository
import com.cvuong233.cinephantom.data.WatchlistDatabase
import com.cvuong233.cinephantom.notifications.WishlistNotificationScheduler
import com.cvuong233.cinephantom.ui.account.AuthActivity
import com.google.firebase.auth.FirebaseAuth
import com.cvuong233.cinephantom.model.WatchlistItem
import com.cvuong233.cinephantom.ui.FuturisticAnim
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SearchResultsAdapter
import com.cvuong233.cinephantom.model.ImdbTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchlistFragment : Fragment() {

    private val toWatchAdapter by lazy {
        SearchResultsAdapter { _, title -> openTitle(title) }.apply {
            onStremioClick = { /* no-op */ }
            onFavoriteClick = { toggleFavorite(it, this) }
        }
    }

    private val watchedAdapter by lazy {
        SearchResultsAdapter { _, title -> openTitle(title) }.apply {
            onStremioClick = { /* no-op */ }
            onFavoriteClick = { toggleFavorite(it, this) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_watchlist, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.watchlist_swipe)
        swipe.setOnRefreshListener { refresh() }

        val toWatchRecycler = view.findViewById<RecyclerView>(R.id.towatch_recycler)
        toWatchRecycler.layoutManager = LinearLayoutManager(requireContext())
        toWatchRecycler.adapter = toWatchAdapter

        val watchedRecycler = view.findViewById<RecyclerView>(R.id.watched_recycler)
        watchedRecycler.layoutManager = LinearLayoutManager(requireContext())
        watchedRecycler.adapter = watchedAdapter

        val db = WatchlistDatabase.get(requireContext())
        lifecycleScope.launch {
            launch {
                db.dao().getToWatch().collect { updateToWatch(view, it) }
            }
            launch {
                db.dao().getWatched().collect { updateWatched(view, it) }
            }
        }
    }

    private fun refresh() {
        // Flow-based — already live. Just stop the spinner after a brief delay.
        view?.postDelayed({
            view?.findViewById<SwipeRefreshLayout>(R.id.watchlist_swipe)?.isRefreshing = false
        }, 600)
    }

    private fun updateToWatch(view: View, items: List<WatchlistItem>) {
        val section = view.findViewById<View>(R.id.towatch_section)
        val empty = view.findViewById<View>(R.id.watchlist_empty)

        if (items.isEmpty()) {
            section.visibility = View.GONE
        } else {
            section.visibility = View.VISIBLE
            toWatchAdapter.submitList(items.map { it.toImdbTitle() })
            toWatchAdapter.hideLoading()
            staggeredItemEntrance(view.findViewById(R.id.towatch_recycler))
        }

        updateOverallEmpty(view)
    }

    private fun updateWatched(view: View, items: List<WatchlistItem>) {
        val section = view.findViewById<View>(R.id.watched_section)

        if (items.isEmpty()) {
            section.visibility = View.GONE
        } else {
            section.visibility = View.VISIBLE
            watchedAdapter.submitList(items.map { it.toImdbTitle() })
            watchedAdapter.hideLoading()
            staggeredItemEntrance(view.findViewById(R.id.watched_recycler))
        }

        updateOverallEmpty(view)
    }

    private fun updateOverallEmpty(view: View) {
        val toWatchSection = view.findViewById<View>(R.id.towatch_section)
        val watchedSection = view.findViewById<View>(R.id.watched_section)
        val empty = view.findViewById<View>(R.id.watchlist_empty)
        empty.visibility = if (toWatchSection.visibility == View.GONE && watchedSection.visibility == View.GONE)
            View.VISIBLE else View.GONE
    }

    private fun toggleFavorite(title: ImdbTitle, adapter: com.cvuong233.cinephantom.ui.search.SearchResultsAdapter) {
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

    private fun openTitle(title: ImdbTitle) {
        val intent = Intent(requireContext(), DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, title.id)
            putExtra(DetailActivity.EXTRA_TITLE, title.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, title.imageUrl)
            putExtra(DetailActivity.EXTRA_CAST, title.cast)
            putExtra(DetailActivity.EXTRA_YEAR, title.year)
            putExtra(DetailActivity.EXTRA_TYPE, title.typeLabel)
        }
        startActivity(intent)
    }

    private fun markAsWatched(title: ImdbTitle) {
        lifecycleScope.launch {
            WatchlistDatabase.get(requireContext()).dao().markWatched(title.id)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), R.string.watched_marked, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun markAsUnwatched(title: ImdbTitle) {
        lifecycleScope.launch {
            WatchlistDatabase.get(requireContext()).dao().markUnwatched(title.id)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), R.string.watched_unmarked, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun staggeredItemEntrance(recycler: RecyclerView) {
        Handler(Looper.getMainLooper()).postDelayed({
            for (i in 0 until recycler.childCount) {
                val child = recycler.getChildAt(i) ?: continue
                if (child.alpha >= 0.99f) continue
                child.translationY = 40f
                child.alpha = 0f
                child.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(350)
                    .setStartDelay(i * 50L)
                    .setInterpolator(android.view.animation.OvershootInterpolator(0.6f))
                    .start()
            }
        }, 100)
    }

    private fun WatchlistItem.toImdbTitle() = ImdbTitle(
        id = imdbId,
        title = title,
        typeLabel = if (type == "series") "TV Series" else "Movie",
        year = year,
        cast = cast,
        imageUrl = imageUrl
    )
}
