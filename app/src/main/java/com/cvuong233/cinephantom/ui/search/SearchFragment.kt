package com.cvuong233.cinephantom.ui.search

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.TMDBApi
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.FuturisticAnim
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import android.graphics.Color
import android.content.Intent
import android.content.ActivityNotFoundException
import android.view.animation.OvershootInterpolator
import android.view.ViewTreeObserver
import android.net.Uri
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.appcompat.content.res.AppCompatResources
import com.cvuong233.cinephantom.data.WatchlistDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

class SearchFragment : Fragment() {

    private val api = TMDBApi()
    private val adapter by lazy { SearchResultsAdapter { view, title -> openImdbTitle(view, title) } }
    private val debounceHandler = Handler(Looper.getMainLooper())
    private val debounceDelayMs = 400L
    private var searchJob: Thread? = null
    private var latestQuery = ""
    private var bindingRef: com.cvuong233.cinephantom.databinding.ActivitySearchBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val binding = com.cvuong233.cinephantom.databinding.ActivitySearchBinding.inflate(inflater, container, false)
        bindingRef = binding
        return binding.root.also { setupView(binding) }
    }

    fun clearSearchFocus() {
        val binding = bindingRef ?: return
        binding.searchEditText.clearFocus()
        binding.searchEditText.clearComposingText()
        binding.searchEditText.isCursorVisible = false
        binding.root.isFocusableInTouchMode = true
        binding.root.requestFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun setupView(binding: com.cvuong233.cinephantom.databinding.ActivitySearchBinding) {
        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.resultsRecyclerView.adapter = adapter

        adapter.onStremioClick = { openInStremio(it) }

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() { updateEmptyState(binding) }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { updateEmptyState(binding) }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { updateEmptyState(binding) }
        })

        // Filter chips
        binding.filterAllChip.setOnClickListener { setFilter(binding, null) }
        binding.filterMoviesChip.setOnClickListener { setFilter(binding, "movie") }
        binding.filterTvChip.setOnClickListener { setFilter(binding, "series") }
        setFilter(binding, null)

        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                debounceHandler.removeCallbacksAndMessages(null)
                debounceHandler.postDelayed({ performSearch(binding) }, debounceDelayMs)
            }
        })

        binding.searchEditText.requestFocus()
        binding.searchEditText.isCursorVisible = true
        binding.searchEditText.postDelayed({
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
        }, 300)

        // Search bar focus state
        binding.searchEditText.setOnFocusChangeListener { _, hasFocus ->
            val layout = binding.searchInputLayout
            binding.searchEditText.isCursorVisible = hasFocus
            if (hasFocus) {
                layout.boxStrokeColor = Color.parseColor("#CDA8FF")
                layout.alpha = 1f
                layout.animate().scaleX(1.02f).scaleY(1.02f).setDuration(250)
                    .setInterpolator(OvershootInterpolator(0.5f)).start()
            } else {
                layout.boxStrokeColor = Color.parseColor("#8F7E8F")
                layout.alpha = 0.8f
                layout.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
            }
        }

        // Staggered results entrance
        val observer = binding.resultsRecyclerView.viewTreeObserver
        observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                animateVisibleItems(binding)
            }
        })

        binding.searchEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(binding)
                true
            } else false
        }
    }

    private fun setFilter(binding: com.cvuong233.cinephantom.databinding.ActivitySearchBinding, type: String?) { return }  // disabled

    private fun updateEmptyState(binding: com.cvuong233.cinephantom.databinding.ActivitySearchBinding) {
        val query = binding.searchEditText.text?.toString().orEmpty().trim()
        val hasResults = adapter.itemCount > 0 && !adapter.isLoading()
        if (query.isEmpty()) {
            binding.emptyStateContainer.visibility = View.VISIBLE
            binding.emptyStateIcon.setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_search_empty))
            binding.emptyStateIcon.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.secondary_accent))
            binding.emptyStateTitle.text = getString(R.string.search_guidance)
            binding.emptyStateSubtitle.text = getString(R.string.search_empty_subtitle)
        } else if (!hasResults && !adapter.isLoading()) {
            binding.emptyStateContainer.visibility = View.VISIBLE
            binding.emptyStateIcon.setImageDrawable(AppCompatResources.getDrawable(requireContext(), R.drawable.ic_search_empty_active))
            binding.emptyStateIcon.imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.neon_pink))
            binding.emptyStateTitle.text = getString(R.string.search_no_results_title)
            binding.emptyStateSubtitle.text = getString(R.string.search_no_results_subtitle)
        } else {
            binding.emptyStateContainer.visibility = View.GONE
        }
    }

    private fun performSearch(binding: com.cvuong233.cinephantom.databinding.ActivitySearchBinding) {
        val query = binding.searchEditText.text?.toString().orEmpty().trim()
        if (query.isEmpty()) {
            latestQuery = ""
            adapter.submitList(emptyList())
            return
        }
        if (query == latestQuery) return
        latestQuery = query

        searchJob?.interrupt()
        adapter.showLoading()
        updateEmptyState(binding)

        val currentFilter: String? = null  // disabled
        searchJob = thread {
            val result = api.searchTitles(query)
            if (Thread.currentThread().isInterrupted) return@thread
            val filtered = result.getOrNull() ?: emptyList()

            activity?.runOnUiThread {
                if (latestQuery != query) return@runOnUiThread
                adapter.submitList(filtered)
                adapter.hideLoading()
                updateEmptyState(binding)
                adapter.onRatingFetchDone()
                if (filtered.isEmpty() && query.isNotEmpty()) {
                    updateEmptyState(binding)
                }
            }
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

    private fun toggleWatchlist(title: ImdbTitle) {
        val db = WatchlistDatabase.get(requireContext())
        CoroutineScope(Dispatchers.IO).launch {
            val isSaved = db.dao().isSaved(title.id)
            if (isSaved) {
                db.dao().delete(title.id)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.watchlist_removed, Toast.LENGTH_SHORT).show()
                }
            } else {
                db.dao().insert(
                    com.cvuong233.cinephantom.model.WatchlistItem(
                        imdbId = title.id,
                        title = title.title,
                        type = if (title.typeLabel?.contains("series", ignoreCase = true) == true || title.typeLabel?.contains("episode", ignoreCase = true) == true) "series" else "movie",
                        year = title.year,
                        imageUrl = title.imageUrl,
                        cast = title.cast
                    )
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.watchlist_added, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bindingRef = null
    }

    override fun onDestroy() {
        super.onDestroy()
        debounceHandler.removeCallbacksAndMessages(null)
    }

    private fun animateVisibleItems(binding: com.cvuong233.cinephantom.databinding.ActivitySearchBinding) {
        val rv = binding.resultsRecyclerView
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            if (child.alpha >= 0.99f) continue
            child.translationY = 60f
            child.alpha = 0f
            child.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(350)
                .setStartDelay(i * 45L)
                .setInterpolator(OvershootInterpolator(0.7f))
                .start()
        }
    }
}
