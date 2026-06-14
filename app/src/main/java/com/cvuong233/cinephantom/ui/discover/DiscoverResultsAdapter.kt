package com.cvuong233.cinephantom.ui.discover

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.search.ShimmerView
import com.cvuong233.cinephantom.ui.search.SimpleImageLoader
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class DiscoverResultsAdapter(
    private val skeletonLayoutRes: Int = R.layout.item_discover_skeleton,
    private val showRankLabel: Boolean = true,
    private val showFeaturedMetricLabel: Boolean = false,
    private val onClick: (View, ImdbTitle) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ImdbTitle>()
    private var isLoading = false
    private val skeletonCount = 5
    private val pendingRatings = linkedSetOf<String>()
    private val requestedRatings = linkedSetOf<String>()
    private var highlightId: String? = null

    var onStremioClick: ((ImdbTitle) -> Unit)? = null
    var onRatingNeeded: ((ImdbTitle) -> Unit)? = null

    fun showLoading() {
        isLoading = true
        notifyDataSetChanged()
    }

    fun hideLoading() {
        isLoading = false
        notifyDataSetChanged()
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

    fun requestHighlight(imdbId: String, position: Int) {
        highlightId = imdbId
        if (position in items.indices) {
            notifyItemChanged(position, "highlight")
        }
    }

    override fun getItemCount(): Int = if (isLoading) skeletonCount else items.size

    override fun getItemViewType(position: Int): Int = if (isLoading) VIEW_TYPE_SKELETON else VIEW_TYPE_RESULT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_SKELETON) {
            SkeletonViewHolder(inflater.inflate(skeletonLayoutRes, parent, false))
        } else {
            ResultViewHolder(inflater.inflate(R.layout.item_discover_result, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is SkeletonViewHolder -> holder.bind()
            is ResultViewHolder -> holder.bind(items[position], position)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any>) {
        if (holder is ResultViewHolder && payloads.contains("highlight")) {
            holder.bind(items[position], position)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ResultViewHolder) {
            holder.posterPlaceholder.visibility = View.VISIBLE
            holder.posterImage.visibility = View.GONE
            holder.posterImage.setImageDrawable(null)
        }
        super.onViewRecycled(holder)
    }

    inner class SkeletonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val shimmerPoster: ShimmerView? = itemView.findViewById(R.id.skeleton_poster)
        fun bind() { shimmerPoster?.startShimmer() }
    }

    inner class ResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val rootCard: MaterialCardView = itemView as MaterialCardView
        val posterFrame: View = itemView.findViewById(R.id.poster_frame)
        val posterImage: ImageView = itemView.findViewById(R.id.poster_image)
        val posterPlaceholder: View = itemView.findViewById(R.id.poster_placeholder)
        private val titleText: TextView = itemView.findViewById(R.id.title_text)
        private val ratingBadge: TextView = itemView.findViewById(R.id.rating_badge)
        private val rankText: TextView = itemView.findViewById(R.id.rank_text)
        private val secondaryText: TextView = itemView.findViewById(R.id.secondary_text)
        private val stremioButton: ImageView = itemView.findViewById(R.id.stremio_button)

        fun bind(item: ImdbTitle, position: Int) {
            ViewCompat.setTransitionName(posterImage, "poster_${item.id}")
            itemView.setOnClickListener { onClick(posterImage, item) }

            titleText.text = item.title

            val ratingText = item.ratingText?.trim().orEmpty()
            val ratingSource = item.ratingSourceLabel?.trim().ifNullOrBlank("IMDb")
            val resolvedRatingText = when {
                ratingText.isNotBlank() -> "$ratingSource $ratingText"
                (item.rating ?: 0f) > 0f -> String.format(Locale.US, "$ratingSource %.1f", item.rating)
                else -> ""
            }
            if (resolvedRatingText.isNotBlank()) {
                ratingBadge.text = resolvedRatingText
                ratingBadge.visibility = View.VISIBLE
            } else {
                ratingBadge.visibility = View.GONE
            }

            val rankLabel = item.rankLabel?.trim().orEmpty()
            val featuredMetricLabel = item.featuredMetricLabel?.trim().orEmpty()
            val secondaryLabel = item.secondaryLabel?.trim().orEmpty()
            val imdbStyleRankOnly = item.id.startsWith("fx:")

            if (showFeaturedMetricLabel && featuredMetricLabel.isNotBlank()) {
                rankText.text = featuredMetricLabel
                rankText.visibility = View.VISIBLE
                if (!imdbStyleRankOnly && secondaryLabel.isNotBlank()) {
                    secondaryText.text = secondaryLabel
                    secondaryText.visibility = View.VISIBLE
                } else {
                    secondaryText.visibility = View.GONE
                }
            } else if (showRankLabel && rankLabel.isNotBlank()) {
                rankText.text = rankLabel
                rankText.visibility = View.VISIBLE
                secondaryText.visibility = View.GONE
            } else if (secondaryLabel.isNotBlank()) {
                secondaryText.text = secondaryLabel
                secondaryText.visibility = View.VISIBLE
                rankText.visibility = View.GONE
            } else {
                rankText.visibility = View.GONE
                secondaryText.visibility = View.GONE
            }

            if (pendingRatings.contains(item.id) && requestedRatings.add(item.id)) {
                onRatingNeeded?.invoke(item)
            }

            stremioButton.setOnClickListener { onStremioClick?.invoke(item) }

            val imageUrl = item.imageUrl
            if (imageUrl.isNullOrBlank()) {
                posterImage.visibility = View.GONE
                posterPlaceholder.visibility = View.VISIBLE
            } else {
                posterPlaceholder.visibility = View.VISIBLE
                posterImage.visibility = View.GONE
                posterImage.setImageDrawable(null)
                SimpleImageLoader.load(
                    url = imageUrl,
                    imageView = posterImage,
                    onSuccess = {
                        posterImage.visibility = View.VISIBLE
                        posterPlaceholder.visibility = View.GONE
                    },
                    onError = {
                        posterImage.visibility = View.GONE
                        posterPlaceholder.visibility = View.VISIBLE
                    }
                )
            }

            if (item.id == highlightId) {
                highlightId = null
                val baseStroke = itemView.context.getColor(R.color.surface_border)
                val glowStroke = itemView.context.getColor(R.color.neon_pink)
                itemView.post {
                    itemView.animate().cancel()
                    posterFrame.animate().cancel()
                    rootCard.strokeWidth = 3
                    rootCard.strokeColor = glowStroke
                    itemView.alpha = 0.82f
                    itemView.scaleX = 0.976f
                    itemView.scaleY = 0.976f
                    posterFrame.scaleX = 0.988f
                    posterFrame.scaleY = 0.988f
                    itemView.animate().alpha(1f).scaleX(1.012f).scaleY(1.012f).setDuration(220)
                        .withEndAction { itemView.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }.start()
                    posterFrame.animate().scaleX(1.016f).scaleY(1.016f).setDuration(220)
                        .withEndAction { posterFrame.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }.start()
                    ObjectAnimator.ofArgb(rootCard, "strokeColor", glowStroke, baseStroke).apply {
                        duration = 620; start()
                    }
                    itemView.postDelayed({
                        rootCard.strokeWidth = 1
                        rootCard.strokeColor = baseStroke
                        itemView.alpha = 1f; itemView.scaleX = 1f; itemView.scaleY = 1f
                        posterFrame.scaleX = 1f; posterFrame.scaleY = 1f
                    }, 680)
                }
            }
        }
    }

    companion object {
        private const val VIEW_TYPE_SKELETON = 0
        private const val VIEW_TYPE_RESULT = 1
    }
}

private fun String?.ifNullOrBlank(default: String) = if (isNullOrBlank()) default else this!!
