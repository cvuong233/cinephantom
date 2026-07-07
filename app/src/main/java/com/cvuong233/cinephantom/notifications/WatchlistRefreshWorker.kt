package com.cvuong233.cinephantom.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cvuong233.cinephantom.data.TMDBApi
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WatchlistRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!NotificationPreferences.get(applicationContext).masterEnabled) return@withContext Result.success()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext Result.success()
        val db = FirebaseFirestore.getInstance()

        val snapshot = try {
            Tasks.await(db.collection("users").document(uid).collection("favorites").get())
        } catch (_: Exception) { return@withContext Result.retry() }

        val tmdbApi = TMDBApi()

        for (doc in snapshot.documents) {
            val imdbId = doc.getString("imdbId") ?: continue
            val title = doc.getString("title") ?: continue
            val typeLabel = doc.getString("typeLabel") ?: ""
            val imageUrl = doc.getString("imageUrl")
            val isTV = typeLabel.contains("series", ignoreCase = true) ||
                       typeLabel.contains("tv", ignoreCase = true) ||
                       typeLabel.contains("drama", ignoreCase = true)

            try {
                val details = tmdbApi.fetchTitleDetailsByImdb(imdbId, preferSeries = isTV)
                    ?: continue

                if (isTV) {
                    val nextEp = details.showDetails?.nextEpisode
                    val airDate = nextEp?.airDate
                    if (!airDate.isNullOrBlank()) {
                        WatchlistNotificationScheduler.schedule(
                            context = applicationContext,
                            imdbId = imdbId,
                            title = title,
                            isTV = true,
                            airDate = airDate,
                            season = nextEp.seasonNumber,
                            episode = nextEp.episodeNumber,
                            imageUrl = imageUrl,
                        )
                    }
                } else {
                    val releaseDate = details.releaseDate
                    if (!releaseDate.isNullOrBlank()) {
                        WatchlistNotificationScheduler.schedule(
                            context = applicationContext,
                            imdbId = imdbId,
                            title = title,
                            isTV = false,
                            airDate = releaseDate,
                            imageUrl = imageUrl,
                        )
                    }
                }
            } catch (_: Exception) { /* skip this title */ }
        }

        Result.success()
    }
}
