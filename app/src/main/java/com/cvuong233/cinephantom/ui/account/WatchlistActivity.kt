package com.cvuong233.cinephantom.ui.account

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.FavoritesRepository
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import kotlinx.coroutines.launch

class WatchlistActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "watchlist_prefs"
        private const val KEY_LAST_TAB = "last_tab"
    }

    private var currentFilter = "movies"
    private var allTitles = listOf<ImdbTitle>()
    private var sequenceStarted = false

    private lateinit var adapter: WatchlistGridAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var filterMovies: TextView
    private lateinit var filterTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0D0011.toInt()
        setContentView(R.layout.activity_watchlist)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        currentFilter = prefs.getString(KEY_LAST_TAB, "movies") ?: "movies"

        findViewById<View>(R.id.toolbar_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.toolbar_title).text = "Watchlist"

        recycler = findViewById(R.id.watchlist_recycler)
        emptyText = findViewById(R.id.watchlist_empty_text)
        filterMovies = findViewById(R.id.watchlist_filter_movies)
        filterTv = findViewById(R.id.watchlist_filter_tv)
        applyTabSelectionStyling(currentFilter)

        adapter = WatchlistGridAdapter(onClick = { openTitle(it) })
        recycler.layoutManager = GridLayoutManager(this, 3)
        recycler.itemAnimator = null
        recycler.adapter = adapter

        filterMovies.setOnClickListener { if (currentFilter != "movies") setFilter("movies") }
        filterTv.setOnClickListener { if (currentFilter != "tv") setFilter("tv") }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FavoritesRepository.favorites.collect { titles ->
                    allTitles = titles
                    if (!sequenceStarted) {
                        sequenceStarted = true
                        adapter.startNewSequence()
                    }
                    applyFilter(animate = false)
                }
            }
        }
    }

    private fun isMovieType(title: ImdbTitle): Boolean =
        title.typeLabel?.lowercase()?.trim() == "movie"

    private fun filteredList(): List<ImdbTitle> {
        val base = when (currentFilter) {
            "movies" -> allTitles.filter { isMovieType(it) }
            "tv"     -> allTitles.filter { !isMovieType(it) }
            else     -> allTitles
        }
        return base.sortedByDescending { it.year?.trim()?.toIntOrNull() ?: 0 }
    }

    private fun applyTabSelectionStyling(type: String) {
        val selected = if (type == "movies") filterMovies else filterTv
        val unselected = listOf(filterMovies, filterTv).filter { it !== selected }
        selected.setBackgroundResource(R.drawable.bg_discover_tab_selected)
        selected.setTextColor(0xFFFFFFFF.toInt())
        unselected.forEach {
            it.setBackgroundResource(R.drawable.bg_discover_tab_unselected)
            it.setTextColor(resources.getColor(R.color.text_muted, null))
        }
    }

    private fun setFilter(type: String) {
        val tabOrder = listOf("movies", "tv")
        val fromIndex = tabOrder.indexOf(currentFilter)
        val toIndex = tabOrder.indexOf(type)
        val movingForward = toIndex >= fromIndex
        currentFilter = type
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_LAST_TAB, type).apply()

        val selected = if (type == "movies") filterMovies else filterTv
        val unselected = listOf(filterMovies, filterTv).filter { it !== selected }

        selected.animate().cancel()
        unselected.forEach { it.animate().cancel() }
        applyTabSelectionStyling(type)

        selected.scaleX = 0.9f; selected.scaleY = 0.9f
        selected.alpha = 0.78f; selected.translationY = 4f
        selected.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(220).start()
        unselected.forEach { v ->
            v.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.88f).translationY(2f).setDuration(150)
                .withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(120).start()
                }.start()
        }

        adapter.startNewSequence()
        applyFilter(animate = true, movingForward = movingForward)
    }

    private fun applyFilter(animate: Boolean, movingForward: Boolean = true) {
        val filtered = filteredList()

        if (animate && filtered.isNotEmpty()) {
            recycler.animate().cancel()
            recycler.alpha = 0f
            recycler.translationX = if (movingForward) 36f else -36f
            recycler.animate()
                .alpha(1f).translationX(0f)
                .setDuration(190)
                .setInterpolator(DecelerateInterpolator(1.2f))
                .start()
        }

        if (filtered.isEmpty()) {
            val emptyMsg = when (currentFilter) {
                "movies" -> "No movies in your watchlist"
                else     -> "No TV shows in your watchlist"
            }
            if (adapter.itemCount > 0) {
                adapter.submitList(emptyList())
                emptyText.postDelayed({
                    emptyText.text = emptyMsg
                    emptyText.visibility = View.VISIBLE
                    recycler.visibility = View.GONE
                }, 200L)
            } else {
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
        startActivity(Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, title.id)
            putExtra(DetailActivity.EXTRA_TITLE, title.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, title.imageUrl)
            putExtra(DetailActivity.EXTRA_YEAR, title.year)
            putExtra(DetailActivity.EXTRA_TYPE, title.typeLabel)
            title.tmdbId?.takeIf { it > 0 }?.let { putExtra(DetailActivity.EXTRA_TMDB_ID, it) }
        })
    }
}
