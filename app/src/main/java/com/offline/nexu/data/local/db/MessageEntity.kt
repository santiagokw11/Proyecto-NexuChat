package com.offline.nexu.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val msgId: Long,
    val endpointId: String,
    val text: String,
    val imagePath: String?,
    val isMine: Boolean,
    val status: Int,
    val timestamp: Long
)