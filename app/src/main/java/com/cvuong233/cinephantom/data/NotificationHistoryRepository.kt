package com.cvuong233.cinephantom.data

import com.cvuong233.cinephantom.model.NotificationHistoryItem
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NotificationHistoryRepository {

    private const val MAX_HISTORY = 200L

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _history = MutableStateFlow<List<NotificationHistoryItem>>(emptyList())
    val history: StateFlow<List<NotificationHistoryItem>> = _history.asStateFlow()

    private var listenerReg: ListenerRegistration? = null

    fun init() {
        auth.addAuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser != null) startListening() else stopListening()
        }
    }

    private fun startListening() {
        val uid = auth.currentUser?.uid ?: return
        listenerReg?.remove()
        listenerReg = db.collection("users").document(uid).collection("notificationHistory")
            .orderBy("firedAt", Query.Direction.DESCENDING)
            .limit(MAX_HISTORY)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                _history.value = snapshot.documents.mapNotNull { doc ->
                    try {
                        NotificationHistoryItem(
                            id = doc.id,
                            title = doc.getString("title") ?: return@mapNotNull null,
                            mediaId = doc.getString("mediaId") ?: "",
                            posterPath = doc.getString("posterPath"),
                            message = doc.getString("message") ?: "",
                            firedAt = doc.getLong("firedAt") ?: 0L,
                            type = doc.getString("type") ?: "movie",
                        )
                    } catch (_: Exception) { null }
                }
            }
    }

    private fun stopListening() {
        listenerReg?.remove()
        listenerReg = null
        _history.value = emptyList()
    }

    // Fire-and-forget — called from WatchlistNotificationReceiver.onReceive, which is not a
    // coroutine context, so this can't suspend/await. Firestore queues the write offline and
    // syncs once connectivity is back, same as FavoritesRepository's writes.
    fun record(title: String, mediaId: String, posterPath: String?, message: String, type: String) {
        val uid = auth.currentUser?.uid ?: return
        val data = hashMapOf<String, Any?>(
            "title" to title,
            "mediaId" to mediaId,
            "posterPath" to posterPath,
            "message" to message,
            "firedAt" to System.currentTimeMillis(),
            "type" to type,
        )
        db.collection("users").document(uid).collection("notificationHistory").add(data)
    }
}
