package com.cvuong233.cinephantom.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.TMDBApi
import com.cvuong233.cinephantom.data.TMDBWatchProvider
import com.cvuong233.cinephantom.data.WatchProviderOverrides
import com.cvuong233.cinephantom.data.WatchProviderPreferences
import com.cvuong233.cinephantom.ui.search.SimpleImageLoader
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class StreamingPlatformsSettingsActivity : AppCompatActivity() {

    private lateinit var watchProviderPrefs: WatchProviderPreferences
    private lateinit var platformLoadingText: TextView
    private lateinit var platformList: RecyclerView
    private lateinit var platformAdapter: PlatformAdapter
    private lateinit var refreshButton: ImageView
    private var isRefreshing = false
    private var allProviders: List<TMDBWatchProvider> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0D0011.toInt()
        setContentView(R.layout.activity_streaming_platforms)

        findViewById<View>(R.id.toolbar_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.toolbar_title).text = "Streaming Platforms"

        watchProviderPrefs = WatchProviderPreferences.get(this)
        platformLoadingText = findViewById(R.id.platform_loading_text)
        platformList = findViewById(R.id.platform_list)
        refreshButton = findViewById(R.id.streaming_platforms_refresh)

        platformAdapter = PlatformAdapter(watchProviderPrefs) { provider, enabled ->
            watchProviderPrefs.setEnabled(provider.id, enabled)
        }
        platformList.layoutManager = LinearLayoutManager(this)
        platformList.adapter = platformAdapter

        refreshButton.setOnClickListener { forceRefetch() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WatchProviderOverrides.overrides.collect { renderList() }
            }
        }

        val cached = watchProviderPrefs.cachedProviders
        if (cached.isNotEmpty()) {
            showPlatformList(cached)
        } else {
            fetchAndShow(showLoadingText = true)
        }
    }

    // User-triggered re-fetch that bypasses the cache entirely — for when the cached
    // list is thin/stale and the automatic cache-key-bump migration didn't help
    // (e.g. it already refetched once and got an equally thin result).
    private fun forceRefetch() {
        if (isRefreshing) return
        isRefreshing = true
        spinRefreshIcon()
        fetchAndShow(showLoadingText = platformAdapter.itemCount == 0) {
            isRefreshing = false
        }
    }

    private fun fetchAndShow(showLoadingText: Boolean, onDone: (() -> Unit)? = null) {
        if (showLoadingText) {
            platformLoadingText.visibility = View.VISIBLE
            platformLoadingText.text = "Loading platforms…"
            platformList.visibility = View.GONE
        }
        thread {
            val fetched = TMDBApi().fetchAvailableWatchProviders()
            runOnUiThread {
                if (fetched.isNotEmpty()) {
                    watchProviderPrefs.cachedProviders = fetched
                    showPlatformList(fetched)
                    if (fetched.size < 5) {
                        Toast.makeText(
                            this,
                            "Only ${fetched.size} platform${if (fetched.size == 1) "" else "s"} found — check logcat (tag TMDBApi) for details",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    platformLoadingText.visibility = View.VISIBLE
                    platformLoadingText.text = "Couldn't load platforms. Check your connection."
                    platformList.visibility = View.GONE
                }
                onDone?.invoke()
            }
        }
    }

    private fun spinRefreshIcon() {
        val anim = RotateAnimation(
            0f, 360f,
            RotateAnimation.RELATIVE_TO_SELF, 0.5f,
            RotateAnimation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 500
            repeatCount = 0
        }
        refreshButton.startAnimation(anim)
    }

    private fun showPlatformList(providers: List<TMDBWatchProvider>) {
        // Stremio is our own button, not a TMDB provider — add it manually so it can be
        // toggled the same way. Kept out of the cached/fetched list itself so the cache
        // only ever holds real TMDB data.
        val stremioEntry = TMDBWatchProvider(
            id = WatchProviderPreferences.STREMIO_SENTINEL_ID,
            name = "Stremio",
            logoPath = null,
        )
        allProviders = listOf(stremioEntry) + providers
        renderList()
        platformLoadingText.visibility = View.GONE
        platformList.visibility = View.VISIBLE
    }

    // Enabled platforms first, then disabled, alphabetically within each group. Re-run any
    // time overrides change (a toggle here, or a sync update from another device) so the
    // list always reflects the current enabled/disabled grouping.
    private fun renderList() {
        if (allProviders.isEmpty()) return
        val sorted = allProviders.sortedWith(
            compareByDescending<TMDBWatchProvider> { watchProviderPrefs.isEnabled(it.id, it.name) }
                .thenBy { it.name.lowercase() }
        )
        platformAdapter.submitList(sorted)
    }

    private class PlatformAdapter(
        private val prefs: WatchProviderPreferences,
        private val onToggle: (TMDBWatchProvider, Boolean) -> Unit,
    ) : RecyclerView.Adapter<PlatformAdapter.ViewHolder>() {

        private val items = mutableListOf<TMDBWatchProvider>()

        fun submitList(newItems: List<TMDBWatchProvider>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_platform_checkbox, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val row: View = itemView.findViewById(R.id.platform_row)
            private val logo: ImageView = itemView.findViewById(R.id.platform_logo)
            private val name: TextView = itemView.findViewById(R.id.platform_name)
            private val checkbox: MaterialCheckBox = itemView.findViewById(R.id.platform_checkbox)

            fun bind(provider: TMDBWatchProvider) {
                name.text = provider.name
                checkbox.isChecked = prefs.isEnabled(provider.id, provider.name)
                logo.setImageDrawable(null)
                if (provider.id == WatchProviderPreferences.STREMIO_SENTINEL_ID) {
                    logo.setImageResource(R.drawable.ic_stremio_brand)
                } else {
                    provider.logoUrl?.let { url -> SimpleImageLoader.load(url = url, imageView = logo) }
                }

                row.setOnClickListener {
                    val newState = !checkbox.isChecked
                    checkbox.isChecked = newState
                    onToggle(provider, newState)
                }
            }
        }
    }
}
