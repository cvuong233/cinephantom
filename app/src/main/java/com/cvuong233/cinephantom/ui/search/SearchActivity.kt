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
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.databinding.ActivitySearchBinding
import com.cvuong233.cinephantom.data.ImdbSuggestionApi
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import kotlin.concurrent.thread

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val api = ImdbSuggestionApi()
    private val adapter = SearchResultsAdapter(::openImdbTitle)

    private val debounceHandler = Handler(Looper.getMainLooper())
    private val debounceDelayMs = 400L
    private var latestQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.resultsRecyclerView.adapter = adapter

        adapter.onStremioClick = { openInStremio(it) }

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

    private fun performSearch() {
        val query = binding.searchEditText.text?.toString().orEmpty().trim()
        if (query.isEmpty()) return
        if (query == latestQuery) return
        latestQuery = query

        adapter.showLoading()
        thread {
            val result = api.search(query)
            runOnUiThread {
                if (latestQuery != query) return@runOnUiThread
                adapter.hideLoading()
                result.onSuccess { titles ->
                    adapter.submitList(titles)
                }.onFailure { error ->
                    adapter.submitList(emptyList())
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
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, title.id)
            putExtra(DetailActivity.EXTRA_TITLE, title.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, title.imageUrl)
            putExtra(DetailActivity.EXTRA_CAST, title.cast)
            putExtra(DetailActivity.EXTRA_YEAR, title.year)
            putExtra(DetailActivity.EXTRA_TYPE, title.typeLabel)
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
