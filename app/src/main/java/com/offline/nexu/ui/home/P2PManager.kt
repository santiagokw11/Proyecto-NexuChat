package com.offline.nexu.ui.home

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.offline.nexu.ConnectionService
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.data.model.UserProfile
import com.offline.nexu.ui.chat.ChatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

object P2PManager {
    var currentEndpointId: String? = null
    var currentEndpointName: String? = null
    var context: Context? = null
    var currentActivity: android.app.Activity? = null

    // Novedad: Saber exactamente qué chat está abierto en la pantalla
    var activeChatIdentifier: String? = null

    var onMessageReceivedListener: ((Long, String, Uri?) -> Unit)? = null
    var onMessageSentListener: ((Long) -> Unit)? = null
    var onAckReceivedListener: ((Long) -> Unit)? = null
    var onDistanceEstimatedListener: ((String) -> Unit)? = null
    var onProfileReceivedListener: ((String, String, String) -> Unit)? = null
    var onPayloadProgress: ((Long, Int) -> Unit)? = null

    val outgoingPayloadsTracker = mutableMapOf<Long, Long>()
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()
    private val expectedProfileImages = mutableMapOf<Long, String>()
    private val expectedChatImages = mutableMapOf<Long, Triple<Long, String, String>>()

    val connectedNames = mutableMapOf<String, String>()
    val connectedEndpoints = mutableSetOf<String>()

    fun setExpectedGroupAvatar(payloadId: Long, groupId: String) {
        expectedChatImages[payloadId] = Triple(System.currentTimeMillis(), "SYS:AVATAR_UPDATE", groupId)
    }

    fun clearSession() {
        currentEndpointId = null
        currentEndpointName = null
        outgoingPayloadsTracker.clear()
        incomingFilePayloads.clear()
        expectedProfileImages.clear()
        expectedChatImages.clear()

        context?.let {
            try { Nearby.getConnectionsClient(it).stopAllEndpoints() } catch (e: Exception) {}
        }
    }

    // MAGIA 1: La función que debes llamar al presionar "Cerrar Sesión" en tu app
    fun cerrarSesionCompleta(ctx: Context) {
        clearSession()
        activeChatIdentifier = null
        try {
            Nearby.getConnectionsClient(ctx).stopAllEndpoints()
            Nearby.getConnectionsClient(ctx).stopAdvertising()
            Nearby.getConnectionsClient(ctx).stopDiscovery()
            val serviceIntent = Intent(ctx, ConnectionService::class.java)
            ctx.stopService(serviceIntent)
        } catch (e: Exception) {}
    }

