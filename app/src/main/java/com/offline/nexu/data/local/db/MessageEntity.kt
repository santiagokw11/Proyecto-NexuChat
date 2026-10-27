package com.offline.nexu.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val msgId: Long,
    val localUserId: String, // El dueño de este mensaje
    val endpointId: String,  // Con quién estás hablando
    val text: String,
    val imagePath: String?,
    val isMine: Boolean,
    val status: Int,
    val timestamp: Long,
    val originalSender: String? = null,
    val isGroup: Boolean = false
)