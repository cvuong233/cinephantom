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
    private val onClick: (View, ImdbTitle) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ImdbTitle>()
    private var isLoading = false
    private val skeletonCount = 5
    private val pendingRatings = linkedSetOf<String>()
    private val requestedRatings = linkedSetOf<String>()
    private var highlightId: String? = null

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
            // Keep the shared-element ImageView laid out (its placeholder background shows through)
            // so a tap on a recycled/reloading card still has a valid view for the hero transition.
            holder.backdropPlaceholder.visibility = View.GONE
            holder.backdropImage.visibility = View.VISIBLE
            holder.backdropImage.setImageDrawable(null)
        }
        super.onViewRecycled(holder)
    }

    inner class SkeletonViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val shimmerPoster: ShimmerView? = itemView.findViewById(R.id.skeleton_poster)
        fun bind() { shimmerPoster?.startShimmer() }
    }

    inner class ResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val rootCard: MaterialCardView = itemView as MaterialCardView
        val backdropFrame: View = itemView.findViewById(R.id.backdrop_frame)
        val backdropImage: ImageView = itemView.findViewById(R.id.backdrop_image)
        val backdropPlaceholder: View = itemView.findViewById(R.id.backdrop_placeholder)
        private val titleText: TextView = itemView.findViewById(R.id.title_text)
        private val ratingBadge: TextView = itemView.findViewById(R.id.rating_badge)
        private val rankText: TextView = itemView.findViewById(R.id.rank_text)

        fun bind(item: ImdbTitle, position: Int) {
            ViewCompat.setTransitionName(backdropImage, "backdrop_${item.id}")
            itemView.setOnClickListener { onClick(backdropImage, item) }

            titleText.text = item.title

            val ratingText = item.ratingText?.trim().orEmpty()
            when {
                ratingText.isNotBlank() -> {
                    ratingBadge.text = "★ $ratingText"
                    ratingBadge.visibility = View.VISIBLE
                }
                (item.rating ?: 0f) > 0f -> {
                    ratingBadge.text = "★ " + String.format(Locale.US, "%.1f", item.rating)
                    ratingBadge.visibility = View.VISIBLE
                }
                pendingRatings.contains(item.id) -> {
                    ratingBadge.text = "★ --"
                    ratingBadge.visibility = View.VISIBLE
                }
                else -> ratingBadge.visibility = View.GONE
            }

            val rankLabel = item.rankLabel?.trim().orEmpty()
            if (showRankLabel && rankLabel.isNotBlank()) {
                rankText.text = rankLabel
                rankText.visibility = View.VISIBLE
            } else {
                rankText.visibility = View.GONE
            }

            if (pendingRatings.contains(item.id) && requestedRatings.add(item.id)) {
                onRatingNeeded?.invoke(item)
            }

            // The backdrop ImageView stays VISIBLE at all times (its placeholder-background shows
            // while the bitmap loads). makeSceneTransitionAnimation captures the tapped view, and a
            // GONE view has no bounds to fly — that was why some mid-load taps missed the hero
            // transition. Keeping it laid out guarantees a valid shared element on every tap.
            backdropPlaceholder.visibility = View.GONE
            backdropImage.visibility = View.VISIBLE
            backdropImage.setImageDrawable(null)
            val imageUrl = item.landscapeImageUrl
            if (!imageUrl.isNullOrBlank()) {
                SimpleImageLoader.loadBackdrop(
                    url = imageUrl,
                    imageView = backdropImage,
                    // No crossfade on cards: a fade playing at the instant the user taps makes the
                    // shared-element transition capture a half-faded frame, so the hero flight
                    // starts from a blank/wrong state. The bitmap just appears (still animated in
                    // by the card's own entrance/highlight animations).
                    crossfade = false,
                    onError = { backdropImage.setImageDrawable(null) }
                )
            }

            if (item.id == highlightId) {
                highlightId = null
                val baseStroke = itemView.context.getColor(R.color.surface_border)
                val glowStroke = itemView.context.getColor(R.color.neon_pink)
                itemView.post {
                    itemView.animate().cancel()
                    backdropFrame.animate().cancel()
                    rootCard.strokeWidth = 3
                    rootCard.strokeColor = glowStroke
                    itemView.alpha = 0.82f
                    itemView.scaleX = 0.976f
                    itemView.scaleY = 0.976f
                    backdropFrame.scaleX = 0.988f
                    backdropFrame.scaleY = 0.988f
                    itemView.animate().alpha(1f).scaleX(1.012f).scaleY(1.012f).setDuration(220)
                        .withEndAction { itemView.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }.start()
                    backdropFrame.animate().scaleX(1.016f).scaleY(1.016f).setDuration(220)
                        .withEndAction { backdropFrame.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }.start()
                    ObjectAnimator.ofArgb(rootCard, "strokeColor", glowStroke, baseStroke).apply {
                        duration = 620; start()
                    }
                    itemView.postDelayed({
                        rootCard.strokeWidth = 1
                        rootCard.strokeColor = baseStroke
                        itemView.alpha = 1f; itemView.scaleX = 1f; itemView.scaleY = 1f
                        backdropFrame.scaleX = 1f; backdropFrame.scaleY = 1f
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
