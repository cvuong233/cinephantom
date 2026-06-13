package com.cvuong233.cinephantom.ui.search

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.databinding.ActivitySearchBinding
import com.cvuong233.cinephantom.data.ImdbSuggestionApi
import com.cvuong233.cinephantom.data.SearchRatingLoader
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import kotlin.concurrent.thread

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val api = ImdbSuggestionApi()
    private val adapter = SearchResultsAdapter { view, title -> openImdbTitle(view, title) }

    private var searchJob: Thread? = null
    private var ratingLoader: SearchRatingLoader? = null
    private var latestQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.resultsRecyclerView.adapter = adapter

        adapter.onStremioClick = { openInStremio(it) }

        // Register adapter data observer for empty state
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                updateEmptyState()
            }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                updateEmptyState()
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                updateEmptyState()
            }
        })

        // Initial empty state
        updateEmptyState()

        binding.searchEditText.requestFocus()
        binding.searchEditText.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
        }, 200)

        binding.searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else {
                false
            }
        }

        val initialQuery = when (intent?.action) {
            Intent.ACTION_SEARCH -> intent.getStringExtra(SearchManager.QUERY)
            else -> intent.getStringExtra(EXTRA_INITIAL_QUERY)
        }

        initialQuery?.let {
            binding.searchEditText.setText(it)
            binding.searchEditText.setSelection(it.length)
            performSearch()
        }
    }

    private fun updateEmptyState() {
        val query = binding.searchEditText.text?.toString().orEmpty().trim()
        val hasResults = adapter.itemCount > 0 && !adapter.isLoading()

        if (query.isEmpty()) {
            // No query entered yet — show guidance
            binding.emptyStateContainer.visibility = View.VISIBLE
            binding.emptyStateIcon.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.ic_search_empty))
            binding.emptyStateIcon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.secondary_accent))
            binding.emptyStateTitle.text = getString(R.string.search_guidance)
            binding.emptyStateSubtitle.text = getString(R.string.search_empty_subtitle)
        } else if (!hasResults && !adapter.isLoading()) {
            // Search returned no results
            binding.emptyStateContainer.visibility = View.VISIBLE
            binding.emptyStateIcon.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.ic_search_empty_active))
            binding.emptyStateIcon.imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.neon_pink))
            binding.emptyStateTitle.text = getString(R.string.search_no_results_title)
            binding.emptyStateSubtitle.text = getString(R.string.search_no_results_subtitle)
        } else {
            // Has results or loading
            binding.emptyStateContainer.visibility = View.GONE
        }
    }

    private fun performSearch() {
        val query = binding.searchEditText.text?.toString().orEmpty().trim()
        if (query.isEmpty()) {
            latestQuery = ""
            adapter.submitList(emptyList())
            return
        }
        if (query == latestQuery) return
        latestQuery = query

        // Cancel previous search + rating loader
        searchJob?.interrupt()
        ratingLoader?.cancel()
        adapter.showLoading()
        updateEmptyState()

        searchJob = thread {
            val result = api.search(query)
            if (Thread.currentThread().isInterrupted) return@thread
            runOnUiThread {
                if (latestQuery != query) return@runOnUiThread
                result.onSuccess { titles ->
                    adapter.submitList(titles)
                    adapter.hideLoading()
                    updateEmptyState()

                    // Kick off rating fetch for visible results
                    if (titles.isNotEmpty()) {
                        ratingLoader = SearchRatingLoader(
                            onRatingFetched = { updatedTitle ->
                                runOnUiThread { adapter.updateRating(updatedTitle) }
                            },
                            onComplete = { runOnUiThread { adapter.onRatingFetchDone() } },
                        ).apply { load(titles) }
                    }

                }.onFailure { error ->
                    adapter.submitList(emptyList())
                    adapter.hideLoading()
                    updateEmptyState()
                    Toast.makeText(
                        this,
                        error.message ?: "Search failed",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }



    private fun openImdbTitle(posterView: View, title: ImdbTitle) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, title.id)
            putExtra(DetailActivity.EXTRA_TITLE, title.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, title.imageUrl)
            putExtra(DetailActivity.EXTRA_CAST, title.cast)
            putExtra(DetailActivity.EXTRA_YEAR, title.year)
            putExtra(DetailActivity.EXTRA_TYPE, title.typeLabel)
        }
        ViewCompat.setTransitionName(posterView, "poster_${title.id}")
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            this, posterView, "poster_${title.id}"
        )
        startActivity(intent, options.toBundle())
    }

    private fun openInStremio(title: ImdbTitle) {
        val stremioType = when (title.typeLabel) {
            "TV Series", "TV Mini Series", "TV Series (mini)" -> "series"
            "TV Episode" -> "episode"
            else -> "movie"
        }
        val stremioUri = Uri.parse("stremio://detail/$stremioType/${title.id}")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, stremioUri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.stremio_not_installed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_INITIAL_QUERY = "extra_initial_query"
    }
}