    fun sendProfileHandshake(endpointId: String, profile: UserProfile, ctx: Context) {
        if (profile.avatar.startsWith("/") || profile.avatar.startsWith("file://") || profile.avatar.startsWith("content://")) {
            try {
                val file = if (profile.avatar.startsWith("/")) File(profile.avatar) else File(Uri.parse(profile.avatar).path ?: "")
                if (file.exists()) {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val filePayload = Payload.fromFile(pfd)
                    val textPayload = Payload.fromBytes("PROFILE_IMG:${profile.name}:${filePayload.id}".toByteArray(Charsets.UTF_8))
                    Nearby.getConnectionsClient(ctx).sendPayload(endpointId, textPayload)
                    Nearby.getConnectionsClient(ctx).sendPayload(endpointId, filePayload)
                }
            } catch (e: Exception) {}
        } else {
            val textPayload = Payload.fromBytes("PROFILE_EMOJI:${profile.name}:${profile.avatar}".toByteArray(Charsets.UTF_8))
            Nearby.getConnectionsClient(ctx).sendPayload(endpointId, textPayload)
        }
    }

    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val data = String(payload.asBytes()!!, Charsets.UTF_8)
                    if (data.startsWith("PING:") || data.startsWith("PONG:")) {
                        // NexuMeshManager
                    } else if (data.startsWith("ACK:")) {
                        val msgId = data.substringAfter("ACK:").toLongOrNull()
                        if (msgId != null) onAckReceivedListener?.invoke(msgId)
                    } else if (data.startsWith("TXT:")) {
                        val parts = data.split(":", limit = 3)
                        if (parts.size == 3) {
                            val msgId = parts[1].toLong()
                            val text = parts[2]

                            if (text.startsWith("SYS:")) {
                                onMessageReceivedListener?.invoke(msgId, text, null)
                            } else {
                                procesarMensajeEntrante(endpointId, msgId, text, null)
                            }
                            if (msgId != 0L) sendAck(endpointId, msgId)
                        }
                    } else if (data.startsWith("IMG:") || data.startsWith("VID:") || data.startsWith("DOC:")) {
                        val parts = data.split(":", limit = 4)
                        if (parts.size == 4) {
                            expectedChatImages[parts[1].toLong()] = Triple(parts[2].toLong(), parts[3], parts[0])
                        }
                    } else if (data.startsWith("PROFILE_EMOJI:")) {
                        val parts = data.split(":", limit = 3)
                        if (parts.size == 3) saveContactProfile(endpointId, parts[1], parts[2])
                    } else if (data.startsWith("PROFILE_IMG:")) {
                        val parts = data.split(":", limit = 3)
                        if (parts.size == 3) expectedProfileImages[parts[2].toLong()] = parts[1]
                    }
                }
                Payload.Type.FILE -> incomingFilePayloads[payload.id] = payload
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.IN_PROGRESS && update.totalBytes > 0) {
                val progress = ((update.bytesTransferred.toDouble() / update.totalBytes.toDouble()) * 100).toInt()
                val msgId = outgoingPayloadsTracker[update.payloadId] ?: expectedChatImages[update.payloadId]?.first
                if (msgId != null) onPayloadProgress?.invoke(msgId, progress)
            }
            else if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                if (outgoingPayloadsTracker.containsKey(update.payloadId)) {
                    onMessageSentListener?.invoke(outgoingPayloadsTracker[update.payloadId]!!)
                    outgoingPayloadsTracker.remove(update.payloadId)
                }
                if (expectedProfileImages.containsKey(update.payloadId)) {
                    val name = expectedProfileImages[update.payloadId]!!
                    val filePayload = incomingFilePayloads[update.payloadId]
                    if (filePayload != null) {
                        val uri = renameAndMoveFileSafely(filePayload, "PROFILE")
                        if (uri != null) saveContactProfile(endpointId, name, uri.toString())
                    }
                    expectedProfileImages.remove(update.payloadId)
                    incomingFilePayloads.remove(update.payloadId)
                }
                if (expectedChatImages.containsKey(update.payloadId)) {
                    val msgInfo = expectedChatImages[update.payloadId]!!
                    val filePayload = incomingFilePayloads[update.payloadId]
                    if (filePayload != null) {
                        val uri = renameAndMoveFileSafely(filePayload, msgInfo.third)
                        procesarMensajeEntrante(endpointId, msgInfo.first, msgInfo.second, uri)
                        sendAck(endpointId, msgInfo.first)
                    }
                    expectedChatImages.remove(update.payloadId)
                    incomingFilePayloads.remove(update.payloadId)
                }
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE || update.status == PayloadTransferUpdate.Status.CANCELED) {
                outgoingPayloadsTracker.remove(update.payloadId)
                expectedProfileImages.remove(update.payloadId)
                expectedChatImages.remove(update.payloadId)
                incomingFilePayloads.remove(update.payloadId)
            }
        }
    }

    // MAGIA 2: Guardado de mensajes blindado. Ocurre siempre, sin importar si el chat está abierto
    private fun procesarMensajeEntrante(endpointId: String, msgId: Long, text: String, uri: Uri?) {
        context?.let { ctx ->
            val prefs = Prefs(ctx)
            val localUserId = prefs.getUserProfile().id
            if (localUserId.isEmpty()) return // Ignorar si no hay sesión iniciada

            val senderName = currentEndpointName ?: "Desconocido"

            val entity = MessageEntity(
                msgId = msgId,
                localUserId = localUserId,
                endpointId = senderName,
                text = text,
                imagePath = uri?.toString(),
                isMine = false,
                status = 0,
                timestamp = System.currentTimeMillis()
            )

            // Guardamos el mensaje en BD independientemente de la UI
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    AppDatabase.getDatabase(ctx).nexuDao().insertMessage(entity)
                } catch (e: Exception) { Log.e("P2P", "Error saving msg", e) }
            }

            // Si el usuario tiene el chat abierto, notificamos a la pantalla
            if (activeChatIdentifier == senderName) {
                onMessageReceivedListener?.invoke(msgId, text, uri)
            } else {
                // Si está cerrado, disparamos la notificación Push
                mostrarNotificacion(ctx, senderName, text)
            }
        }
    }

    private fun mostrarNotificacion(ctx: Context, emisor: String, mensaje: String) {
        val channelId = "nexu_messages"
        val notifManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Mensajes P2P", NotificationManager.IMPORTANCE_HIGH)
            notifManager.createNotificationChannel(channel)
        }
        val intent = Intent(ctx, ChatActivity::class.java).apply {
            putExtra("EXTRA_USER_NAME", emisor)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(ctx, emisor.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val textoMostrar = if (mensaje.isEmpty()) "📷 Multimedia recibida" else mensaje

        val builder = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(emisor)
            .setContentText(textoMostrar)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        notifManager.notify(emisor.hashCode(), builder.build())
    }

    private fun sendAck(endpointId: String, msgId: Long) {
        context?.let { ctx ->
            val ackPayload = Payload.fromBytes("ACK:$msgId".toByteArray(Charsets.UTF_8))
            Nearby.getConnectionsClient(ctx).sendPayload(endpointId, ackPayload)
        }
    }

    private fun renameAndMoveFileSafely(filePayload: Payload, type: String): Uri? {
        if (context == null) return null
        try {
            val subFolder = when(type) {
                "VID" -> "NexuChat Videos"
                "DOC" -> "NexuChat Documents"
                "PROFILE" -> "Profiles"
                else -> "NexuChat Images"
            }
            val ext = when(type) { "VID" -> ".mp4"; "DOC" -> ".pdf"; else -> ".jpg" }
            val prefix = when(type) { "VID" -> "VID_"; "DOC" -> "DOC_"; "PROFILE" -> "PROF_"; else -> "IMG_" }

            val mediaDir = File(context!!.getExternalFilesDir(null), "NexuChat/Media/$subFolder")
            if (!mediaDir.exists()) mediaDir.mkdirs()
            val nomedia = File(mediaDir, ".nomedia")
            if (!nomedia.exists()) nomedia.createNewFile()

            val newFile = File(mediaDir, "${prefix}${System.currentTimeMillis()}$ext")

            var inputStream: java.io.InputStream? = null
            val payloadFile = filePayload.asFile()

            if (payloadFile != null) {
                if (payloadFile.asUri() != null) {
                    try { inputStream = context!!.contentResolver.openInputStream(payloadFile.asUri()!!) } catch (e: Exception) { }
                }
                if (inputStream == null && payloadFile.asParcelFileDescriptor() != null) {
                    try { inputStream = ParcelFileDescriptor.AutoCloseInputStream(payloadFile.asParcelFileDescriptor()) } catch (e: Exception) { }
                }
                if (inputStream == null && payloadFile.asJavaFile() != null) {
                    try { if (payloadFile.asJavaFile()!!.exists()) inputStream = payloadFile.asJavaFile()!!.inputStream() } catch (e: Exception) { }
                }
            }
            if (inputStream == null) return null
            inputStream.use { input -> FileOutputStream(newFile).use { output -> input.copyTo(output) } }
            return Uri.fromFile(newFile)
        } catch (e: Exception) { return null }
    }

    private fun saveContactProfile(endpointId: String, name: String, avatarData: String) {
        context?.let { ctx ->
            Prefs(ctx).saveContactAvatar(name, avatarData)
            onProfileReceivedListener?.invoke(endpointId, name, avatarData)
        }
    }
}