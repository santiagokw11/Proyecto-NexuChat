package com.offline.nexu.data.model

import android.net.Uri

data class Message(
    val msgId: Long,
    val text: String?,
    val imageUrl: Uri?,
    val isMine: Boolean,
    var status: Int
)