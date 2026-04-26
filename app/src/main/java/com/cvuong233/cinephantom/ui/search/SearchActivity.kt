package com.cvuong233.cinephantom.ui.search

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.databinding.ActivitySearchBinding
import com.cvuong233.cinephantom.data.ImdbSuggestionApi
import com.cvuong233.cinephantom.data.SearchRatingLoader
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.data.RatingFetcher
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import kotlin.concurrent.thread

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val api = ImdbSuggestionApi()
    private val adapter = SearchResultsAdapter(::openImdbTitle)

    private val debounceHandler = Handler(Looper.getMainLooper())
    private val debounceDelayMs = 400L
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

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                debounceHandler.removeCallbacksAndMessages(null)
                debounceHandler.postDelayed({ performSearch() }, debounceDelayMs)
            }
        })

        binding.searchEditText.requestFocus()

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

    override fun onDestroy() {
        super.onDestroy()
        debounceHandler.removeCallbacksAndMessages(null)
    }

    override fun onResume() {
        super.onResume()
        pasteClipboard()
    }

    private fun pasteClipboard() {
        val input = binding.searchEditText.text?.toString().orEmpty().trim()
        if (input.isNotEmpty()) return // already has text, don't overwrite

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return

        val text = clip.getItemAt(0).text?.toString()?.trim() ?: return
        if (text.isBlank() || text.length > 200) return

        binding.searchEditText.setText(text)
        binding.searchEditText.setSelection(text.length)
        performSearch()
    }

    private fun updateEmptyState() {
        val query = binding.searchEditText.text?.toString().orEmpty().trim()
        val hasResults = adapter.itemCount > 0 && !adapter.isLoading()

        if (query.isEmpty()) {
            // No query entered yet — show guidance
            binding.emptyStateContainer.visibility = View.VISIBLE
            binding.emptyStateIcon.text = "🎬"
            binding.emptyStateTitle.text = getString(R.string.search_guidance)
            binding.emptyStateSubtitle.text = getString(R.string.search_empty_subtitle)
        } else if (!hasResults && !adapter.isLoading()) {
            // Search returned no results
            binding.emptyStateContainer.visibility = View.VISIBLE
            binding.emptyStateIcon.text = "🔍"
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



    private fun openImdbTitle(title: ImdbTitle) {
        // Use rating from the item (already visible on search card), or from
        // the shared RatingFetcher cache if the item hasn't been re-bound yet.
        // This guarantees search card and detail page always show the same value.
        val effectiveRating = if (title.rating != null && title.rating > 0f) {
            title.rating
        } else {
            RatingFetcher().fetchRating(title.id)?.takeIf { it > 0f }
        }

        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, title.id)
            putExtra(DetailActivity.EXTRA_TITLE, title.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, title.imageUrl)
            putExtra(DetailActivity.EXTRA_CAST, title.cast)
            putExtra(DetailActivity.EXTRA_YEAR, title.year)
            putExtra(DetailActivity.EXTRA_TYPE, title.typeLabel)
            if (effectiveRating != null && effectiveRating > 0f) {
                putExtra(DetailActivity.EXTRA_RATING, effectiveRating)
            }
        }
        startActivity(intent)
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
