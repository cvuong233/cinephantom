package com.cvuong233.cinephantom.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.cvuong233.cinephantom.model.WatchlistItem
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlist WHERE watchedAt IS NULL ORDER BY addedAt DESC")
    fun getToWatch(): Flow<List<WatchlistItem>>

    @Query("SELECT * FROM watchlist WHERE watchedAt IS NOT NULL ORDER BY watchedAt DESC")
    fun getWatched(): Flow<List<WatchlistItem>>

    @Query("SELECT * FROM watchlist ORDER BY addedAt DESC")
    fun getAll(): Flow<List<WatchlistItem>>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE imdbId = :imdbId)")
    suspend fun isSaved(imdbId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WatchlistItem)

    @Query("UPDATE watchlist SET watchedAt = :watchedAt WHERE imdbId = :imdbId")
    suspend fun markWatched(imdbId: String, watchedAt: Long = System.currentTimeMillis())

    @Query("UPDATE watchlist SET watchedAt = NULL WHERE imdbId = :imdbId")
    suspend fun markUnwatched(imdbId: String)

    @Query("DELETE FROM watchlist WHERE imdbId = :imdbId")
    suspend fun delete(imdbId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE imdbId = :imdbId AND watchedAt IS NOT NULL)")
    suspend fun isWatched(imdbId: String): Boolean

    @Query("UPDATE watchlist SET userRating = :rating WHERE imdbId = :imdbId")
    suspend fun updateRating(imdbId: String, rating: Float?)

    @Query("SELECT * FROM watchlist WHERE imdbId = :imdbId LIMIT 1")
    suspend fun getById(imdbId: String): WatchlistItem?
}
