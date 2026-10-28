package com.offline.nexu.data.model

import android.net.Uri

data class Message(
    val msgId: Long,
    val text: String?,
    val imageUrl: Uri?,
    val isMine: Boolean,
    var status: Int,
    var progress: Int = -1,
    val originalSender: String? = null,
    val isGroup: Boolean = false,
    val expiresAt: Long? = null // NUEVO
)