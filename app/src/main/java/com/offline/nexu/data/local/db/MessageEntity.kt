package com.offline.nexu.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val msgId: Long,
    val localUserId: String,
    val endpointId: String,
    val originalSender: String?,
    val isGroup: Boolean,
    val text: String,
    val imagePath: String?,
    val isMine: Boolean,
    val status: Int,
    val timestamp: Long,
    val expiresAt: Long? = null // NUEVO: Fecha/hora exacta en que debe borrarse
)