package com.offline.nexu.ui.home

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.model.UserProfile
import java.io.File

// Bueno, este es el manager maestro de la conexión P2P. Si esto se rompe, nos quedamos sin app xd.
object P2PManager {
    // Acá guardamos el ID y nombre del bato con el que estamos conectados ahorita
    var currentEndpointId: String? = null
    var currentEndpointName: String? = null
    var context: Context? = null

    var onMessageReceivedListener: ((Long, String, Uri?) -> Unit)? = null
    var onMessageSentListener: ((Long) -> Unit)? = null
    var onAckReceivedListener: ((Long) -> Unit)? = null
    var onDistanceEstimatedListener: ((String) -> Unit)? = null
    var onProfileReceivedListener: ((String, String, String) -> Unit)? = null

    // Mapa para saber qué payloads mandamos y ver si llegaron (ojalá)
    val outgoingPayloadsTracker = mutableMapOf<Long, Long>()
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()

    private val expectedProfileImages = mutableMapOf<Long, String>()
    private val expectedChatImages = mutableMapOf<Long, Pair<Long, String>>()

    fun clearSession() {
        currentEndpointId = null
        currentEndpointName = null
        outgoingPayloadsTracker.clear()
        incomingFilePayloads.clear()
        expectedProfileImages.clear()
        expectedChatImages.clear()

        context?.let {
            try {
                com.google.android.gms.nearby.Nearby.getConnectionsClient(it).stopAllEndpoints()
            } catch (e: Exception) {
                Log.e("NexuHandshake", "Error al detener endpoints", e)
            }
        }
    }

    // ARREGLO EXTREMO: Lectura de archivos a prueba de fallos de Android
    // Malditos URIs de Android que siempre cambian, esto debería aguantar todo.
    fun sendProfileHandshake(endpointId: String, profile: UserProfile, ctx: Context) {
        Log.d("NexuHandshake", "Iniciando Handshake para $endpointId. Avatar: ${profile.avatar}")

        if (profile.avatar.startsWith("/") || profile.avatar.startsWith("file://") || profile.avatar.startsWith("content://")) {
            try {
                val file = if (profile.avatar.startsWith("/")) {
                    File(profile.avatar)
                } else {
                    File(Uri.parse(profile.avatar).path ?: "")
                }

                if (file.exists()) {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val filePayload = Payload.fromFile(pfd)
                    val textPayload = Payload.fromBytes("PROFILE_IMG:${profile.name}:${filePayload.id}".toByteArray(Charsets.UTF_8))

                    com.google.android.gms.nearby.Nearby.getConnectionsClient(ctx).sendPayload(endpointId, textPayload)
                    com.google.android.gms.nearby.Nearby.getConnectionsClient(ctx).sendPayload(endpointId, filePayload)
                    Log.d("NexuHandshake", "ÉXITO: Foto cargada y enviada. Payload ID: ${filePayload.id}")
                } else {
                    Log.e("NexuHandshake", "ERROR: El archivo de imagen no existe en la ruta.")
                }
            } catch (e: Exception) {
                Log.e("NexuHandshake", "ERROR CRÍTICO abriendo la foto de perfil", e)
            }
        } else {
            val textPayload = Payload.fromBytes("PROFILE_EMOJI:${profile.name}:${profile.avatar}".toByteArray(Charsets.UTF_8))
            com.google.android.gms.nearby.Nearby.getConnectionsClient(ctx).sendPayload(endpointId, textPayload)
            Log.d("NexuHandshake", "ÉXITO: Emoji enviado correctamente: ${profile.avatar}")
        }
    }

