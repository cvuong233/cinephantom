package com.cvuong233.cinephantom.notifications

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.model.NotificationHistoryItem
import com.cvuong233.cinephantom.ui.search.SimpleImageLoader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class NotificationHistoryAdapter(
    private val onClick: (NotificationHistoryItem) -> Unit,
) : RecyclerView.Adapter<NotificationHistoryAdapter.ViewHolder>() {

    private val items = mutableListOf<NotificationHistoryItem>()

    fun submitList(newList: List<NotificationHistoryItem>) {
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

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_notification_history, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val poster: ImageView = itemView.findViewById(R.id.notif_history_poster)
        private val title: TextView = itemView.findViewById(R.id.notif_history_title)
        private val message: TextView = itemView.findViewById(R.id.notif_history_message)
        private val time: TextView = itemView.findViewById(R.id.notif_history_time)

        fun bind(item: NotificationHistoryItem) {
            title.text = item.title
            message.text = item.message
            time.text = formatFiredAt(item.firedAt)

            poster.setImageDrawable(null)
            if (!item.posterPath.isNullOrBlank()) {
                SimpleImageLoader.load(url = item.posterPath, imageView = poster, crossfade = false)
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }

    private fun formatFiredAt(millis: Long): String {
        val zone = ZoneId.systemDefault()
        val dateTime = Instant.ofEpochMilli(millis).atZone(zone)
        val today = LocalDate.now(zone)
        val timeStr = dateTime.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
        return when (dateTime.toLocalDate()) {
            today -> "Today, $timeStr"
            today.minusDays(1) -> "Yesterday, $timeStr"
            else -> dateTime.format(DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US))
        }
    }
}
