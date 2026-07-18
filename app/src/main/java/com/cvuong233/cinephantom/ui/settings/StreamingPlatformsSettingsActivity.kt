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
import coil.dispose
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
    // Number of real TMDB providers currently displayed (excludes the manual Stremio row).
    // Lets the silent background refresh decide whether a fetch is more complete than the
    // list already on screen before it rebuilds/re-sorts.
    private var displayedProviderCount = 0

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
                // A synced enable/disable change (a local toggle OR a sync from another device)
                // only needs the checkbox states refreshed — NOT a re-sort. Re-sorting here is
                // what made rows jump enabled-to-top under the user's finger on every tap.
                WatchProviderOverrides.overrides.collect { platformAdapter.refreshEnabledStates() }
            }
        }

        val cached = watchProviderPrefs.cachedProviders
        if (cached.isNotEmpty()) {
            // Show the cached list instantly, but always re-fetch in the background so a
            // thin/stale cache (e.g. a region that only indexed a few providers) is replaced
            // by the full merged VN+US catalog. The settings screen must always show every
            // available platform, never a partial set.
            showPlatformList(cached)
            fetchAndShow(showLoadingText = false, backgroundRefresh = true)
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

    // backgroundRefresh = true is the silent heal-the-cache pass fired while a cached list is
    // already on screen: it only rebuilds the UI when the fetch actually yields more platforms
    // than are showing, and never surfaces a toast/error (the visible cached list stays put).
    private fun fetchAndShow(
        showLoadingText: Boolean,
        backgroundRefresh: Boolean = false,
        onDone: (() -> Unit)? = null,
    ) {
        if (showLoadingText) {
            platformLoadingText.visibility = View.VISIBLE
            platformLoadingText.text = "Loading platforms…"
            platformList.visibility = View.GONE
        }
        thread {
            val fetched = TMDBApi().fetchAvailableWatchProviders()
            runOnUiThread {
                // Merge rather than overwrite — the cache also accumulates providers seen via
                // per-title lookups on the detail page (see WatchProviderPreferences.mergeProviders),
                // which catch major platforms the bulk catalog endpoint sometimes omits. Overwriting
                // here would wipe those out on every background refresh.
                if (fetched.isNotEmpty()) watchProviderPrefs.mergeProviders(fetched)
                val merged = watchProviderPrefs.cachedProviders
                if (merged.isNotEmpty()) {
                    // In background mode, only take over the display if the merged result is more
                    // complete than what's already shown — avoids re-sorting the list out from
                    // under the user when the cache was already the full catalog.
                    if (!backgroundRefresh || merged.size > displayedProviderCount) {
                        showPlatformList(merged)
                    }
                    if (merged.size < 5 && !backgroundRefresh) {
                        Toast.makeText(
                            this,
                            "Only ${merged.size} platform${if (merged.size == 1) "" else "s"} found — check logcat (tag TMDBApi) for details",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else if (!backgroundRefresh) {
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
        val majorProviders = providers.filter { it.id in WatchProviderPreferences.MAJOR_PROVIDER_IDS }
        allProviders = listOf(stremioEntry) + majorProviders
        displayedProviderCount = majorProviders.size
        renderList()
        platformLoadingText.visibility = View.GONE
        platformList.visibility = View.VISIBLE
    }

    // Sorts enabled platforms first, then disabled, alphabetically within each group. This is
    // the ONE-TIME ordering, applied only when the list is (re)built — see showPlatformList.
    // Toggles must NOT re-run this, or rows would jump under the user's finger; a toggle only
    // refreshes checkbox states in place (see PlatformAdapter.refreshEnabledStates).
    private fun renderList() {
        if (allProviders.isEmpty()) return
        // Stremio always leads the list regardless of alphabetical position — it's our own
        // button, not a TMDB platform, and should never be buried under "A"-"R" providers.
        val (stremio, rest) = allProviders.partition { it.id == WatchProviderPreferences.STREMIO_SENTINEL_ID }
        val sortedRest = rest.sortedWith(
            compareByDescending<TMDBWatchProvider> { watchProviderPrefs.isEnabled(it.id, it.name) }
                .thenBy { it.name.lowercase() }
        )
        platformAdapter.submitList(stremio + sortedRest)
    }

    private class PlatformAdapter(
        private val prefs: WatchProviderPreferences,
        private val onToggle: (TMDBWatchProvider, Boolean) -> Unit,
    ) : RecyclerView.Adapter<PlatformAdapter.ViewHolder>() {

        private val items = mutableListOf<TMDBWatchProvider>()

        companion object {
            private const val PAYLOAD_ENABLED = "enabled"
        }

        fun submitList(newItems: List<TMDBWatchProvider>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        // Re-reads each row's enabled state from the synced prefs WITHOUT changing the order.
        // Used when an override changes (local toggle or cross-device sync) so checkboxes stay
        // accurate while rows keep their original one-time sort position.
        fun refreshEnabledStates() {
            if (items.isEmpty()) return
            notifyItemRangeChanged(0, items.size, PAYLOAD_ENABLED)
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

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_ENABLED)) {
                holder.refreshChecked(items[position])
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val row: View = itemView.findViewById(R.id.platform_row)
            private val logo: ImageView = itemView.findViewById(R.id.platform_logo)
            private val name: TextView = itemView.findViewById(R.id.platform_name)
            private val checkbox: MaterialCheckBox = itemView.findViewById(R.id.platform_checkbox)

            // Updates only the checkbox from the current synced state — no logo reload, no
            // reorder. Setting isChecked to its current value is a no-op, so a local toggle
            // that already flipped the box doesn't flicker.
            fun refreshChecked(provider: TMDBWatchProvider) {
                checkbox.isChecked = prefs.isEnabled(provider.id, provider.name)
            }

            fun bind(provider: TMDBWatchProvider) {
                name.text = provider.name
                checkbox.isChecked = prefs.isEnabled(provider.id, provider.name)
                // Cancel any in-flight Coil request left over from this recycled view's previous
                // binding — otherwise a stale async load (e.g. a TMDB provider logo) can land here
                // after we've already set the Stremio drawable below, silently overwriting it.
                logo.dispose()
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
