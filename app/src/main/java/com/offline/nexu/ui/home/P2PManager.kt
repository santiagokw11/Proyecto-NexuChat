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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.offline.nexu.ConnectionService
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.utils.MsgIdGenerator
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.data.model.UserProfile
import com.offline.nexu.ui.chat.ChatActivity
import com.offline.nexu.data.network.NexuMeshManager
import com.offline.nexu.ui.chat.group.GroupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object P2PManager {
    var currentEndpointId: String? = null
    var currentEndpointName: String? = null
    var context: Context? = null
    var currentActivity: android.app.Activity? = null

    var activeChatIdentifier: String? = null

    val connectedNames = mutableMapOf<String, String>()

    // Último % de batería reportado por cada contacto, keyed por nombre. Se actualiza cada
    // 3s vía el broadcast "BATT:" (ver NexuMeshManager.enviarNivelBateria).
    val contactBatteryLevels = mutableMapOf<String, Int>()

    var onMessageReceivedListener: ((Long, String, Uri?) -> Unit)? = null
    var onMessageSentListener: ((Long) -> Unit)? = null
    var onMessageFailedListener: ((Long) -> Unit)? = null

    // MODIFICADO: Ahora envía el msgId y el status (2 o 3)
    var onAckReceivedListener: ((Long, Int) -> Unit)? = null

    var onDistanceEstimatedListener: ((String) -> Unit)? = null
    var onProfileReceivedListener: ((String, String, String) -> Unit)? = null
    var onPayloadProgress: ((Long, Int) -> Unit)? = null

    // Avisa a la pantalla de chat abierta que llegó un nuevo % de batería (ver BATT: en
    // NexuMeshManager), para refrescar el header/burbuja al instante en vez de esperar a que
    // algo no relacionado repinte la UI.
    var onBatteryUpdateListener: ((String, Int) -> Unit)? = null

    data class PendingFileInfo(val msgId: Long, val content: String, val type: String, val senderName: String, val isGroup: Boolean = false, val groupId: String? = null)

    val outgoingPayloadsTracker = mutableMapOf<Long, Long>()
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()
    private val expectedProfileImages = mutableMapOf<Long, String>()
    private val expectedChatImages = mutableMapOf<Long, PendingFileInfo>()

    private var isGlobalSyncing = false

    fun clearSession() {
        currentEndpointId = null
        currentEndpointName = null
        outgoingPayloadsTracker.clear()
        incomingFilePayloads.clear()
        expectedProfileImages.clear()
        expectedChatImages.clear()
        connectedNames.clear()

        context?.let {
            try {
                Nearby.getConnectionsClient(it).stopAllEndpoints()
                Nearby.getConnectionsClient(it).stopDiscovery()
                Nearby.getConnectionsClient(it).stopAdvertising()
            } catch (e: Exception) {}
        }
    }

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

    fun inicializarChatSiNoExiste(ctx: Context, endpointId: String, endpointName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(ctx)
                val prefs = Prefs(ctx)
                val myLocalUserId = prefs.getUserProfile().id
                val exists = db.nexuDao().getRecentChats(myLocalUserId).any { it.endpointId == endpointId }

                if (!exists) {
                    val dummyMsg = MessageEntity(
                        msgId = MsgIdGenerator.next(),
                        localUserId = myLocalUserId,
                        endpointId = endpointId,
                        originalSender = endpointName,
                        isGroup = false,
                        text = "Chat inicializado",
                        imagePath = null,
                        isMine = false,
                        status = -1,
                        timestamp = System.currentTimeMillis()
                    )
                    db.nexuDao().insertMessage(dummyMsg)

                    withContext(Dispatchers.Main) {
                        val intent = Intent("com.offline.nexu.ACTION_UPDATE_CHAT_LIST")
                        intent.setPackage(ctx.packageName)
                        ctx.sendBroadcast(intent)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    fun reenviarMensajesPendientesGlobal(ctx: Context) {
        if (isGlobalSyncing) return
        isGlobalSyncing = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(ctx)
                val prefs = Prefs(ctx)
                val myLocalUserId = prefs.getUserProfile().id
                val myName = prefs.getUserProfile().name
                // Usamos los endpoints con mensajes pendientes reales (incluye comandos SYS: de
                // grupo), no getRecentChats: esa lista ignora chats cuyo único mensaje es un SYS:
                // pendiente (ej. invitado nuevo a un grupo sin chat 1:1 previo).
                val pendingEndpoints = db.nexuDao().getEndpointsWithPendingMessages(myLocalUserId)

                val eps = NexuMeshManager.connectedEndpoints.toList()
                if (eps.isEmpty()) { isGlobalSyncing = false; return@launch }

                // Con cualquier conexión activa reintentamos: el paquete MESH/GMESH viaja hasta
                // 4 saltos por reenvío (TTL), así que exigir vecino directo del destinatario
                // dejaba miembros de grupo sin recibir la invitación/actualización indefinidamente.
                for (targetId in pendingEndpoints) {
                    val historial = db.nexuDao().getMessagesForUserSync(targetId, myLocalUserId)
                    val pendientes = historial.filter { it.isMine && (it.status == 0 || it.status == 1 || it.status == -2) }
                    val groupKeyB64 = if (pendientes.any { it.isGroup }) db.nexuDao().getGroup(targetId)?.encryptionKeyB64 else null

                    if (pendientes.isNotEmpty()) {
                        for (msg in pendientes) {
                            val text = msg.text ?: ""
                            val msgId = msg.msgId
                            val isGroupMsg = msg.isGroup

                            if (msg.imagePath != null) {
                                val file = File(Uri.parse(msg.imagePath).path ?: "")
                                if (file.exists()) {
                                    val filePayload = Payload.fromFile(file)
                                    val headerType = when {
                                        msg.imagePath.contains("DOC") || msg.imagePath.endsWith(".pdf") -> "DOC"
                                        msg.imagePath.contains("VID") || msg.imagePath.endsWith(".mp4") -> "VID"
                                        msg.imagePath.contains("AUD") || msg.imagePath.endsWith(".ogg") || msg.imagePath.endsWith(".m4a") -> "AUD"
                                        else -> "IMG"
                                    }
                                    val header = "$headerType:${filePayload.id}:$msgId:$text"

                                    if (isGroupMsg) {
                                        NexuMeshManager.enviarMensajeGrupoMesh(ctx, myLocalUserId, myName, targetId, msgId, header, groupKeyB64)
                                    } else {
                                        NexuMeshManager.enviarMensajeMesh(ctx, myLocalUserId, myName, targetId, msgId, header)
                                    }
                                    outgoingPayloadsTracker[filePayload.id] = msgId
                                    delay(500)
                                    Nearby.getConnectionsClient(ctx).sendPayload(eps, filePayload)
                                }
                            } else if (text.isNotEmpty()) {
                                val header = "TXT:$msgId:$text"
                                if (isGroupMsg) {
                                    NexuMeshManager.enviarMensajeGrupoMesh(ctx, myLocalUserId, myName, targetId, msgId, header, groupKeyB64)
                                } else {
                                    NexuMeshManager.enviarMensajeMesh(ctx, myLocalUserId, myName, targetId, msgId, header)
                                }
                            }
                            delay(400)
                        }
                    }
                }
            } catch (e: Exception) {}
            delay(2000)
            isGlobalSyncing = false
        }
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

    fun setExpectedGroupAvatar(payloadId: Long, groupId: String) {
        expectedChatImages[payloadId] = PendingFileInfo(0L, "GRP_AVATAR:$groupId", "IMG", "", true, groupId)
    }

    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val data = String(payload.asBytes()!!, Charsets.UTF_8)

                    if (data.startsWith("PING:") || data.startsWith("PONG:")) {
                        // Latency pings
                    } else if (data.startsWith("SYS:GROUP_CREATE|")) {
                        // El ACK real ya lo envía NexuMeshManager al desempacar el paquete MESH con el msgId correcto.
                        Log.d("NexuGroupDebug", "P2PManager dispatch SYS:GROUP_CREATE| context=${context != null}")
                        context?.let { GroupManager.handleGroupCreateCommand(it, data, endpointId) }
                    } else if (data.startsWith("SYS:GROUP_UPDATE|")) {
                        Log.d("NexuGroupDebug", "P2PManager dispatch SYS:GROUP_UPDATE| context=${context != null}")
                        context?.let { GroupManager.handleGroupUpdateCommand(it, data) }
                    } else if (data.startsWith("ACK:")) { // Doble Check Gris
                        val msgId = data.substringAfter("ACK:").toLongOrNull()
                        if (msgId != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                context?.let { ctx -> AppDatabase.getDatabase(ctx).nexuDao().updateStatus(msgId, Prefs(ctx).getUserProfile().id, 2) }
                            }
                            onAckReceivedListener?.invoke(msgId, 2)
                        }
                    } else if (data.startsWith("READ_ACK:")) { // Doble Check Azul
                        val msgId = data.substringAfter("READ_ACK:").toLongOrNull()
                        if (msgId != null) {
                            CoroutineScope(Dispatchers.IO).launch {
                                context?.let { ctx -> AppDatabase.getDatabase(ctx).nexuDao().updateStatus(msgId, Prefs(ctx).getUserProfile().id, 3) }
                            }
                            onAckReceivedListener?.invoke(msgId, 3)
                        }
                    } else if (data.startsWith("TXT:") || data.startsWith("EPH:")) {
                        val isEph = data.startsWith("EPH:")
                        val parts = data.split(":", limit = if (isEph) 4 else 3)

                        if (parts.size >= 3) {
                            val msgIdParsed = parts[1].toLongOrNull() ?: 0L
                            val finalMsgId = if (msgIdParsed == 0L) MsgIdGenerator.next() else msgIdParsed
                            val content = if (isEph) parts[3] else parts[2]
                            val duration = if (isEph) parts[2].toLongOrNull() ?: 0L else 0L

                            if (content.startsWith("SYS:GROUP_CREATE")) {
                                context?.let { GroupManager.handleGroupCreateCommand(it, content, endpointId) }
                                if (finalMsgId != 0L) sendAck(endpointId, finalMsgId)
                            } else if (content.startsWith("SYS:GROUP_UPDATE")) {
                                context?.let { GroupManager.handleGroupUpdateCommand(it, content) }
                                if (finalMsgId != 0L) sendAck(endpointId, finalMsgId)
                            } else if (content.startsWith("SYS:ADD_TIME:")) {
                                onMessageReceivedListener?.invoke(finalMsgId, data, null)
                            } else if (content == "SYS:TYPING:1" || content == "SYS:TYPING:0") {
                                onMessageReceivedListener?.invoke(finalMsgId, data, null)
                            } else {
                                val senderName = connectedNames[endpointId] ?: currentEndpointName ?: endpointId
                                procesarMensajeEntrante(senderName, finalMsgId, content, null, isGroup = false, originalSender = senderName, duration = duration)
                                if (finalMsgId != 0L) sendAck(endpointId, finalMsgId)
                            }
                        }
                    } else if (data.startsWith("GRP:")) {
                        val parts = data.split(":", limit = 5)
                        if (parts.size >= 5) {
                            val groupId = parts[1]
                            val senderId = parts[2]
                            val senderName = parts[3]
                            val realContent = parts[4]

                            if (realContent.startsWith("IMG:") || realContent.startsWith("VID:") || realContent.startsWith("DOC:") || realContent.startsWith("AUD:")) {
                                val subParts = realContent.split(":", limit = 4)
                                if (subParts.size == 4) {
                                    expectedChatImages[subParts[1].toLong()] = PendingFileInfo(subParts[2].toLong(), subParts[3], subParts[0], senderName, true, groupId)
                                }
                            } else {
                                val isEph = realContent.startsWith("EPH:")
                                val subParts = realContent.split(":", limit = if (isEph) 4 else 3)

                                if (subParts.size >= 3) {
                                    val msgIdParsed = subParts[1].toLongOrNull() ?: 0L
                                    val finalMsgId = if (msgIdParsed == 0L) MsgIdGenerator.next() else msgIdParsed
                                    val content = if (isEph) subParts[3] else subParts[2]
                                    val duration = if (isEph) subParts[2].toLongOrNull() ?: 0L else 0L

                                    if (content.startsWith("SYS:GROUP_CREATE")) {
                                        context?.let { GroupManager.handleGroupCreateCommand(it, content, endpointId) }
                                        if (finalMsgId != 0L) sendAck(endpointId, finalMsgId)
                                    } else if (content.startsWith("SYS:GROUP_UPDATE")) {
                                        context?.let { GroupManager.handleGroupUpdateCommand(it, content) }
                                        if (finalMsgId != 0L) sendAck(endpointId, finalMsgId)
                                    } else if (content.startsWith("SYS:ADD_TIME:")) {
                                        onMessageReceivedListener?.invoke(finalMsgId, data, null)
                                    } else if (content == "SYS:TYPING:1" || content == "SYS:TYPING:0") {
                                        onMessageReceivedListener?.invoke(finalMsgId, data, null)
                                    } else {
                                        procesarMensajeEntrante(groupId, finalMsgId, content, null, isGroup = true, originalSender = senderName, duration = duration)
                                        if (finalMsgId != 0L) sendAck(endpointId, finalMsgId)
                                    }
                                }
                            }
                        }
                    } else if (data.startsWith("IMG:") || data.startsWith("VID:") || data.startsWith("DOC:") || data.startsWith("AUD:")) {
                        val parts = data.split(":", limit = 4)
                        if (parts.size == 4) {
                            expectedChatImages[parts[1].toLong()] = PendingFileInfo(parts[2].toLong(), parts[3], parts[0], connectedNames[endpointId] ?: endpointId, false, null)
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
                val msgId = outgoingPayloadsTracker[update.payloadId] ?: expectedChatImages[update.payloadId]?.msgId
                if (msgId != null) onPayloadProgress?.invoke(msgId, progress)
            }
            else if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                if (outgoingPayloadsTracker.containsKey(update.payloadId)) {
                    val msgId = outgoingPayloadsTracker.remove(update.payloadId)!!
                    CoroutineScope(Dispatchers.IO).launch {
                        context?.let { ctx -> AppDatabase.getDatabase(ctx).nexuDao().updateStatus(msgId, Prefs(ctx).getUserProfile().id, 1) }
                    }
                    onMessageSentListener?.invoke(msgId)
                }

                if (expectedProfileImages.containsKey(update.payloadId)) {
                    val name = expectedProfileImages.remove(update.payloadId)!!
                    val filePayload = incomingFilePayloads.remove(update.payloadId)
                    if (filePayload != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val uri = renameAndMoveFileSafely(filePayload, "PROFILE")
                            withContext(Dispatchers.Main) {
                                if (uri != null) saveContactProfile(endpointId, name, uri.toString())
                            }
                        }
                    }
                }

                Log.d("NexuGroupDebug", "PayloadTransferUpdate SUCCESS id=${update.payloadId} expected=${expectedChatImages.containsKey(update.payloadId)} incomingFile=${incomingFilePayloads.containsKey(update.payloadId)} context=${context != null}")
                if (expectedChatImages.containsKey(update.payloadId)) {
                    val msgInfo = expectedChatImages.remove(update.payloadId)!!
                    val filePayload = incomingFilePayloads.remove(update.payloadId)

                    if (filePayload != null) {
                        CoroutineScope(Dispatchers.IO).launch {
                            val uri = renameAndMoveFileSafely(filePayload, msgInfo.type)

                            if (msgInfo.content.startsWith("GRP_AVATAR:")) {
                                val groupId = msgInfo.content.substringAfter("GRP_AVATAR:")
                                context?.let { ctx ->
                                    val db = AppDatabase.getDatabase(ctx)
                                    val existing = db.nexuDao().getGroup(groupId)
                                    if (existing != null && uri != null) {
                                        val updated = existing.copy(groupAvatar = uri.toString())
                                        db.nexuDao().insertGroup(updated)
                                    }
                                }
                            } else {
                                val text = msgInfo.content

                                if (!text.startsWith("SYS:")) {
                                    procesarMensajeEntrante(
                                        chatId = if (msgInfo.isGroup) msgInfo.groupId!! else msgInfo.senderName,
                                        msgId = msgInfo.msgId,
                                        text = text,
                                        uri = uri,
                                        isGroup = msgInfo.isGroup,
                                        originalSender = msgInfo.senderName
                                    )
                                }
                            }

                            withContext(Dispatchers.Main) {
                                sendAck(endpointId, msgInfo.msgId)
                            }
                        }
                    }
                }
            } else if (update.status == PayloadTransferUpdate.Status.FAILURE || update.status == PayloadTransferUpdate.Status.CANCELED) {
                if (outgoingPayloadsTracker.containsKey(update.payloadId)) {
                    val msgId = outgoingPayloadsTracker.remove(update.payloadId)!!
                    CoroutineScope(Dispatchers.IO).launch {
                        context?.let { ctx -> AppDatabase.getDatabase(ctx).nexuDao().updateStatus(msgId, Prefs(ctx).getUserProfile().id, -2) }
                    }
                    onMessageFailedListener?.invoke(msgId)
                }
                expectedProfileImages.remove(update.payloadId)
                expectedChatImages.remove(update.payloadId)
                incomingFilePayloads.remove(update.payloadId)
            }
        }
    }

    // Alerta de conexión/desconexión DENTRO del chat: evento local, no viaja por la red. Reutiliza
    // el mismo mecanismo de "mensaje de sistema" (status 99) que ya usan zumbidos y eventos de
    // grupo, así que se ve y persiste igual, y aparece en vivo si el chat de ese contacto está abierto.
    fun notificarEventoConexionEnChat(ctx: Context, peerName: String, conectado: Boolean) {
        val prefs = Prefs(ctx)
        val localUserId = prefs.getUserProfile().id
        if (localUserId.isEmpty()) return
        val texto = if (conectado) "🟢 $peerName se conectó" else "🔴 Se perdió la conexión con $peerName"
        val msgId = MsgIdGenerator.next()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(ctx)
                val entity = MessageEntity(
                    msgId = msgId,
                    localUserId = localUserId,
                    endpointId = peerName,
                    originalSender = peerName,
                    isGroup = false,
                    text = texto,
                    imagePath = null,
                    isMine = false,
                    status = 99,
                    timestamp = System.currentTimeMillis()
                )
                db.nexuDao().insertMessage(entity)

                withContext(Dispatchers.Main) {
                    if (activeChatIdentifier == peerName) {
                        onMessageReceivedListener?.invoke(msgId, "SYS:EVENT:$texto", null)
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun procesarMensajeEntrante(chatId: String, msgId: Long, text: String, uri: Uri?, isGroup: Boolean, originalSender: String? = null, duration: Long = 0L) {
        context?.let { ctx ->
            if (text.contains("SYS:REFRESH_GROUP") || text.contains("SYS:TYPING")) return

            val prefs = Prefs(ctx)
            val localUserId = prefs.getUserProfile().id
            if (localUserId.isEmpty()) return

            val senderName = originalSender ?: (currentEndpointName ?: "Desconocido")
            val expiresAt = if (duration > 0) System.currentTimeMillis() + duration else null

            val isNudge = text.contains("SYS:NUDGE")
            val isEvent = text.contains("SYS:EVENT:")

            val dbText = when {
                isNudge -> "¡Te han enviado un zumbido! \uD83D\uDCA5"
                isEvent -> text.substringAfter("SYS:EVENT:")
                else -> text
            }

            val msgStatus = if (isNudge || isEvent) 99 else 0

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getDatabase(ctx)

                    val exists = db.nexuDao().getRecentChats(localUserId).any { it.endpointId == chatId }
                    if (!exists) {
                        val isGroupChat = chatId.startsWith("GRP_")
                        val dummyMsg = MessageEntity(
                            msgId = MsgIdGenerator.next(),
                            localUserId = localUserId,
                            endpointId = chatId,
                            originalSender = senderName,
                            isGroup = isGroupChat,
                            text = "Chat inicializado",
                            imagePath = null,
                            isMine = false,
                            status = -1,
                            timestamp = System.currentTimeMillis() - 1000
                        )
                        db.nexuDao().insertMessage(dummyMsg)
                    }

                    val entity = MessageEntity(
                        msgId = msgId,
                        localUserId = localUserId,
                        endpointId = chatId,
                        originalSender = senderName,
                        isGroup = isGroup,
                        text = dbText,
                        imagePath = uri?.toString(),
                        isMine = false,
                        status = msgStatus,
                        timestamp = System.currentTimeMillis(),
                        expiresAt = expiresAt
                    )

                    db.nexuDao().insertMessage(entity)

                    val chatTitle = if(isGroup) {
                        db.nexuDao().getGroup(chatId)?.groupName ?: "Grupo"
                    } else {
                        senderName
                    }

                    withContext(Dispatchers.Main) {
                        if (isNudge) {
                            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                            val pattern = longArrayOf(0, 500, 200, 500, 200, 800)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator.vibrate(pattern, -1)
                            }

                            if (activeChatIdentifier != chatId) {
                                Toast.makeText(ctx, "¡$senderName te ha enviado un zumbido! \uD83D\uDCA5", Toast.LENGTH_LONG).show()
                            }
                        }

                        if (activeChatIdentifier == chatId) {
                            val uiText = if (isNudge) "SYS:NUDGE" else if (isEvent) "SYS:EVENT:$dbText" else dbText
                            if (isGroup) {
                                val prefix = if (duration > 0) "GRP_EPH_IN_CHAT:$senderName:$duration" else "GRP_IN_CHAT:$senderName"
                                onMessageReceivedListener?.invoke(msgId, "$prefix:$uiText", uri)
                            } else {
                                val ephPrefix = if (duration > 0) "EPH_IN_CHAT:$duration:" else ""
                                onMessageReceivedListener?.invoke(msgId, "$ephPrefix$uiText", uri)
                            }
                        } else {
                            if (!isNudge && !isEvent) {
                                val notifText = if(isGroup) "$senderName: $dbText" else dbText
                                mostrarNotificacion(ctx, chatId, chatTitle, notifText, isGroup)
                            }
                        }

                        val intent = Intent("com.offline.nexu.ACTION_UPDATE_CHAT_LIST")
                        intent.setPackage(ctx.packageName)
                        ctx.sendBroadcast(intent)
                    }
                } catch (e: Exception) { Log.e("P2P", "Error saving msg", e) }
            }
        }
    }

    private fun mostrarNotificacion(ctx: Context, chatId: String, chatTitle: String, mensaje: String, isGroup: Boolean) {
        val channelId = "nexu_messages"
        val notifManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Mensajes P2P", NotificationManager.IMPORTANCE_HIGH)
            notifManager.createNotificationChannel(channel)
        }
        val intent = Intent(ctx, ChatActivity::class.java).apply {
            if (isGroup) {
                putExtra("EXTRA_IS_GROUP", true)
                putExtra("EXTRA_GROUP_ID", chatId)
                putExtra("EXTRA_GROUP_NAME", chatTitle)
            } else {
                putExtra("EXTRA_USER_NAME", chatId)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(ctx, chatId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val textoMostrar = if (mensaje.isEmpty()) "🖼️ Multimedia recibida" else mensaje

        val builder = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(chatTitle)
            .setContentText(textoMostrar)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        notifManager.notify(chatId.hashCode(), builder.build())
    }

    private fun sendAck(endpointId: String, msgId: Long) {
        context?.let { ctx ->
            val ackPayload = Payload.fromBytes("ACK:$msgId".toByteArray(Charsets.UTF_8))
            val eps = NexuMeshManager.connectedEndpoints.toList()
            if (eps.isNotEmpty()) {
                Nearby.getConnectionsClient(ctx).sendPayload(eps, ackPayload)
            } else {
                Nearby.getConnectionsClient(ctx).sendPayload(endpointId, ackPayload)
            }
        }
    }

    private fun renameAndMoveFileSafely(filePayload: Payload, type: String): Uri? {
        if (context == null) return null
        try {
            val subFolder = when(type) {
                "VID" -> "NexuChat Videos"
                "DOC" -> "NexuChat Documents"
                "AUD" -> "NexuChat Voice Notes"
                "PROFILE" -> "Profiles"
                else -> "NexuChat Images"
            }
            val ext = when(type) { "VID" -> ".mp4"; "DOC" -> ".pdf"; "AUD" -> ".ogg"; else -> ".jpg" }
            val prefix = when(type) { "VID" -> "VID_"; "DOC" -> "DOC_"; "AUD" -> "AUD_"; "PROFILE" -> "PROF_"; else -> "IMG_" }

            val baseMediaDir = context!!.externalMediaDirs.firstOrNull() ?: context!!.getExternalFilesDir(null)
            val mediaDir = File(baseMediaDir, "NexuChat/Media/$subFolder")
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
                if (inputStream == null) {
                    try {
                        val javaFile = payloadFile.asJavaFile()
                        if (javaFile != null && javaFile.exists()) {
                            inputStream = javaFile.inputStream()
                        }
                    } catch (e: Exception) { }
                }
            }
            if (inputStream == null) return null

            inputStream.use { input -> FileOutputStream(newFile).use { output -> input.copyTo(output, 64 * 1024) } }

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