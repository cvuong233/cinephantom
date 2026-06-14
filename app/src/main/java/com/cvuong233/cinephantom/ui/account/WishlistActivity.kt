package com.cvuong233.cinephantom.ui.account

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.FavoritesRepository
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SearchResultsAdapter
import kotlinx.coroutines.launch

class WishlistActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0D0011.toInt()
        setContentView(R.layout.activity_wishlist)

        findViewById<TextView>(R.id.toolbar_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.toolbar_title).text = "Wishlist"

        val recycler = findViewById<RecyclerView>(R.id.wishlist_recycler)
        val emptyText = findViewById<TextView>(R.id.wishlist_empty_text)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.itemAnimator = SlideRemoveAnimator()

        val adapter = SearchResultsAdapter { _, title -> openTitle(title) }.apply {
            onStremioClick = { /* no-op */ }
            onFavoriteClick = {
                FavoritesRepository.toggle(it)
                notifyFavoriteChanged(it.id)
            }
        }
        recycler.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FavoritesRepository.favorites.collect { titles ->
                    if (titles.isEmpty()) {
                        emptyText.visibility = View.VISIBLE
                        recycler.visibility = View.GONE
                    } else {
                        emptyText.visibility = View.GONE
                        recycler.visibility = View.VISIBLE
                        adapter.submitList(titles)
                    }
                }
            }
        }
    }

    private fun openTitle(title: ImdbTitle) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, title.id)
            putExtra(DetailActivity.EXTRA_TITLE, title.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, title.imageUrl)
            putExtra(DetailActivity.EXTRA_YEAR, title.year)
            putExtra(DetailActivity.EXTRA_TYPE, title.typeLabel)
            title.tmdbId?.takeIf { it > 0 }?.let { putExtra(DetailActivity.EXTRA_TMDB_ID, it) }
        }
        startActivity(intent)
    }

    /** Fade + slide-left animation for wishlist item removal. */
    private class SlideRemoveAnimator : DefaultItemAnimator() {

        private val pendingRemoves = mutableListOf<RecyclerView.ViewHolder>()

        init {
            removeDuration = 280L
        }

        override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
            endAnimation(holder)
            pendingRemoves.add(holder)
            return true
        }

        override fun runPendingAnimations() {
            val removes = pendingRemoves.toList()
            pendingRemoves.clear()
            for (holder in removes) {
                val view = holder.itemView
                view.animate()
                    .alpha(0f)
                    .translationX(-view.width.toFloat() * 0.18f)
                    .setDuration(removeDuration)
                    .setInterpolator(AccelerateInterpolator())
                    .withStartAction { dispatchRemoveStarting(holder) }
                    .withEndAction {
                        view.animate().setListener(null)
                        view.alpha = 1f
                        view.translationX = 0f
                        dispatchRemoveFinished(holder)
                        if (!isRunning()) dispatchAnimationsFinished()
                    }
                    .start()
            }
            super.runPendingAnimations()
        }

        override fun endAnimation(holder: RecyclerView.ViewHolder) {
            if (pendingRemoves.remove(holder)) {
                holder.itemView.animate().cancel()
                holder.itemView.alpha = 1f
                holder.itemView.translationX = 0f
                dispatchRemoveFinished(holder)
            }
            super.endAnimation(holder)
        }

        override fun endAnimations() {
            pendingRemoves.toList().forEach { holder ->
                holder.itemView.animate().cancel()
                holder.itemView.alpha = 1f
                holder.itemView.translationX = 0f
                dispatchRemoveFinished(holder)
            }
            pendingRemoves.clear()
            super.endAnimations()
        }

        override fun isRunning(): Boolean = pendingRemoves.isNotEmpty() || super.isRunning()
    }
}
