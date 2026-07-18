package com.cvuong233.cinephantom.ui.account

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.search.SimpleImageLoader
import java.util.Locale

class WatchlistGridAdapter(
    private val onClick: (View, ImdbTitle) -> Unit,
) : RecyclerView.Adapter<WatchlistGridAdapter.GridViewHolder>() {

    private val items = mutableListOf<ImdbTitle>()
    private val animated = mutableSetOf<String>()
    private var sequenceStartMs = 0L

    fun submitList(newList: List<ImdbTitle>) {
        val old = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newList.size
            override fun areItemsTheSame(op: Int, np: Int) = old[op].id == newList[np].id
            override fun areContentsTheSame(op: Int, np: Int) = old[op] == newList[np]
        })
        items.clear()
        items.addAll(newList)
        diff.dispatchUpdatesTo(this)
    }

    // Call before submitList() whenever a fresh stagger sequence should play
    // (initial load or filter switch). Each item animates at most once per sequence.
    fun startNewSequence() {
        animated.clear()
        sequenceStartMs = System.currentTimeMillis()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = GridViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_watchlist_grid, parent, false)
    )

    override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
        holder.bind(items[position])
    }

    // Fires after addView() so ViewPropertyAnimator is reliable on an attached view.
    // itemAnimator=null eliminates the pre-layout double-attach that would otherwise
    // cause onViewDetachedFromWindow to cancel mid-flight animations.
    //
    // Delay = (100ms head-start) + (position × 40ms) − elapsed since sequence started.
    // This makes the whole list share one timeline: card 0 at ~100ms, card 8 at ~420ms.
    // Cards scrolled into view during the sequence window join it with their remaining
    // delay; cards whose slot has already passed animate immediately; cards too far
    // ahead (remaining > 600ms) appear instantly so they're never hidden for too long.
    override fun onViewAttachedToWindow(holder: GridViewHolder) {
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        val item = items.getOrNull(pos) ?: return
        if (!animated.add(item.id)) return  // already played in this sequence

        val elapsed = System.currentTimeMillis() - sequenceStartMs
        val remaining = (100L + pos * 40L - elapsed).coerceAtLeast(0L)
        if (remaining > 600L) return  // too far ahead — show instantly, no long blank wait

        holder.itemView.animate().cancel()
        holder.itemView.alpha = 0f
        holder.itemView.scaleX = 0.84f
        holder.itemView.scaleY = 0.84f
        holder.itemView.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(280)
            .setStartDelay(remaining)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    override fun onViewDetachedFromWindow(holder: GridViewHolder) {
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
    }

    inner class GridViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.watchlist_grid_poster)
        private val title: TextView = itemView.findViewById(R.id.watchlist_grid_title)
        private val rating: TextView = itemView.findViewById(R.id.watchlist_grid_rating)

        fun bind(item: ImdbTitle) {
            title.text = item.title

            val rt = item.ratingText?.trim()
            when {
                !rt.isNullOrBlank() -> {
                    rating.text = "★ $rt"
                    rating.visibility = View.VISIBLE
                }
                (item.rating ?: 0f) > 0f -> {
                    rating.text = "★ ${String.format(Locale.US, "%.1f", item.rating)}"
                    rating.visibility = View.VISIBLE
                }
                else -> rating.visibility = View.GONE
            }

            poster.setImageDrawable(null)
            if (!item.imageUrl.isNullOrBlank()) {
                // No crossfade on cards: a fade mid-tap would make the shared-element hero
                // transition capture a half-faded frame. The poster is animated in by the
                // grid's own stagger entrance instead.
                SimpleImageLoader.load(url = item.imageUrl, imageView = poster, crossfade = false)
            }

            ViewCompat.setTransitionName(poster, "poster_${item.id}")
            itemView.setOnClickListener { onClick(poster, item) }
        }
    }
}
