package com.offline.nexu.data.model

import android.net.Uri

data class Message(
    val id: Long, // ID único para rastrear los chulos
    val text: String,
    val imageUri: Uri?,
    val isMine: Boolean,
    var status: Int = 0 // 0 = Reloj, 1 = Un chulo, 2 = Dos chulos
)