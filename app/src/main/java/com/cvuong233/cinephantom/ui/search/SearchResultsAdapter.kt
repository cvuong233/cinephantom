package com.cvuong233.cinephantom.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.databinding.ItemSearchResultBinding
import com.cvuong233.cinephantom.model.ImdbTitle

class SearchResultsAdapter(
    private val onClick: (ImdbTitle) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_SKELETON = 0
        private const val VIEW_TYPE_RESULT = 1
    }

    private val items = mutableListOf<ImdbTitle>()
    private val pendingRatings = mutableSetOf<String>()
    private var isLoading = false
    private val skeletonCount = 5

    var onStremioClick: ((ImdbTitle) -> Unit)? = null

    fun showLoading() { isLoading = true; notifyDataSetChanged() }
    fun hideLoading() { isLoading = false; notifyDataSetChanged() }

    fun isLoading(): Boolean = isLoading

    fun submitList(newList: List<ImdbTitle>) {
        items.clear()
        items.addAll(newList)
        pendingRatings.clear()
        pendingRatings.addAll(newList.map { it.id })
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

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ResultViewHolder) {
            holder.binding.posterPlaceholder.stopAndHide()
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
            binding.root.setOnClickListener { onClick(item) }

            // Title
            binding.titleText.text = item.title

            // Type + year meta chip
            val meta = listOfNotNull(item.typeLabel, item.year).joinToString(" • ")
            binding.metaText.text = meta
            binding.metaText.visibility = if (meta.isBlank()) View.GONE else View.VISIBLE

            // Rating badge — IMDb style, rolling digits
            val rating = item.rating
            if (rating != null && rating > 0f) {
                binding.ratingBadge.visibility = View.VISIBLE
                binding.ratingBadge.text = "IMDb "
                RatingAnimation.animateRolling(binding.ratingBadge, rating)
            } else if (pendingRatings.contains(item.id)) {
                // Still waiting for rating — show rolling placeholder
                binding.ratingBadge.visibility = View.VISIBLE
                binding.ratingBadge.text = "IMDb --"
                RatingAnimation.startContinuousRoll(binding.ratingBadge)
            } else {
                // Rating fetch completed with no result — hide badge
                binding.ratingBadge.visibility = View.GONE
                RatingAnimation.stop(binding.ratingBadge)
            }

            // Genres — hidden by default
            binding.genresContainer.visibility = View.GONE

            // Cast label
            val hasCast = !item.cast.isNullOrBlank()
            binding.castLabelText.visibility = if (hasCast) View.VISIBLE else View.GONE

            // Cast text
            binding.castText.text = item.cast ?: "Tap to open detail page"
            binding.castText.alpha = if (hasCast) 1f else 0.72f

            // Poster — load manually
            if (item.imageUrl.isNullOrBlank()) {
                binding.posterImage.visibility = View.GONE
                binding.posterPlaceholder.visibility = View.VISIBLE
                binding.posterPlaceholder.startShimmer()
            } else {
                binding.posterPlaceholder.visibility = View.VISIBLE
                binding.posterPlaceholder.startShimmer()
                binding.posterImage.visibility = View.GONE
                binding.posterImage.setImageDrawable(null)
                SimpleImageLoader.load(
                    url = item.imageUrl,
                    imageView = binding.posterImage,
                    onSuccess = {
                        binding.posterImage.visibility = View.VISIBLE
                        binding.posterPlaceholder.stopAndHide()
                    },
                    onError = {
                        binding.posterPlaceholder.stopAndHide()
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
