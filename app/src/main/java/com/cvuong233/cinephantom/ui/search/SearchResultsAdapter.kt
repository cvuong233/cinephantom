package com.cvuong233.cinephantom.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.databinding.ItemSearchResultBinding
import com.cvuong233.cinephantom.model.ImdbTitle

class SearchResultsAdapter(
    private val onClick: (ImdbTitle) -> Unit,
) : RecyclerView.Adapter<SearchResultsAdapter.ResultViewHolder>() {

    private val items = mutableListOf<ImdbTitle>()
    private var isLoading = false

    var onStremioClick: ((ImdbTitle) -> Unit)? = null

    fun showLoading() { isLoading = true; notifyDataSetChanged() }
    fun hideLoading() { isLoading = false; notifyDataSetChanged() }

    fun submitList(newList: List<ImdbTitle>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemSearchResultBinding.inflate(inflater, parent, false)
        return ResultViewHolder(binding)
    }

    override fun getItemCount(): Int = if (isLoading) 5 else items.size

    override fun getItemViewType(position: Int): Int = if (isLoading) 0 else 1

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        if (!isLoading) {
            holder.bind(items[position], position)
        }
    }

    override fun onViewRecycled(holder: ResultViewHolder) {
        super.onViewRecycled(holder)
        holder.binding.posterPlaceholder.stopAndHide()
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
            } else {
                binding.ratingBadge.visibility = View.VISIBLE
                binding.ratingBadge.text = "IMDb --"
                RatingAnimation.startContinuousRoll(binding.ratingBadge)
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
                binding.posterImage.setImageDrawable(null) // clear
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
