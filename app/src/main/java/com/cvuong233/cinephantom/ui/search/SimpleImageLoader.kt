package com.cvuong233.cinephantom.ui.search

import android.widget.ImageView
import coil.load
import coil.request.CachePolicy
import coil.request.Disposable
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
