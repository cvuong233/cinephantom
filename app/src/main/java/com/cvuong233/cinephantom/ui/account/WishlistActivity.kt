package com.cvuong233.cinephantom.ui.account

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.FavoritesRepository
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SearchResultsAdapter
import kotlinx.coroutines.launch

class WishlistActivity : AppCompatActivity() {

    private val slideAnimator = SlideRemoveAnimator()
    private var currentFilter = "movies"
    private var allTitles = listOf<ImdbTitle>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0D0011.toInt()
        setContentView(R.layout.activity_wishlist)

        findViewById<View>(R.id.toolbar_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.toolbar_title).text = "Wishlist"

        val recycler = findViewById<RecyclerView>(R.id.wishlist_recycler)
        val emptyText = findViewById<TextView>(R.id.wishlist_empty_text)
        val filterMovies = findViewById<TextView>(R.id.wishlist_filter_movies)
        val filterTv = findViewById<TextView>(R.id.wishlist_filter_tv)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.itemAnimator = slideAnimator

        val adapter = SearchResultsAdapter { _, title -> openTitle(title) }.apply {
            onStremioClick = { /* no-op */ }
            onFavoriteClick = { title ->
                // Don't call notifyFavoriteChanged — doing so races with the StateFlow
                // collect path and can cause a notifyItemChanged + notifyItemRemoved conflict
                // on the same position. The collect handles the visual update correctly.
                FavoritesRepository.toggle(title)
            }
        }
        recycler.adapter = adapter

