package com.cvuong233.cinephantom.ui.account

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
        setContentView(R.layout.activity_wishlist)

        findViewById<TextView>(R.id.wishlist_back).setOnClickListener { finish() }

        val recycler = findViewById<RecyclerView>(R.id.wishlist_recycler)
        val emptyText = findViewById<TextView>(R.id.wishlist_empty_text)
        recycler.layoutManager = LinearLayoutManager(this)

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
                        adapter.hideLoading()
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
}
