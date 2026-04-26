package com.cvuong233.cinephantom.ui.search

import android.widget.ImageView
import coil.load
import coil.request.CachePolicy

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
}
