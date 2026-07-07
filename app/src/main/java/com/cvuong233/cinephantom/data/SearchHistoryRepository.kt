package com.cvuong233.cinephantom.data

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

object SearchHistoryRepository {

    private const val MAX_HISTORY = 10L

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Blocking — call from a background thread.
    fun recentBlocking(): List<String> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        return try {
            val snapshot = Tasks.await(
                db.collection("users").document(uid).collection("searchHistory")
                    .orderBy("searchedAt", Query.Direction.DESCENDING)
                    .limit(MAX_HISTORY)
                    .get()
            )
            snapshot.documents.mapNotNull { it.getString("query") }
        } catch (_: Exception) { emptyList() }
    }

    // Doc ID is the lowercased query, so re-searching the same term just bumps its timestamp
    // instead of piling up duplicates.
    fun record(query: String) {
        val uid = auth.currentUser?.uid ?: return
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val data = hashMapOf<String, Any>(
            "query" to trimmed,
            "searchedAt" to System.currentTimeMillis()
        )
        db.collection("users").document(uid).collection("searchHistory")
            .document(trimmed.lowercase()).set(data)
    }

    // Blocking — call from a background thread.
    fun clearBlocking() {
        val uid = auth.currentUser?.uid ?: return
        try {
            val snapshot = Tasks.await(
                db.collection("users").document(uid).collection("searchHistory").get()
            )
            for (doc in snapshot.documents) Tasks.await(doc.reference.delete())
        } catch (_: Exception) { }
    }
}
