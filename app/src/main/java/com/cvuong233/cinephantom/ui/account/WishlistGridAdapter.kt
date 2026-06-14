package com.cvuong233.cinephantom.ui.account

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.model.ImdbTitle
import com.cvuong233.cinephantom.ui.search.SimpleImageLoader
import java.util.Locale

class WishlistGridAdapter(
    private val onClick: (ImdbTitle) -> Unit,
) : RecyclerView.Adapter<WishlistGridAdapter.GridViewHolder>() {

    private val items = mutableListOf<ImdbTitle>()
    private val animated = mutableSetOf<String>()

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

    fun clearAnimationState() { animated.clear() }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = GridViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_wishlist_grid, parent, false)
    )

    override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
        val item = items[position]
        // Cancel any in-flight animation from a recycled view before starting a new one.
        holder.itemView.animate().cancel()
        if (animated.add(item.id)) {
            // First time this item is shown — staggered entrance. All 9 visible items bind
            // in the same layout pass so setStartDelay creates the visible cascade.
            val delay = (position % 9) * 65L
            holder.itemView.alpha = 0f
            holder.itemView.scaleX = 0.84f
            holder.itemView.scaleY = 0.84f
            holder.itemView.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(280)
                .setStartDelay(delay)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        } else {
            holder.itemView.alpha = 1f
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
        }
        holder.bind(item)
    }

    override fun onViewDetachedFromWindow(holder: GridViewHolder) {
        // Reset to fully visible so recycled views never leak a partial-animation state.
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
    }

    inner class GridViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.wishlist_grid_poster)
        private val title: TextView = itemView.findViewById(R.id.wishlist_grid_title)
        private val rating: TextView = itemView.findViewById(R.id.wishlist_grid_rating)

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
                SimpleImageLoader.load(url = item.imageUrl, imageView = poster)
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }
}
