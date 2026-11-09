package com.offline.nexu.data.local.db

import androidx.room.Entity

// Clave compuesta: msgId solo no es único entre cuentas (multicuenta) ni garantiza
// no colisionar entre chats distintos; sin esto, OnConflictStrategy.REPLACE podía
// pisar silenciosamente un mensaje de otra cuenta/chat que compartiera el mismo msgId.
@Entity(tableName = "messages", primaryKeys = ["msgId", "localUserId"])
data class MessageEntity(
    val msgId: Long,
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