    // Este callback es un monstruo que maneja de todo. 
    // TODO: Refactorizar esto luego porque está un poco feo, pero meh, si funciona no lo toques. 🤷‍♂️
    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val data = String(payload.asBytes()!!, Charsets.UTF_8)
                    if (data.startsWith("PING:")) {
                        // ignore
                    } else if (data.startsWith("ACK:")) {
                        val msgId = data.substringAfter("ACK:").toLongOrNull()
                        if (msgId != null) onAckReceivedListener?.invoke(msgId)
                    } else if (data.startsWith("TXT:")) {
                        val parts = data.split(":", limit = 3)
                        if (parts.size == 3) {
                            val msgId = parts[1].toLong()
                            val text = parts[2]
                            onMessageReceivedListener?.invoke(msgId, text, null)
                            sendAck(endpointId, msgId)
                        }
                    } else if (data.startsWith("IMG:")) {
                        val parts = data.split(":", limit = 4)
                        if (parts.size == 4) {
                            val payloadId = parts[1].toLong()
                            val msgId = parts[2].toLong()
                            val text = parts[3]
                            expectedChatImages[payloadId] = Pair(msgId, text)
                        }
                    } else if (data.startsWith("PROFILE_EMOJI:")) {
                        val parts = data.split(":", limit = 3)
                        if (parts.size == 3) {
                            val name = parts[1]
                            val emoji = parts[2]
                            Log.d("NexuHandshake", "Recibido EMOJI de perfil de $name: $emoji")
                            saveContactProfile(endpointId, name, emoji)
                        }
                    } else if (data.startsWith("PROFILE_IMG:")) {
                        val parts = data.split(":", limit = 3)
                        if (parts.size == 3) {
                            val name = parts[1]
                            val payloadId = parts[2].toLong()
                            Log.d("NexuHandshake", "Aviso recibido: Viene la FOTO de $name (Payload ID: $payloadId)")
                            expectedProfileImages[payloadId] = name
                        }
                    }
                }
                Payload.Type.FILE -> {
                    Log.d("NexuHandshake", "Recibiendo archivo pesado... Payload ID: ${payload.id}")
                    incomingFilePayloads[payload.id] = payload
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                if (outgoingPayloadsTracker.containsKey(update.payloadId)) {
                    val msgId = outgoingPayloadsTracker[update.payloadId]!!
                    onMessageSentListener?.invoke(msgId)
                    outgoingPayloadsTracker.remove(update.payloadId)
                }

                if (expectedProfileImages.containsKey(update.payloadId)) {
                    val name = expectedProfileImages[update.payloadId]!!
                    val filePayload = incomingFilePayloads[update.payloadId]
                    if (filePayload != null && filePayload.asFile() != null) {
                        Log.d("NexuHandshake", "¡Descarga de FOTO de $name exitosa! Procesando guardado...")
                        val uri = renameAndMoveFile(filePayload.asFile()!!.asJavaFile())
                        if (uri != null) {
                            Log.d("NexuHandshake", ">> ÉXITO: Foto guardada en caché local: $uri")
                            saveContactProfile(endpointId, name, uri.toString())
                        } else {
                            Log.e("NexuHandshake", ">> ERROR: Falló al mover el archivo a NexuChat/Media/Profiles")
                        }
                    } else {
                        Log.e("NexuHandshake", ">> ERROR: El archivo de imagen llegó nulo o corrupto.")
                    }
                    expectedProfileImages.remove(update.payloadId)
                }

                if (expectedChatImages.containsKey(update.payloadId)) {
                    val msgInfo = expectedChatImages[update.payloadId]!!
                    val filePayload = incomingFilePayloads[update.payloadId]
                    if (filePayload != null && filePayload.asFile() != null) {
                        val uri = renameAndMoveFile(filePayload.asFile()!!.asJavaFile())
                        onMessageReceivedListener?.invoke(msgInfo.first, msgInfo.second, uri)
                        sendAck(endpointId, msgInfo.first)
                    }
                    expectedChatImages.remove(update.payloadId)
                }
            }
        }
    }

    private fun sendAck(endpointId: String, msgId: Long) {
        context?.let { ctx ->
            val ackPayload = Payload.fromBytes("ACK:$msgId".toByteArray(Charsets.UTF_8))
            com.google.android.gms.nearby.Nearby.getConnectionsClient(ctx).sendPayload(endpointId, ackPayload)
        }
    }

    private fun renameAndMoveFile(tempFile: File?): Uri? {
        // A veces Android devuelve null acá, así que mejor checamos para que no crashee.
        if (tempFile == null || context == null) return null
        try {
            val mediaDir = File(context!!.getExternalFilesDir(null), "NexuChat/Media/Profiles")
            if (!mediaDir.exists()) mediaDir.mkdirs()
            val newFile = File(mediaDir, "IMG_${System.currentTimeMillis()}.jpg")
            tempFile.copyTo(newFile, overwrite = true)
            return Uri.fromFile(newFile)
        } catch (e: Exception) {
            Log.e("NexuHandshake", "Error guardando archivo", e)
        }
        return null
    }

    private fun saveContactProfile(endpointId: String, name: String, avatarData: String) {
        context?.let { ctx ->
            Prefs(ctx).saveContactAvatar(name, avatarData)
            onProfileReceivedListener?.invoke(endpointId, name, avatarData)
        }
    }
}