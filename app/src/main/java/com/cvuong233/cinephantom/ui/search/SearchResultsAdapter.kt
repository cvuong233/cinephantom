package com.cvuong233.cinephantom.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.databinding.ItemSearchResultBinding
import com.cvuong233.cinephantom.model.ImdbTitle

class SearchResultsAdapter(
    private val onClick: (ImdbTitle) -> Unit,
) : RecyclerView.Adapter<SearchResultsAdapter.ResultViewHolder>() {

    private val items = mutableListOf<ImdbTitle>()

    fun submitList(newItems: List<ImdbTitle>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemSearchResultBinding.inflate(inflater, parent, false)
        return ResultViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ResultViewHolder(
        private val binding: ItemSearchResultBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ImdbTitle) {
            binding.titleText.text = item.title
            binding.metaText.text = listOfNotNull(item.typeLabel, item.year).joinToString(" • ")
            binding.castText.text = item.cast ?: "Tap to open official IMDb page"
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}
