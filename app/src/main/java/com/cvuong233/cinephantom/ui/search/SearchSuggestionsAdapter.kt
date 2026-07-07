package com.cvuong233.cinephantom.ui.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.model.ImdbTitle

sealed class SearchSuggestion {
    object ClearHistoryHeader : SearchSuggestion()
    data class History(val query: String) : SearchSuggestion()
    data class Autocomplete(val title: ImdbTitle) : SearchSuggestion()
}

class SearchSuggestionsAdapter(
    private val onSuggestionClick: (SearchSuggestion) -> Unit,
    private val onClearHistory: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<SearchSuggestion>()

    fun submitList(newItems: List<SearchSuggestion>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is SearchSuggestion.ClearHistoryHeader -> VIEW_TYPE_HEADER
        is SearchSuggestion.History -> VIEW_TYPE_ROW
        is SearchSuggestion.Autocomplete -> VIEW_TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderViewHolder(inflater.inflate(R.layout.item_search_suggestion_header, parent, false))
        } else {
            SuggestionViewHolder(inflater.inflate(R.layout.item_search_suggestion, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SearchSuggestion.ClearHistoryHeader -> (holder as HeaderViewHolder).bind()
            is SearchSuggestion.History -> (holder as SuggestionViewHolder).bindHistory(item)
            is SearchSuggestion.Autocomplete -> (holder as SuggestionViewHolder).bindAutocomplete(item)
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind() {
            itemView.findViewById<TextView>(R.id.suggestion_clear_history)
                .setOnClickListener { onClearHistory() }
        }
    }

    inner class SuggestionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.suggestion_icon)
        private val title: TextView = itemView.findViewById(R.id.suggestion_title)
        private val subtitle: TextView = itemView.findViewById(R.id.suggestion_subtitle)

        fun bindHistory(item: SearchSuggestion.History) {
            icon.setImageResource(R.drawable.ic_history)
            title.text = item.query
            subtitle.visibility = View.GONE
            itemView.setOnClickListener { onSuggestionClick(item) }
        }

        fun bindAutocomplete(item: SearchSuggestion.Autocomplete) {
            icon.setImageResource(R.drawable.ic_search)
            title.text = item.title.title
            val typeBadge = if (item.title.typeLabel?.contains("TV", ignoreCase = true) == true) "TV" else "Movie"
            val parts = listOfNotNull(
                item.title.year?.trim()?.takeIf { it.isNotBlank() },
                typeBadge
            )
            if (parts.isNotEmpty()) {
                subtitle.text = parts.joinToString(" • ")
                subtitle.visibility = View.VISIBLE
            } else {
                subtitle.visibility = View.GONE
            }
            itemView.setOnClickListener { onSuggestionClick(item) }
        }
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_ROW = 1
    }
}
