package com.cvuong233.cinephantom.data

import com.cvuong233.cinephantom.model.ImdbTitle
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object FavoritesRepository {

    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _favorites = MutableStateFlow<List<ImdbTitle>>(emptyList())
    val favorites: StateFlow<List<ImdbTitle>> = _favorites.asStateFlow()

    private val _favoriteIds = MutableStateFlow<Set<String>>(emptySet())

    private var listenerReg: ListenerRegistration? = null

    fun init() {
        auth.addAuthStateListener { firebaseAuth ->
            if (firebaseAuth.currentUser != null) startListening() else stopListening()
        }
    }

    private fun startListening() {
        val uid = auth.currentUser?.uid ?: return
        listenerReg?.remove()
        listenerReg = db.collection("users").document(uid).collection("favorites")
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val titles = snapshot.documents.mapNotNull { doc ->
                    try {
                        ImdbTitle(
                            id = doc.getString("imdbId") ?: return@mapNotNull null,
                            title = doc.getString("title") ?: "",
                            typeLabel = doc.getString("typeLabel"),
                            year = doc.getString("year"),
                            cast = null,
                            imageUrl = doc.getString("imageUrl"),
                            tmdbId = doc.getLong("tmdbId")?.toInt(),
                            rating = doc.getDouble("rating")?.toFloat(),
                            ratingText = doc.getString("ratingText"),
                            ratingSourceLabel = doc.getString("ratingSourceLabel"),
                        )
                    } catch (_: Exception) { null }
                }.sortedByDescending { doc ->
                    snapshot.documents.firstOrNull { it.getString("imdbId") == doc.id }
                        ?.getLong("addedAt") ?: 0L
                }
                _favorites.value = titles
                _favoriteIds.value = titles.map { it.id }.toSet()
            }
    }

    private fun stopListening() {
        listenerReg?.remove()
        listenerReg = null
        _favorites.value = emptyList()
        _favoriteIds.value = emptySet()
    }

    fun isFavorite(imdbId: String): Boolean = _favoriteIds.value.contains(imdbId)

    fun toggle(title: ImdbTitle) {
        val uid = auth.currentUser?.uid ?: return
        val ref = db.collection("users").document(uid).collection("favorites").document(title.id)
        if (isFavorite(title.id)) {
            ref.delete()
            _favoriteIds.value = _favoriteIds.value - title.id
            _favorites.value = _favorites.value.filter { it.id != title.id }
        } else {
            val data = hashMapOf<String, Any?>(
                "imdbId" to title.id,
                "title" to title.title,
                "typeLabel" to title.typeLabel,
                "year" to title.year,
                "imageUrl" to title.imageUrl,
                "tmdbId" to title.tmdbId,
                "rating" to title.rating,
                "ratingText" to title.ratingText,
                "ratingSourceLabel" to title.ratingSourceLabel,
                "addedAt" to System.currentTimeMillis(),
            )
            ref.set(data)
            _favoriteIds.value = _favoriteIds.value + title.id
            _favorites.value = listOf(title) + _favorites.value
        }
    }
}
