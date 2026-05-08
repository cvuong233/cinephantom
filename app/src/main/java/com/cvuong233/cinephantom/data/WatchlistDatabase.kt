package com.cvuong233.cinephantom.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.cvuong233.cinephantom.model.WatchlistItem

@Database(entities = [WatchlistItem::class], version = 3, exportSchema = false)
abstract class WatchlistDatabase : RoomDatabase() {
    abstract fun dao(): WatchlistDao

    companion object {
        @Volatile
        private var INSTANCE: WatchlistDatabase? = null

        fun get(context: Context): WatchlistDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WatchlistDatabase::class.java,
                    "cinephantom.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
        }
    }
}
