package com.cvuong233.cinephantom.ui.discover

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import com.cvuong233.cinephantom.ui.search.SimpleImageLoader
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DiscoverFragment : Fragment() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var currentFilter: String = "movies"
    private var allMovies = listOf<ChartItem>()
    private var allTv = listOf<ChartItem>()
    private var isLoaded = false
    private lateinit var adapter: DiscoverAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_discover, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val filterMovies = view.findViewById<TextView>(R.id.discover_filter_movies)
        val filterTv = view.findViewById<TextView>(R.id.discover_filter_tv)
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.discover_swipe)
        val recycler = view.findViewById<RecyclerView>(R.id.discover_recycler)

        recycler.layoutManager = GridLayoutManager(requireContext(), 2)
        adapter = DiscoverAdapter { view, item ->
            val intent = Intent(requireContext(), DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_IMDB_ID, item.imdbId)
                putExtra(DetailActivity.EXTRA_TITLE, item.title)
                putExtra(DetailActivity.EXTRA_IMAGE_URL, item.poster)
                putExtra(DetailActivity.EXTRA_TYPE, if (item.type == "tv") "TV Series" else "Movie")
                putExtra(DetailActivity.EXTRA_YEAR, "")
                putExtra(DetailActivity.EXTRA_CAST, "")
            }
            ViewCompat.setTransitionName(view, "poster_${item.imdbId}")
            val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                requireActivity(), view, "poster_${item.imdbId}"
            )
            startActivity(intent, options.toBundle())
        }
        recycler.adapter = adapter

        filterMovies.setOnClickListener { setFilter("movies") }
        filterTv.setOnClickListener { setFilter("tv") }
        swipe.setOnRefreshListener { loadCharts() }

        setFilter("movies")
        loadCharts()
    }

    private fun setFilter(type: String) {
        currentFilter = type
        val view = view ?: return
        val filterMovies = view.findViewById<TextView>(R.id.discover_filter_movies)
        val filterTv = view.findViewById<TextView>(R.id.discover_filter_tv)

        if (type == "movies") {
            filterMovies.setBackgroundResource(R.drawable.bg_search_glow)
            filterMovies.setTextColor(0xFFFFFFFF.toInt())
            filterTv.setBackgroundResource(R.drawable.bg_glass_card)
            filterTv.setTextColor(0xFF7C8AAF.toInt())
        } else {
            filterTv.setBackgroundResource(R.drawable.bg_search_glow)
            filterTv.setTextColor(0xFFFFFFFF.toInt())
            filterMovies.setBackgroundResource(R.drawable.bg_glass_card)
            filterMovies.setTextColor(0xFF7C8AAF.toInt())
        }
        showContent()
    }

    private fun loadCharts() {
        val view = view ?: return
        val loading = view.findViewById<LinearLayout>(R.id.discover_loading)
        val error = view.findViewById<TextView>(R.id.discover_error)
        val swipe = view.findViewById<SwipeRefreshLayout>(R.id.discover_swipe)

        loading.visibility = View.VISIBLE
        error.visibility = View.GONE
        animateDots()

        Thread {
            try {
                val req = Request.Builder()
                    .url("https://cvuong233.github.io/agent-presentation/imdb_charts.json")
                    .header("User-Agent", "CinePhantom/1.0")
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: throw Exception("Empty response")
                val root = JSONObject(body)

                val movies = parseItems(root, "movies")
                val tvShows = parseItems(root, "tv")

                activity?.runOnUiThread {
                    loading.visibility = View.GONE
                    swipe?.isRefreshing = false
                    allMovies = movies
                    allTv = tvShows
                    isLoaded = true
                    adapter.submitList(if (currentFilter == "movies") allMovies else allTv)
                    showContent()
                }
            } catch (_: Exception) {
                activity?.runOnUiThread {
                    loading.visibility = View.GONE
                    swipe?.isRefreshing = false
                    if (!isLoaded) error.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    private fun animateDots() {
        val view = view ?: return
        val dots = listOf(
            view.findViewById<View>(R.id.dot_1),
            view.findViewById<View>(R.id.dot_2),
            view.findViewById<View>(R.id.dot_3)
        )
        dots.forEachIndexed { i, dot ->
            dot.animate().cancel()
            dot.alpha = 0.3f
            dot.animate()
                .alpha(1f)
                .setDuration(400)
                .setStartDelay(i * 150L)
                .withEndAction {
                    dot.animate().alpha(0.3f).setDuration(400).withEndAction {
                        if (view.findViewById<LinearLayout>(R.id.discover_loading).visibility == View.VISIBLE) {
                            animateDots()
                        }
                    }
                }
        }
    }

    private fun showContent() {
        val view = view ?: return
        val recycler = view.findViewById<RecyclerView>(R.id.discover_recycler)
        val error = view.findViewById<TextView>(R.id.discover_error)

        val items = if (currentFilter == "movies") allMovies else allTv

        if (items.isEmpty() && isLoaded) {
            error.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            error.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            adapter.submitList(items)
            recycler.scheduleLayoutAnimation()
            Handler(Looper.getMainLooper()).postDelayed({
                // Animate each child for staggered entrance
                val anim = AnimationUtils.loadAnimation(requireContext(), android.R.anim.fade_in)
                anim.duration = 300
                for (i in 0 until recycler.childCount) {
                    val child = recycler.getChildAt(i)
                    child.alpha = 0f
                    child.animate()
                        .alpha(1f)
                        .setDuration(250)
                        .setStartDelay((i * 40).toLong())
                        .start()
                }
            }, 100)
        }
    }

    private fun parseItems(root: JSONObject, key: String): List<ChartItem> {
        val arr = root.optJSONArray(key) ?: return emptyList()
        val items = mutableListOf<ChartItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            items.add(ChartItem(
                imdbId = obj.optString("imdb_id", ""),
                title = obj.optString("title", ""),
                rank = obj.optInt("rank", i + 1),
                rating = obj.optString("rating", ""),
                votes = obj.optString("votes", ""),
                poster = obj.optString("poster", ""),
                type = key
            ))
        }
        return items
    }

    private class DiscoverAdapter(
        private val onItemClick: (View, ChartItem) -> Unit
    ) : RecyclerView.Adapter<DiscoverAdapter.ViewHolder>() {

        private var items: List<ChartItem> = emptyList()

        fun submitList(newItems: List<ChartItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_discover_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item)
            holder.itemView.setOnClickListener { onItemClick(holder.posterView, item) }
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val posterView: ImageView = itemView.findViewById(R.id.discover_poster)
            private val rankView: TextView = itemView.findViewById(R.id.discover_rank)
            private val titleView: TextView = itemView.findViewById(R.id.discover_title)
            private val metaView: TextView = itemView.findViewById(R.id.discover_meta)

            fun bind(item: ChartItem) {
                rankView.text = "#${item.rank}"
                titleView.text = item.title

                val metaParts = mutableListOf<String>()
                if (item.rating.isNotBlank()) metaParts.add("⭐ ${item.rating}")
                if (item.votes.isNotBlank()) metaParts.add(item.votes)
                metaView.text = metaParts.joinToString(" · ")
                metaView.visibility = if (metaParts.isEmpty()) View.GONE else View.VISIBLE

                if (item.poster.isNotBlank()) {
                    SimpleImageLoader.load(item.poster, posterView)
                } else {
                    val fallback = "https://images.metahub.space/poster/small/${item.imdbId}/img"
                    SimpleImageLoader.load(fallback, posterView)
                }
            }
        }
    }

    private data class ChartItem(
        val imdbId: String,
        val title: String,
        val rank: Int,
        val rating: String,
        val votes: String,
        val poster: String,
        val type: String
    )
}