        filterMovies.setOnClickListener { if (currentFilter != "movies") setFilter("movies", recycler, filterMovies, filterTv, emptyText, adapter) }
        filterTv.setOnClickListener { if (currentFilter != "tv") setFilter("tv", recycler, filterMovies, filterTv, emptyText, adapter) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FavoritesRepository.favorites.collect { titles ->
                    allTitles = titles
                    applyFilter(recycler, emptyText, adapter, animate = false)
                }
            }
        }
    }

    private fun isMovieType(title: ImdbTitle): Boolean =
        title.typeLabel?.lowercase()?.trim() == "movie"

    private fun filteredList(): List<ImdbTitle> = when (currentFilter) {
        "movies" -> allTitles.filter { isMovieType(it) }
        "tv" -> allTitles.filter { !isMovieType(it) }
        else -> allTitles
    }

    private fun setFilter(
        type: String,
        recycler: RecyclerView,
        filterMovies: TextView,
        filterTv: TextView,
        emptyText: TextView,
        adapter: SearchResultsAdapter,
    ) {
        val movingToTv = type == "tv"
        currentFilter = type
        val selected = if (type == "movies") filterMovies else filterTv
        val unselected = if (type == "movies") filterTv else filterMovies

        selected.animate().cancel()
        unselected.animate().cancel()
        selected.setBackgroundResource(R.drawable.bg_discover_tab_selected)
        selected.setTextColor(0xFFFFFFFF.toInt())
        unselected.setBackgroundResource(R.drawable.bg_discover_tab_unselected)
        unselected.setTextColor(resources.getColor(R.color.text_muted, null))

        selected.scaleX = 0.9f; selected.scaleY = 0.9f; selected.alpha = 0.78f; selected.translationY = 4f
        selected.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(220).start()
        unselected.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.88f).translationY(2f).setDuration(150)
            .withEndAction { unselected.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(120).start() }
            .start()

        applyFilter(recycler, emptyText, adapter, animate = true, movingToTv = movingToTv)
    }

    private fun applyFilter(
        recycler: RecyclerView,
        emptyText: TextView,
        adapter: SearchResultsAdapter,
        animate: Boolean,
        movingToTv: Boolean = false,
    ) {
        val filtered = filteredList()
        val isEmpty = filtered.isEmpty()

        if (animate && !isEmpty) {
            recycler.animate().cancel()
            recycler.alpha = 0f
            recycler.translationX = if (movingToTv) 34f else -34f
            recycler.animate().alpha(1f).translationX(0f)
                .setDuration(180).setInterpolator(DecelerateInterpolator(1.2f)).start()
        }

        if (isEmpty) {
            if (adapter.itemCount > 0) {
                recycler.visibility = View.VISIBLE
                adapter.submitList(emptyList())
                emptyText.postDelayed({
                    val emptyMsg = if (currentFilter == "movies") "No movies in your wishlist"
                        else "No TV shows in your wishlist"
                    emptyText.text = emptyMsg
                    emptyText.visibility = View.VISIBLE
                    recycler.visibility = View.GONE
                }, slideAnimator.removeDuration + 60L)
            } else {
                val emptyMsg = if (currentFilter == "movies") "No movies in your wishlist"
                    else "No TV shows in your wishlist"
                emptyText.text = emptyMsg
                emptyText.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            }
        } else {
            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            adapter.submitList(filtered)
        }
    }

    private fun openTitle(title: ImdbTitle) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, title.id)
            putExtra(DetailActivity.EXTRA_TITLE, title.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, title.imageUrl)
            putExtra(DetailActivity.EXTRA_YEAR, title.year)
            putExtra(DetailActivity.EXTRA_TYPE, title.typeLabel)
            title.tmdbId?.takeIf { it > 0 }?.let { putExtra(DetailActivity.EXTRA_TMDB_ID, it) }
        }
        startActivity(intent)
    }

    /**
     * Fade + slide-left removal animation.
     *
     * Bugs fixed vs. naive DefaultItemAnimator subclass approach:
     *  1. `isRunning()` returns true while the ViewPropertyAnimator is active (not just while
     *     items are in `pendingRemoves`), so RecyclerView doesn't recycle animating views early.
     *  2. `super.runPendingAnimations()` is still called so adds/moves/changes work normally.
     *  3. No call to `view.animate()` inside `withEndAction` — that would reset the animator.
     */
    class SlideRemoveAnimator : DefaultItemAnimator() {

        private val pendingRemoves = mutableListOf<RecyclerView.ViewHolder>()

        // Counts animations currently in flight (not yet in withEndAction)
        private var activeCount = 0

        init {
            removeDuration = 280L
        }

        override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
            endAnimation(holder)
            pendingRemoves.add(holder)
            return true  // tells RecyclerView to call runPendingAnimations()
        }

        override fun runPendingAnimations() {
            val removes = pendingRemoves.toList()
            pendingRemoves.clear()
            for (holder in removes) {
                activeCount++
                val view = holder.itemView
                view.animate()
                    .alpha(0f)
                    .translationX(-view.width.toFloat() * 0.20f)
                    .setDuration(removeDuration)
                    .setInterpolator(AccelerateInterpolator())
                    .withStartAction {
                        dispatchRemoveStarting(holder)
                    }
                    .withEndAction {
                        // Reset view state for RecyclerView recycling
                        view.alpha = 1f
                        view.translationX = 0f
                        activeCount--
                        dispatchRemoveFinished(holder)
                        // Notify RecyclerView all animations are done if nothing else is pending
                        if (!isRunning()) dispatchAnimationsFinished()
                    }
                    .start()
            }
            // Let parent handle any pending adds / moves / changes
            super.runPendingAnimations()
        }

        override fun endAnimation(holder: RecyclerView.ViewHolder) {
            if (pendingRemoves.remove(holder)) {
                // Was pending but not yet started — cancel immediately
                holder.itemView.alpha = 1f
                holder.itemView.translationX = 0f
                dispatchRemoveFinished(holder)
                if (!isRunning()) dispatchAnimationsFinished()
            } else {
                // Might be actively animating — cancel the ViewPropertyAnimator
                holder.itemView.animate().cancel()
            }
            super.endAnimation(holder)
        }

        override fun endAnimations() {
            val toCancel = pendingRemoves.toList()
            pendingRemoves.clear()
            for (holder in toCancel) {
                holder.itemView.alpha = 1f
                holder.itemView.translationX = 0f
                dispatchRemoveFinished(holder)
            }
            activeCount = 0
            super.endAnimations()
        }

        // isRunning must return true while our animations are in flight so RecyclerView
        // doesn't recycle the animating view holders prematurely.
        override fun isRunning(): Boolean =
            pendingRemoves.isNotEmpty() || activeCount > 0 || super.isRunning()
    }
}
