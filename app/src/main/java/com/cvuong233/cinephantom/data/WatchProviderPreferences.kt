package com.cvuong233.cinephantom.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

// Enabled/disabled state for streaming platforms — synced via Firestore so it follows the
// user across devices (users/{uid}/settings/streamingPlatforms). Platforms the user has never
// touched fall back to WatchProviderOverrides.isEnabled()'s major-platform default.
object WatchProviderOverrides {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _overrides = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val overrides: StateFlow<Map<Int, Boolean>> = _overrides.asStateFlow()

    private var listenerReg: ListenerRegistration? = null

    fun init() {
        auth.addAuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser != null) startListening() else stopListening()
        }
    }

    private fun startListening() {
        val uid = auth.currentUser?.uid ?: return
        listenerReg?.remove()
        listenerReg = db.collection("users").document(uid)
            .collection("settings").document("streamingPlatforms")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                val map = mutableMapOf<Int, Boolean>()
                snapshot.data.orEmpty().forEach { (key, value) ->
                    val id = key.toIntOrNull()
                    val enabled = value as? Boolean
                    if (id != null && enabled != null) map[id] = enabled
                }
                _overrides.value = map
            }
    }

    private fun stopListening() {
        listenerReg?.remove()
        listenerReg = null
        _overrides.value = emptyMap()
    }

    // Until the user explicitly enables a platform, only Stremio is on by default — everything
    // else in the (now major-platforms-only) Settings list starts unchecked so the detail page's
    // button row only shows what the user actually opted into.
    fun isMajorPlatform(providerId: Int, providerName: String): Boolean {
        return providerId == WatchProviderPreferences.STREMIO_SENTINEL_ID
    }

    fun isEnabled(providerId: Int, providerName: String = ""): Boolean =
        _overrides.value[providerId] ?: isMajorPlatform(providerId, providerName)

    fun setEnabled(providerId: Int, enabled: Boolean) {
        _overrides.value = _overrides.value + (providerId to enabled)
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid)
            .collection("settings").document("streamingPlatforms")
            .set(mapOf(providerId.toString() to enabled), SetOptions.merge())
    }
}

class WatchProviderPreferences private constructor(private val prefs: android.content.SharedPreferences) {

    // All providers available in the user's region — fetched once from TMDB, cached here.
    // This is just an app-level fetch cache, not a user preference, so it stays local.
    var cachedProviders: List<TMDBWatchProvider>
        get() {
            val json = prefs.getString(KEY_CACHED_PROVIDERS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    TMDBWatchProvider(
                        id = o.optInt("id"),
                        name = o.optString("name"),
                        logoPath = o.optString("logoPath").ifBlank { null }
                    )
                }
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("logoPath", p.logoPath ?: "")
                })
            }
            prefs.edit().putString(KEY_CACHED_PROVIDERS, arr.toString()).apply()
        }

    // TMDB's bulk /watch/providers/{movie,tv} catalog is what populates the Settings list, but
    // it's known to omit or bury major first-party platforms (Netflix, Disney+, Apple TV+...)
    // that the reliable per-title /movie|tv/{id}/watch/providers endpoint DOES return (that's
    // what powers the detail page's provider buttons). So every time a title's per-title
    // providers are fetched (DetailActivity), they're merged in here too — the Settings list
    // becomes the union of the bulk catalog and everything ever actually seen on a title,
    // and only grows over time instead of being capped by the bulk endpoint's gaps. Union by
    // id; first-seen entry (with its logo) wins on conflicts.
    fun mergeProviders(newProviders: List<TMDBWatchProvider>) {
        if (newProviders.isEmpty()) return
        val merged = LinkedHashMap<Int, TMDBWatchProvider>()
        cachedProviders.forEach { merged[it.id] = it }
        newProviders.forEach { merged.putIfAbsent(it.id, it) }
        cachedProviders = merged.values.toList()
    }

    fun isEnabled(providerId: Int, providerName: String = ""): Boolean =
        WatchProviderOverrides.isEnabled(providerId, providerName)

    fun setEnabled(providerId: Int, enabled: Boolean) {
        WatchProviderOverrides.setEnabled(providerId, enabled)
    }

    companion object {
        // Bumped to "_v2" so anyone who already cached a stale/region-limited list
        // (see fetchAvailableWatchProviders region-merge fix) transparently refetches
        // once instead of being stuck reading the old cached value forever.
        private const val KEY_CACHED_PROVIDERS = "cached_providers_v2"

        // Stremio isn't a TMDB watch provider — it's our own button on the detail page.
        // Real TMDB provider_ids are always positive, so this negative sentinel can't collide.
        const val STREMIO_SENTINEL_ID = -1

        // TMDB's stable global provider_id for Netflix. Used as a fallback key when we synthesize
        // a Netflix button ourselves (see DetailActivity) — TMDB's watch-provider data (sourced
        // from JustWatch) has dropped Netflix almost everywhere, so its flatrate list for a title
        // essentially never contains a real Netflix entry to key off of.
        const val NETFLIX_PROVIDER_ID = 8

        // TMDB provider_ids for the major platforms the Settings list should show. Filtering by
        // id (not name) is what actually excludes ad-tier/regional variants — "Amazon Prime Video
        // with Ads" and "Amazon Prime Video Free with Ads" are separate provider_ids from plain
        // Prime Video (9), so they're naturally left out without any name-matching heuristics.
        val MAJOR_PROVIDER_IDS = setOf(
            8,    // Netflix
            9,    // Amazon Prime Video
            337,  // Disney+
            1899, // Max
            384,  // Max (legacy HBO Max id, still returned for some regions)
            15,   // Hulu
            2,    // Apple TV+
            531,  // Paramount+
            386,  // Peacock Premium
            387,  // Peacock Premium Plus
        )

        fun get(context: Context) = WatchProviderPreferences(
            context.applicationContext.getSharedPreferences("watch_provider_prefs", Context.MODE_PRIVATE)
        )
    }
}
