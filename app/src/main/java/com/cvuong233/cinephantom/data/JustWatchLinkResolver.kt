package com.cvuong233.cinephantom.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves the exact platform URL for a title (e.g. https://www.netflix.com/title/81564463)
 * by fetching TMDB's per-title JustWatch page and scanning its HTML for outbound links to
 * known streaming domains. This is what lets the detail-page buttons open the exact title
 * inside the platform app instead of just a generic search.
 */
object JustWatchLinkResolver {

    private const val TAG = "JustWatchLinkResolver"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Key is matched against the TMDB provider name (case-insensitive "contains").
    private val domainPatterns = mapOf(
        "netflix" to Regex("""https?://(?:www\.)?netflix\.com/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
        "prime video" to Regex("""https?://(?:www\.)?(?:primevideo|amazon)\.[a-z.]+/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
        "amazon" to Regex("""https?://(?:www\.)?(?:primevideo|amazon)\.[a-z.]+/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
        "disney" to Regex("""https?://(?:www\.)?disneyplus\.com/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
        "max" to Regex("""https?://(?:www\.|play\.)?max\.com/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
        "hbo" to Regex("""https?://(?:www\.|play\.)?(?:max|hbomax)\.com/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
        "hulu" to Regex("""https?://(?:www\.)?hulu\.com/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
        "paramount" to Regex("""https?://(?:www\.)?paramountplus\.com/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
        "peacock" to Regex("""https?://(?:www\.)?peacocktv\.com/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
        "apple tv" to Regex("""https?://tv\.apple\.com/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
        "youtube" to Regex("""https?://(?:www\.)?youtube\.com/[^"'\s<>]+""", RegexOption.IGNORE_CASE),
    )

    // Returns a map of the same keys as domainPatterns, but only for platforms whose
    // exact URL was actually found on the page. Blocking — call from a background thread.
    fun resolvePlatformUrls(justWatchUrl: String): Map<String, String> {
        return try {
            val request = Request.Builder()
                .url(justWatchUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .build()
            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "resolvePlatformUrls: HTTP ${response.code} for $justWatchUrl")
                    return emptyMap()
                }
                response.body?.string()
            }
            if (html.isNullOrBlank()) {
                Log.d(TAG, "resolvePlatformUrls: empty body for $justWatchUrl")
                return emptyMap()
            }

            val resolved = mutableMapOf<String, String>()
            for ((key, pattern) in domainPatterns) {
                val match = pattern.find(html)?.value?.trimEnd('\\') ?: continue
                resolved[key] = match
            }
            Log.d(TAG, "resolvePlatformUrls: $justWatchUrl -> ${resolved.keys}")
            resolved
        } catch (e: Exception) {
            Log.d(TAG, "resolvePlatformUrls: failed for $justWatchUrl: ${e.message}")
            emptyMap()
        }
    }
}
