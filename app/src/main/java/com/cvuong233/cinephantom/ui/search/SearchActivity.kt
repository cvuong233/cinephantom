package com.cvuong233.cinephantom.ui.search

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.recyclerview.widget.LinearLayoutManager
import com.cvuong233.cinephantom.data.ImdbSuggestionApi
import com.cvuong233.cinephantom.databinding.ActivitySearchBinding
import kotlin.concurrent.thread

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val api = ImdbSuggestionApi()
    private val adapter = SearchResultsAdapter(::openImdbTitle)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.resultsRecyclerView.adapter = adapter

        binding.searchButton.setOnClickListener { performSearch() }
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

    private fun performSearch() {
        val query = binding.searchEditText.text?.toString().orEmpty().trim()
        if (query.isEmpty()) {
            binding.statusText.text = "Type a title first"
            return
        }

        binding.statusText.text = "Searching IMDb…"
        binding.searchButton.isEnabled = false

        thread {
            val result = api.search(query)
            runOnUiThread {
                binding.searchButton.isEnabled = true
                result.onSuccess { titles ->
                    adapter.submitList(titles)
                    binding.statusText.text = when {
                        titles.isEmpty() -> "No results"
                        else -> "${titles.size} result${if (titles.size == 1) "" else "s"}"
                    }
                }.onFailure { error ->
                    adapter.submitList(emptyList())
                    binding.statusText.text = "Search failed"
                    Toast.makeText(
                        this,
                        error.message ?: "Search failed",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun openImdbTitle(title: com.cvuong233.cinephantom.model.ImdbTitle) {
        val uri = Uri.parse(title.imdbUrl)
        runCatching {
            CustomTabsIntent.Builder().build().launchUrl(this, uri)
        }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    companion object {
        const val EXTRA_INITIAL_QUERY = "extra_initial_query"
    }
}
