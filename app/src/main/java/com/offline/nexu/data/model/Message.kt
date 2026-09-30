package com.offline.nexu.data.model

import android.net.Uri

data class Message(
    val text: String,
    val imageUri: Uri? = null,
    val isMine: Boolean,
    val payloadId: Long? = null // Conecta el texto con la descarga de la imagen
)