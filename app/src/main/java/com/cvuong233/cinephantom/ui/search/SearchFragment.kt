package com.cvuong233.cinephantom.ui.search

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.SearchHistoryRepository
import com.cvuong233.cinephantom.data.TMDBApi
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.FuturisticAnim
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import android.graphics.Color
import android.content.Intent
import android.view.animation.OvershootInterpolator
import android.view.ViewTreeObserver
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.appcompat.content.res.AppCompatResources
import kotlin.concurrent.thread

class SearchFragment : Fragment() {

    private val api = TMDBApi()
    private val adapter by lazy { SearchResultsAdapter { view, title -> openImdbTitle(view, title) } }
    private val suggestionsAdapter by lazy {
        SearchSuggestionsAdapter(
            onSuggestionClick = { onSuggestionTapped(it) },
            onClearHistory = { onClearHistoryTapped() },
        )
    }
    private var searchJob: Thread? = null
    private var autocompleteJob: Thread? = null
    private var latestQuery = ""
    private var bindingRef: com.cvuong233.cinephantom.databinding.ActivitySearchBinding? = null

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingAutocomplete: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val binding = com.cvuong233.cinephantom.databinding.ActivitySearchBinding.inflate(inflater, container, false)
        bindingRef = binding
        return binding.root.also { setupView(binding) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Show keyboard on initial creation (only if still on Search tab after 300ms)
        view.postDelayed({
            if (!isHidden && isAdded) openKeyboard()
        }, 300)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) openKeyboard() else clearSearchFocus()
    }

    private fun openKeyboard() {
        val binding = bindingRef ?: return
        binding.searchEditText.requestFocus()
        binding.searchEditText.isCursorVisible = true
        val imm = context?.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
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
        hideSuggestions(binding)
    }

    private fun setupView(binding: com.cvuong233.cinephantom.databinding.ActivitySearchBinding) {
        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.resultsRecyclerView.adapter = adapter

        binding.suggestionsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.suggestionsRecyclerView.adapter = suggestionsAdapter
        // Cap so a long list scrolls internally instead of pushing the search bar off screen.
        val screenHeightCap = (resources.displayMetrics.heightPixels * 0.6).toInt()
        val fixedCap = (300 * resources.displayMetrics.density).toInt()
        binding.suggestionsRecyclerView.maxHeightPx = minOf(screenHeightCap, fixedCap)

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

        // Search bar focus state
        binding.searchEditText.setOnFocusChangeListener { _, hasFocus ->
            val layout = binding.searchInputLayout
            binding.searchEditText.isCursorVisible = hasFocus
            if (hasFocus) {
                layout.boxStrokeColor = Color.parseColor("#CDA8FF")
                layout.alpha = 1f
                layout.animate().scaleX(1.02f).scaleY(1.02f).setDuration(250)
                    .setInterpolator(OvershootInterpolator(0.5f)).start()

                val query = binding.searchEditText.text?.toString()?.trim().orEmpty()
                if (query.isEmpty()) showHistorySuggestions(binding) else fetchAutocomplete(binding, query)
            } else {
                layout.boxStrokeColor = Color.parseColor("#8F7E8F")
                layout.alpha = 0.8f
                layout.animate().scaleX(1f).scaleY(1f).setDuration(250).start()

                // Small delay so a tap on a suggestion row still registers before we hide it.
                binding.root.postDelayed({
                    if (bindingRef != null && !binding.searchEditText.hasFocus()) hideSuggestions(binding)
                }, 150L)
            }
        }

        // Live autocomplete as the user types (debounced)
        binding.searchEditText.doAfterTextChanged { text ->
            val query = text?.toString()?.trim().orEmpty()
            pendingAutocomplete?.let { debounceHandler.removeCallbacks(it) }
            if (query.isEmpty()) {
                showHistorySuggestions(binding)
            } else {
                val runnable = Runnable { fetchAutocomplete(binding, query) }
                pendingAutocomplete = runnable
                debounceHandler.postDelayed(runnable, 300L)
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
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
                binding.searchEditText.clearFocus()
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
        hideSuggestions(binding)

        if (query.isEmpty()) {
            latestQuery = ""
            adapter.submitList(emptyList())
            return
        }

        thread { SearchHistoryRepository.record(query) }

        if (query == latestQuery) return
        latestQuery = query

        searchJob?.interrupt()
        adapter.showLoading()
        updateEmptyState(binding)

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

    // ── Suggestions (autocomplete + history) ────────────────────────────────

    private fun showHistorySuggestions(binding: com.cvuong233.cinephantom.databinding.ActivitySearchBinding) {
        if (!binding.searchEditText.hasFocus()) return
        thread {
            val history = SearchHistoryRepository.recentBlocking()
            activity?.runOnUiThread {
                val b = bindingRef ?: return@runOnUiThread
                if (b.searchEditText.text?.toString()?.trim().orEmpty().isNotEmpty()) return@runOnUiThread
                if (!b.searchEditText.hasFocus()) return@runOnUiThread
                if (history.isEmpty()) {
                    hideSuggestions(b)
                } else {
                    val items: List<SearchSuggestion> = listOf(SearchSuggestion.ClearHistoryHeader) +
                        history.map { SearchSuggestion.History(it) }
                    suggestionsAdapter.submitList(items)
                    b.suggestionsRecyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun fetchAutocomplete(binding: com.cvuong233.cinephantom.databinding.ActivitySearchBinding, query: String) {
        autocompleteJob?.interrupt()
        autocompleteJob = thread {
            val result = api.searchTitles(query)
            if (Thread.currentThread().isInterrupted) return@thread
            val titles = result.getOrNull().orEmpty().take(8)

            activity?.runOnUiThread {
                val b = bindingRef ?: return@runOnUiThread
                val currentQuery = b.searchEditText.text?.toString()?.trim().orEmpty()
                if (currentQuery != query || !b.searchEditText.hasFocus()) return@runOnUiThread
                if (titles.isEmpty()) {
                    hideSuggestions(b)
                } else {
                    suggestionsAdapter.submitList(titles.map { SearchSuggestion.Autocomplete(it) })
                    b.suggestionsRecyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun onSuggestionTapped(suggestion: SearchSuggestion) {
        val binding = bindingRef ?: return
        val query = when (suggestion) {
            is SearchSuggestion.History -> suggestion.query
            is SearchSuggestion.Autocomplete -> suggestion.title.title
            SearchSuggestion.ClearHistoryHeader -> return
        }
        binding.searchEditText.setText(query)
        binding.searchEditText.setSelection(query.length)
        binding.searchEditText.clearFocus()
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchEditText.windowToken, 0)
        performSearch(binding)
    }

    private fun onClearHistoryTapped() {
        thread {
            SearchHistoryRepository.clearBlocking()
            activity?.runOnUiThread {
                bindingRef?.let { hideSuggestions(it) }
            }
        }
    }

    private fun hideSuggestions(binding: com.cvuong233.cinephantom.databinding.ActivitySearchBinding) {
        binding.suggestionsRecyclerView.visibility = View.GONE
    }

    private fun openImdbTitle(backdropView: View, title: ImdbTitle) {
        val intent = Intent(requireContext(), DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, title.id)
            putExtra(DetailActivity.EXTRA_TITLE, title.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, title.imageUrl)
            putExtra(DetailActivity.EXTRA_BACKDROP_URL, title.landscapeImageUrl)
            putExtra(DetailActivity.EXTRA_CAST, title.cast)
            putExtra(DetailActivity.EXTRA_YEAR, title.year)
            putExtra(DetailActivity.EXTRA_TYPE, title.typeLabel)
            title.tmdbId?.let { putExtra(DetailActivity.EXTRA_TMDB_ID, it) }
        }
        ViewCompat.setTransitionName(backdropView, "backdrop_${title.id}")
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            requireActivity(), backdropView, "backdrop_${title.id}"
        )
        startActivity(intent, options.toBundle())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pendingAutocomplete?.let { debounceHandler.removeCallbacks(it) }
        autocompleteJob?.interrupt()
        bindingRef = null
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
