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

    // Substrings matched case-insensitively against provider names to decide the default-on
    // set. Real TMDB provider names vary slightly by region (e.g. "Amazon Prime Video" vs
    // "Prime Video"), so this matches loosely rather than by exact id.
    private val MAJOR_PLATFORM_KEYWORDS = listOf(
        "netflix", "disney", "prime video", "max", "apple tv", "hulu",
        "paramount", "peacock", "crunchyroll", "youtube", "google play",
        "amazon video", "fubo",
    )

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

    fun isMajorPlatform(providerId: Int, providerName: String): Boolean {
        if (providerId == WatchProviderPreferences.STREMIO_SENTINEL_ID) return true
        val lower = providerName.lowercase()
        return MAJOR_PLATFORM_KEYWORDS.any { lower.contains(it) }
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

        fun get(context: Context) = WatchProviderPreferences(
            context.applicationContext.getSharedPreferences("watch_provider_prefs", Context.MODE_PRIVATE)
        )
    }
}
