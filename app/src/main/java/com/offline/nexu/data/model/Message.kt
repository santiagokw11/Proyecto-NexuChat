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
    val expiresAt: Long? = null, // NUEVO
    // Orden real de llegada/envío en ESTE dispositivo. msgId es el reloj del emisor (sin
    // sincronía entre pares): usarlo para ordenar agrupa mensajes por persona si los relojes
    // difieren. Por defecto = msgId (mensajes propios, generados en este mismo instante).
    val timestamp: Long = msgId
)