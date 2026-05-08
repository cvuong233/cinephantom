package com.cvuong233.cinephantom.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "watchlist")
data class WatchlistItem(
    @PrimaryKey val imdbId: String,
    val title: String,
    val type: String,      // "movie" or "series"
    val year: String?,
    val imageUrl: String?,
    val cast: String?,
    val addedAt: Long = System.currentTimeMillis(),
    val watchedAt: Long? = null,  // null = not watched, timestamp when watched
    val userRating: Float? = null  // 1.0–5.0, user's personal rating
)
