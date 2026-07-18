package com.cvuong233.cinephantom.model

data class NotificationHistoryItem(
    val id: String,
    val title: String,
    val mediaId: String,
    val posterPath: String?,
    val message: String,
    val firedAt: Long,
    val type: String,
)
