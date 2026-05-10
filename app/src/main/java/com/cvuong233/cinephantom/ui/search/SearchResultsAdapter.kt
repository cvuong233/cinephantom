package com.cvuong233.cinephantom.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.databinding.ItemSearchResultBinding
import com.cvuong233.cinephantom.model.ImdbTitle

class SearchResultsAdapter(
    private val onClick: (View, ImdbTitle) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_SKELETON = 0
        private const val VIEW_TYPE_RESULT = 1
    }

    private val items = mutableListOf<ImdbTitle>()
    private val pendingRatings = mutableSetOf<String>()
    private val requestedRatings = mutableSetOf<String>()
    private var isLoading = false
    private val skeletonCount = 5
    private var highlightId: String? = null

    var onStremioClick: ((ImdbTitle) -> Unit)? = null
    var onRatingNeeded: ((ImdbTitle) -> Unit)? = null

    fun showLoading() { isLoading = true; notifyDataSetChanged() }
    fun hideLoading() { isLoading = false; notifyDataSetChanged() }

    fun isLoading(): Boolean = isLoading

    fun requestHighlight(imdbId: String, position: Int) {
        highlightId = imdbId
        if (position in items.indices) notifyItemChanged(position, "highlight")
    }

    fun submitList(newList: List<ImdbTitle>) {
        items.clear()
        items.addAll(newList)
        pendingRatings.clear()
        requestedRatings.clear()
        pendingRatings.addAll(newList.filter { it.rating == null || it.rating <= 0f }.map { it.id })
        notifyDataSetChanged()
    }

    /**
     * Update a single item's rating when fetched asynchronously.
     */
    fun updateRating(updated: ImdbTitle) {
        val index = items.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            items[index] = updated
            notifyItemChanged(index, "rating")
            pendingRatings.remove(updated.id)
        }
    }

    /**
     * Called when all pending rating fetches are done.
     * Finalizes any remaining "---" badges to show nothing instead of rolling forever.
     */
    fun onRatingFetchDone() {
        pendingRatings.clear()
        notifyItemRangeChanged(0, items.size, "rating_done")
    }

    override fun getItemCount(): Int = if (isLoading) skeletonCount else items.size

    override fun getItemViewType(position: Int): Int =
        if (isLoading) VIEW_TYPE_SKELETON else VIEW_TYPE_RESULT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_SKELETON -> {
                val view = inflater.inflate(R.layout.item_search_skeleton, parent, false)
                SkeletonViewHolder(view)
            }
            else -> {
                val binding = ItemSearchResultBinding.inflate(inflater, parent, false)
                ResultViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SkeletonViewHolder -> holder.bind()
            is ResultViewHolder -> holder.bind(items[position], position)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (holder is ResultViewHolder && payloads.contains("highlight")) {
            holder.bind(items[position], position)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ResultViewHolder) {
            holder.binding.posterPlaceholder.visibility = View.VISIBLE
            holder.binding.posterImage.visibility = View.GONE
        }
    }

    class SkeletonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val shimmerPoster = itemView.findViewById<com.cvuong233.cinephantom.ui.search.ShimmerView>(R.id.skeleton_poster)

        fun bind() {
            shimmerPoster?.startShimmer()
        }
    }

    inner class ResultViewHolder(
        val binding: ItemSearchResultBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ImdbTitle, position: Int) {
            ViewCompat.setTransitionName(binding.posterImage, "poster_${item.id}")
            binding.root.setOnClickListener { onClick(binding.posterImage, item) }

            // Title
            binding.titleText.text = item.title

            val rating = item.rating
            val ratingText = item.ratingText?.trim().orEmpty()
            binding.metaText.setTextColor(binding.root.context.getColor(R.color.imdb_yellow))
            binding.metaText.background = binding.root.context.getDrawable(R.drawable.bg_rating_badge_pill)
            if (ratingText.isNotBlank()) {
                binding.metaText.text = "IMDb $ratingText"
                binding.metaText.visibility = View.VISIBLE
            } else if (rating != null && rating > 0f) {
                binding.metaText.text = String.format(java.util.Locale.US, "IMDb %.1f", rating)
                binding.metaText.visibility = View.VISIBLE
            } else if (pendingRatings.contains(item.id)) {
                binding.metaText.text = "IMDb --"
                binding.metaText.visibility = View.VISIBLE
                if (requestedRatings.add(item.id)) {
                    onRatingNeeded?.invoke(item)
                }
            } else {
                binding.metaText.visibility = View.GONE
            }

            val rankLabel = item.rankLabel?.trim().orEmpty()
            if (rankLabel.isNotBlank()) {
                binding.rankText.text = rankLabel
                binding.rankText.visibility = View.VISIBLE
            } else {
                binding.rankText.visibility = View.GONE
            }

            binding.ratingBadge.visibility = View.GONE

            if (item.id == highlightId) {
                highlightId = null
                binding.root.post {
                    binding.root.animate().cancel()
                    binding.root.alpha = 0.5f
                    binding.root.scaleX = 0.985f
                    binding.root.scaleY = 0.985f
                    binding.root.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(420)
                        .start()
                }
            }

            // Poster — load manually
            if (item.imageUrl.isNullOrBlank()) {
                binding.posterImage.visibility = View.GONE
                binding.posterPlaceholder.visibility = View.VISIBLE
            } else {
                binding.posterPlaceholder.visibility = View.VISIBLE
                binding.posterImage.visibility = View.GONE
                binding.posterImage.setImageDrawable(null)
                SimpleImageLoader.load(
                    url = item.imageUrl,
                    imageView = binding.posterImage,
                    onSuccess = {
                        binding.posterImage.visibility = View.VISIBLE
                        binding.posterPlaceholder.visibility = View.GONE
                    },
                    onError = {
                        binding.posterImage.visibility = View.GONE
                        binding.posterPlaceholder.visibility = View.VISIBLE
                    },
                )
            }

            // Stremio button
            binding.stremioButton.visibility = View.VISIBLE
            binding.stremioButton.setOnClickListener {
                onStremioClick?.invoke(item)
            }


        }
    }
}
