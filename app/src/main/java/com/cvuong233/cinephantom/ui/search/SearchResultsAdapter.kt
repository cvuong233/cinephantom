package com.cvuong233.cinephantom.ui.search

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.databinding.ItemSearchResultBinding
import com.cvuong233.cinephantom.model.ImdbTitle
import com.google.android.material.card.MaterialCardView

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

    var onRatingNeeded: ((ImdbTitle) -> Unit)? = null

    fun showLoading() { isLoading = true; notifyDataSetChanged() }
    fun hideLoading() { isLoading = false; notifyDataSetChanged() }

    fun isLoading(): Boolean = isLoading

    fun requestHighlight(imdbId: String, position: Int) {
        highlightId = imdbId
        if (position in items.indices) notifyItemChanged(position, "highlight")
    }

    fun submitList(newList: List<ImdbTitle>) {
        if (isLoading) {
            // Switching from skeleton → real items: reset cleanly with no animations
            isLoading = false
            items.clear()
            items.addAll(newList)
            pendingRatings.clear()
            requestedRatings.clear()
            pendingRatings.addAll(newList.filter { it.rating == null || it.rating <= 0f }.map { it.id })
            notifyDataSetChanged()
            return
        }
        val oldList = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldList.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(op: Int, np: Int) = oldList[op].id == newList[np].id
            override fun areContentsTheSame(op: Int, np: Int) = oldList[op] == newList[np]
        })
        items.clear()
        items.addAll(newList)
        pendingRatings.clear()
        requestedRatings.clear()
        pendingRatings.addAll(newList.filter { it.rating == null || it.rating <= 0f }.map { it.id })
        diff.dispatchUpdatesTo(this)
    }

    fun updateRating(updated: ImdbTitle) {
        val index = items.indexOfFirst { it.id == updated.id }
        if (index >= 0) {
            items[index] = updated
            notifyItemChanged(index, "rating")
            pendingRatings.remove(updated.id)
        }
    }

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
            VIEW_TYPE_SKELETON -> SkeletonViewHolder(inflater.inflate(R.layout.item_search_skeleton, parent, false))
            else -> ResultViewHolder(ItemSearchResultBinding.inflate(inflater, parent, false))
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
            holder.binding.backdropPlaceholder.visibility = View.VISIBLE
            holder.binding.backdropImage.visibility = View.GONE
        }
    }

    class SkeletonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val shimmerPoster = itemView.findViewById<ShimmerView>(R.id.skeleton_poster)
        fun bind() { shimmerPoster?.startShimmer() }
    }

    inner class ResultViewHolder(
        val binding: ItemSearchResultBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ImdbTitle, position: Int) {
            ViewCompat.setTransitionName(binding.backdropImage, "backdrop_${item.id}")
            binding.root.setOnClickListener { onClick(binding.backdropImage, item) }

            binding.titleText.text = item.title

            val baseStroke = binding.root.context.getColor(R.color.surface_border)
            val glowStroke = binding.root.context.getColor(R.color.neon_pink)

            (binding.root as? MaterialCardView)?.apply {
                strokeWidth = 1
                strokeColor = baseStroke
            }

            val ratingText = item.ratingText?.trim().orEmpty()
            when {
                ratingText.isNotBlank() -> {
                    binding.ratingBadge.text = "★ $ratingText"
                    binding.ratingBadge.visibility = View.VISIBLE
                }
                (item.rating ?: 0f) > 0f -> {
                    binding.ratingBadge.text = "★ " + String.format(java.util.Locale.US, "%.1f", item.rating)
                    binding.ratingBadge.visibility = View.VISIBLE
                }
                pendingRatings.contains(item.id) -> {
                    binding.ratingBadge.text = "★ --"
                    binding.ratingBadge.visibility = View.VISIBLE
                    if (requestedRatings.add(item.id)) onRatingNeeded?.invoke(item)
                }
                else -> binding.ratingBadge.visibility = View.GONE
            }

            if (item.id == highlightId) {
                highlightId = null
                binding.root.post {
                    val card = binding.root as? MaterialCardView
                    binding.root.animate().cancel()
                    binding.backdropFrame.animate().cancel()

                    binding.root.alpha = 0.78f
                    binding.root.scaleX = 0.972f
                    binding.root.scaleY = 0.972f
                    binding.backdropFrame.scaleX = 0.984f
                    binding.backdropFrame.scaleY = 0.984f
                    binding.backdropFrame.alpha = 0.9f
                    binding.ratingBadge.alpha = 0.72f
                    card?.strokeWidth = 3
                    card?.strokeColor = glowStroke

                    binding.root.animate().alpha(1f).scaleX(1.012f).scaleY(1.012f).setDuration(220)
                        .withEndAction { binding.root.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }
                        .start()
                    binding.backdropFrame.animate().alpha(1f).scaleX(1.018f).scaleY(1.018f).setDuration(220)
                        .withEndAction { binding.backdropFrame.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }
                        .start()
                    binding.ratingBadge.animate().alpha(1f).setDuration(260).start()
                    ObjectAnimator.ofArgb(card, "strokeColor", glowStroke, baseStroke).apply {
                        duration = 620; start()
                    }
                    binding.root.postDelayed({
                        card?.strokeWidth = 1
                        card?.strokeColor = baseStroke
                        binding.root.alpha = 1f; binding.root.scaleX = 1f; binding.root.scaleY = 1f
                        binding.backdropFrame.alpha = 1f; binding.backdropFrame.scaleX = 1f; binding.backdropFrame.scaleY = 1f
                        binding.ratingBadge.alpha = 1f
                    }, 680)
                }
            }

            val imageUrl = item.landscapeImageUrl
            if (imageUrl.isNullOrBlank()) {
                binding.backdropImage.visibility = View.GONE
                binding.backdropPlaceholder.visibility = View.VISIBLE
            } else {
                binding.backdropPlaceholder.visibility = View.VISIBLE
                binding.backdropImage.visibility = View.GONE
                binding.backdropImage.setImageDrawable(null)
                SimpleImageLoader.loadBackdrop(
                    url = imageUrl,
                    imageView = binding.backdropImage,
                    onSuccess = {
                        binding.backdropImage.visibility = View.VISIBLE
                        binding.backdropPlaceholder.visibility = View.GONE
                    },
                    onError = {
                        binding.backdropImage.visibility = View.GONE
                        binding.backdropPlaceholder.visibility = View.VISIBLE
                    },
                )
            }
        }
    }
}
