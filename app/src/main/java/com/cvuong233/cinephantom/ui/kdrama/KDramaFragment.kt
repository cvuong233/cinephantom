package com.cvuong233.cinephantom.ui.kdrama

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.KDramaChartsApi
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.discover.DiscoverResultsAdapter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class KDramaFragment : Fragment() {

    private lateinit var adapter: DiscoverResultsAdapter
    private val api = KDramaChartsApi()
    private var lastUpdatedLabel: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_kdrama, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler = view.findViewById<RecyclerView>(R.id.kdrama_recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.itemAnimator = null

        adapter = DiscoverResultsAdapter(
            skeletonLayoutRes = R.layout.item_discover_skeleton,
            showRankLabel = true,
            onClick = { backdropView, title -> openTitle(backdropView, title) }
        )
        recycler.adapter = adapter

        updateTimestamp(view)
        loadCharts()
    }

    private fun openTitle(backdropView: View, title: ImdbTitle) {
        val intent = Intent(requireContext(), DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, title.id)
            putExtra(DetailActivity.EXTRA_TITLE, title.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, title.imageUrl)
            putExtra(DetailActivity.EXTRA_BACKDROP_URL, title.landscapeImageUrl)
            putExtra(DetailActivity.EXTRA_CAST, title.cast)
            putExtra(DetailActivity.EXTRA_YEAR, title.year)
            putExtra(DetailActivity.EXTRA_TYPE, "TV Series")
            title.tmdbId?.takeIf { it > 0 }?.let { putExtra(DetailActivity.EXTRA_TMDB_ID, it) }
            (title.ratingText ?: title.featuredMetricLabel)?.takeIf { it.isNotBlank() }
                ?.let { putExtra(DetailActivity.EXTRA_FUNDEX_RATING, it) }
        }
        ViewCompat.setTransitionName(backdropView, "backdrop_${title.id}")
        val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
            requireActivity(), backdropView, "backdrop_${title.id}"
        )
        startActivity(intent, options.toBundle())
    }

    private fun loadCharts() {
        val rootView = view ?: return
        val error = rootView.findViewById<TextView>(R.id.kdrama_error)
        error.visibility = View.GONE
        adapter.showLoading()

        Thread {
            val result = api.fetchTopKDramas()
            activity?.runOnUiThread {
                if (result.isSuccess) {
                    val (updated, _, list) = result.getOrThrow()
                    lastUpdatedLabel = formatUpdatedLabel(updated)
                    updateTimestamp(rootView)
                    adapter.hideLoading()
                    adapter.submitList(list)
                    updateContentState(list.isEmpty())
                } else {
                    adapter.hideLoading()
                    updateContentState(showError = true)
                }
            }
        }.start()
    }

    private fun updateContentState(showError: Boolean) {
        val rootView = view ?: return
        val recycler = rootView.findViewById<RecyclerView>(R.id.kdrama_recycler)
        val error = rootView.findViewById<TextView>(R.id.kdrama_error)
        if (showError) {
            error.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            error.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    private fun updateTimestamp(rootView: View) {
        val updatedView = rootView.findViewById<TextView>(R.id.kdrama_updated) ?: return
        val label = lastUpdatedLabel
        if (label.isNullOrBlank()) {
            updatedView.visibility = View.GONE
        } else {
            updatedView.text = getString(R.string.discover_updated, label)
            updatedView.visibility = View.VISIBLE
        }
    }

    private fun formatUpdatedLabel(rawValue: String?): String? {
        val value = rawValue?.trim().orEmpty()
        if (value.isBlank()) return null
        return try {
            val instant = Instant.parse(value)
            val formatter = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US).withZone(ZoneId.systemDefault())
            formatter.format(instant)
        } catch (_: Exception) {
            value
        }
    }
}
