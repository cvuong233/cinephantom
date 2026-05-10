package com.cvuong233.cinephantom.ui.search

import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
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
            val context = binding.root.context
            val baseStroke = context.getColor(R.color.surface_border)
            val glowStroke = context.getColor(R.color.neon_pink)
            val baseMetaBg = context.getColor(R.color.imdb_yellow)
            val pulseMetaBg = context.getColor(R.color.neon_pink)
            binding.metaText.setTextColor(baseMetaBg)
            binding.metaText.background = context.getDrawable(R.drawable.bg_rating_badge_pill)?.mutate()
            binding.metaText.background?.setTint(baseMetaBg)
            (binding.root as? MaterialCardView)?.apply {
                strokeWidth = 1
                strokeColor = baseStroke
            }
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
                    val card = binding.root as? MaterialCardView
                    binding.root.animate().cancel()
                    binding.posterFrame.animate().cancel()
                    binding.metaText.background = context.getDrawable(R.drawable.bg_rating_badge_pill)?.mutate()
                    binding.metaText.background?.setTint(baseMetaBg)

                    binding.root.alpha = 0.86f
                    binding.root.scaleX = 0.972f
                    binding.root.scaleY = 0.972f
                    binding.posterFrame.scaleX = 0.985f
                    binding.posterFrame.scaleY = 0.985f
                    binding.posterFrame.alpha = 0.92f
                    card?.strokeWidth = 2
                    card?.strokeColor = glowStroke
                    binding.metaText.background?.setTint(pulseMetaBg)

                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(binding.root, View.ALPHA, 0.86f, 1f),
                            ObjectAnimator.ofFloat(binding.root, View.SCALE_X, 0.972f, 1.012f, 1f),
                            ObjectAnimator.ofFloat(binding.root, View.SCALE_Y, 0.972f, 1.012f, 1f),
                            ObjectAnimator.ofFloat(binding.posterFrame, View.SCALE_X, 0.985f, 1.02f, 1f),
                            ObjectAnimator.ofFloat(binding.posterFrame, View.SCALE_Y, 0.985f, 1.02f, 1f),
                            ObjectAnimator.ofFloat(binding.posterFrame, View.ALPHA, 0.92f, 1f),
                            ValueAnimator.ofObject(ArgbEvaluator(), glowStroke, baseStroke).apply {
                                addUpdateListener { card?.strokeColor = it.animatedValue as Int }
                            },
                            ValueAnimator.ofObject(ArgbEvaluator(), pulseMetaBg, baseMetaBg).apply {
                                addUpdateListener { binding.metaText.background?.setTint(it.animatedValue as Int) }
                            }
                        )
                        duration = 680
                        start()
                    }

                    binding.root.postDelayed({
                        card?.strokeWidth = 1
                        card?.strokeColor = baseStroke
                        binding.root.alpha = 1f
                        binding.root.scaleX = 1f
                        binding.root.scaleY = 1f
                        binding.posterFrame.alpha = 1f
                        binding.posterFrame.scaleX = 1f
                        binding.posterFrame.scaleY = 1f
                        binding.metaText.background = context.getDrawable(R.drawable.bg_rating_badge_pill)?.mutate()
                        binding.metaText.background?.setTint(baseMetaBg)
                    }, 720)
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
