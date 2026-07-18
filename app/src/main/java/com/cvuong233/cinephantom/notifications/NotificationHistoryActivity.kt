package com.cvuong233.cinephantom.notifications

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cvuong233.cinephantom.R
import com.cvuong233.cinephantom.data.NotificationHistoryRepository
import com.cvuong233.cinephantom.model.NotificationHistoryItem
import com.cvuong233.cinephantom.ui.detail.DetailActivity
import kotlinx.coroutines.launch

class NotificationHistoryActivity : AppCompatActivity() {

    private lateinit var adapter: NotificationHistoryAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF0D0011.toInt()
        setContentView(R.layout.activity_notification_history)

        findViewById<View>(R.id.toolbar_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.toolbar_title).text = "Notification History"

        recycler = findViewById(R.id.notif_history_recycler)
        emptyText = findViewById(R.id.notif_history_empty_text)

        adapter = NotificationHistoryAdapter(onClick = { openTitle(it) })
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NotificationHistoryRepository.history.collect { items ->
                    if (items.isEmpty()) {
                        emptyText.visibility = View.VISIBLE
                        recycler.visibility = View.GONE
                    } else {
                        emptyText.visibility = View.GONE
                        recycler.visibility = View.VISIBLE
                        adapter.submitList(items)
                    }
                }
            }
        }
    }

    private fun openTitle(item: NotificationHistoryItem) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_IMDB_ID, item.mediaId)
            putExtra(DetailActivity.EXTRA_TITLE, item.title)
            putExtra(DetailActivity.EXTRA_IMAGE_URL, item.posterPath)
        }
        startActivity(intent)
    }
}
