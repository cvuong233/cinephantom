package com.cvuong233.cinephantom.ui.search

import android.util.Log
import android.widget.ImageView
import coil.load
import coil.request.CachePolicy
import coil.request.Disposable
import coil.size.Scale
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import coil.ImageLoader
import android.content.Context

object SimpleImageLoader {

    fun load(
        url: String,
        imageView: ImageView,
        onLoading: () -> Unit = {},
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {},
    ): Boolean {
        if (url.isBlank()) return false
        onLoading()
        imageView.load(url) {
            crossfade(true)
            memoryCachePolicy(CachePolicy.ENABLED)
            size(300, 450)
            listener(
                onStart = { onLoading() },
                onSuccess = { _, _ -> onSuccess() },
                onError = { _, _ -> onError() }
            )
        }
        return true
    }

    /**
     * Load a landscape backdrop/still into a card. Uses a fixed 16:9 target size (matching
     * TMDB's w780 backdrop dimensions) rather than Coil's automatic ViewSizeResolver —
     * callers set the ImageView to GONE while loading, and GONE views are skipped during
     * FrameLayout's measure pass, so a view-size-based request would never resolve and the
     * load would hang forever (no success, no error). Scale.FILL covers the target box
     * regardless of the source image's own aspect ratio; the view's centerCrop scaleType
     * then fills/crops it to the actual card bounds.
     */
    fun loadBackdrop(
        url: String,
        imageView: ImageView,
        onLoading: () -> Unit = {},
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {},
    ): Boolean {
        if (url.isBlank()) return false
        onLoading()
        imageView.load(url) {
            crossfade(true)
            memoryCachePolicy(CachePolicy.ENABLED)
            size(780, 439)
            scale(Scale.FILL)
            listener(
                onStart = { onLoading() },
                onSuccess = { _, _ -> onSuccess() },
                onError = { _, result -> Log.e("CardBackdrop", "loadBackdrop failed url=$url", result.throwable); onError() }
            )
        }
        return true
    }

    /**
     * Load a small cast avatar thumbnail from TMDB profile path.
     * Uses smaller size + network timeout for faster loading on mobile.
     */
    fun loadCastPhoto(
        url: String,
        imageView: ImageView,
        onSuccess: () -> Unit = {},
        onError: () -> Unit = {},
    ) {
        if (url.isBlank()) {
            onError()
            return
        }
        imageView.load(url) {
            crossfade(300)
            size(120, 120)
            memoryCachePolicy(CachePolicy.ENABLED)
            networkCachePolicy(CachePolicy.ENABLED)
            listener(
                onSuccess = { _, _ -> onSuccess() },
                onError = { _, _ -> onError() }
            )
        }
    }
}
