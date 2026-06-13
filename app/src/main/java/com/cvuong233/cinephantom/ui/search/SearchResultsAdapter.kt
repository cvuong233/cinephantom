package com.cvuong233.cinephantom.ui.search

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.FavoritesRepository
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

    var onStremioClick: ((ImdbTitle) -> Unit)? = null
    var onFavoriteClick: ((ImdbTitle) -> Unit)? = null
    var onRatingNeeded: ((ImdbTitle) -> Unit)? = null

    fun showLoading() { isLoading = true; notifyDataSetChanged() }
    fun hideLoading() { isLoading = false; notifyDataSetChanged() }

    fun isLoading(): Boolean = isLoading

    fun notifyFavoriteChanged(imdbId: String) {
        val idx = items.indexOfFirst { it.id == imdbId }
        if (idx >= 0) notifyItemChanged(idx, "favorite")
    }

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
        if (holder is ResultViewHolder && payloads.contains("favorite")) {
            val isFav = FavoritesRepository.isFavorite(items[position].id)
            holder.binding.favoriteButton.setImageResource(if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            return
        }
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
        private val shimmerPoster = itemView.findViewById<ShimmerView>(R.id.skeleton_poster)
        fun bind() { shimmerPoster?.startShimmer() }
    }

    inner class ResultViewHolder(
        val binding: ItemSearchResultBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ImdbTitle, position: Int) {
            ViewCompat.setTransitionName(binding.posterImage, "poster_${item.id}")
            binding.root.setOnClickListener { onClick(binding.posterImage, item) }

            binding.titleText.text = item.title

            val ratingText = item.ratingText?.trim().orEmpty()
            val baseStroke = binding.root.context.getColor(R.color.surface_border)
            val glowStroke = binding.root.context.getColor(R.color.neon_pink)

            (binding.root as? MaterialCardView)?.apply {
                strokeWidth = 1
                strokeColor = baseStroke
            }

            when {
                ratingText.isNotBlank() -> {
                    val source = item.ratingSourceLabel?.takeIf { it.isNotBlank() } ?: "IMDb"
                    binding.ratingBadge.text = "$source $ratingText"
                    binding.ratingBadge.visibility = View.VISIBLE
                }
                (item.rating ?: 0f) > 0f -> {
                    binding.ratingBadge.text = String.format(java.util.Locale.US, "IMDb %.1f", item.rating)
                    binding.ratingBadge.visibility = View.VISIBLE
                }
                pendingRatings.contains(item.id) -> {
                    binding.ratingBadge.text = "IMDb --"
                    binding.ratingBadge.visibility = View.VISIBLE
                    if (requestedRatings.add(item.id)) onRatingNeeded?.invoke(item)
                }
                else -> binding.ratingBadge.visibility = View.GONE
            }

            binding.rankText.visibility = View.GONE
            binding.secondaryText.visibility = View.GONE

            if (item.id == highlightId) {
                highlightId = null
                binding.root.post {
                    val card = binding.root as? MaterialCardView
                    binding.root.animate().cancel()
                    binding.posterFrame.animate().cancel()

                    binding.root.alpha = 0.78f
                    binding.root.scaleX = 0.972f
                    binding.root.scaleY = 0.972f
                    binding.posterFrame.scaleX = 0.984f
                    binding.posterFrame.scaleY = 0.984f
                    binding.posterFrame.alpha = 0.9f
                    binding.ratingBadge.alpha = 0.72f
                    card?.strokeWidth = 3
                    card?.strokeColor = glowStroke

                    binding.root.animate().alpha(1f).scaleX(1.012f).scaleY(1.012f).setDuration(220)
                        .withEndAction { binding.root.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }
                        .start()
                    binding.posterFrame.animate().alpha(1f).scaleX(1.018f).scaleY(1.018f).setDuration(220)
                        .withEndAction { binding.posterFrame.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }
                        .start()
                    binding.ratingBadge.animate().alpha(1f).setDuration(260).start()
                    ObjectAnimator.ofArgb(card, "strokeColor", glowStroke, baseStroke).apply {
                        duration = 620; start()
                    }
                    binding.root.postDelayed({
                        card?.strokeWidth = 1
                        card?.strokeColor = baseStroke
                        binding.root.alpha = 1f; binding.root.scaleX = 1f; binding.root.scaleY = 1f
                        binding.posterFrame.alpha = 1f; binding.posterFrame.scaleX = 1f; binding.posterFrame.scaleY = 1f
                        binding.ratingBadge.alpha = 1f
                    }, 680)
                }
            }

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

            binding.stremioButton.visibility = View.VISIBLE
            binding.stremioButton.setOnClickListener { onStremioClick?.invoke(item) }

            val isFav = FavoritesRepository.isFavorite(item.id)
            binding.favoriteButton.setImageResource(if (isFav) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)
            binding.favoriteButton.setOnClickListener { onFavoriteClick?.invoke(item) }
        }
    }
}
