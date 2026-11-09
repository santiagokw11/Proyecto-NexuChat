package com.offline.nexu.data.network

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.ui.home.P2PManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NexuMeshManager(private val context: Context, private val nickname: String) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.offline.nexu.MESH_NETWORK"

    private var pingStartTime: Long = 0
    private var distanceListener: ((String) -> Unit)? = null

    private val pingHandler = Handler(Looper.getMainLooper())
    private var pingRunnable: Runnable? = null

    private val endpointNames = mutableMapOf<String, String>()

    companion object {
        var payloadCallbackPendiente: PayloadCallback? = null
        var distanceUpdateListener: ((String) -> Unit)? = null

        val connectedEndpoints = mutableSetOf<String>()
        val connectedNames = mutableMapOf<String, String>()
        val seenMessagesCache = mutableSetOf<Long>()
        val incomingPayloadMap = mutableMapOf<Long, Long>()

        fun aceptarConexionPendiente(context: Context, endpointId: String) {
            payloadCallbackPendiente?.let { callback ->
                Nearby.getConnectionsClient(context).acceptConnection(endpointId, callback)
            }
        }

        fun rechazarConexionPendiente(context: Context, endpointId: String) {
            Nearby.getConnectionsClient(context).rejectConnection(endpointId)
        }

        private fun mostrarNotificacionMensaje(context: Context, chatName: String, text: String, isGroup: Boolean) {
            val channelId = "nexu_chat_messages"
            val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Mensajes de Chat", NotificationManager.IMPORTANCE_HIGH)
                notifManager.createNotificationChannel(channel)
            }

            val intent = Intent(context, Class.forName("com.offline.nexu.ui.chat.ChatActivity")).apply {
                if (isGroup) {
                    putExtra("EXTRA_GROUP_ID", chatName)
                    putExtra("EXTRA_IS_GROUP", true)
                } else {
                    putExtra("EXTRA_USER_NAME", chatName)
                    putExtra("EXTRA_IS_GROUP", false)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(context, chatName.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(chatName)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            notifManager.notify(chatName.hashCode(), builder.build())
        }

        fun getMeshPayloadCallback(context: Context): PayloadCallback {
            return object : PayloadCallback() {
                override fun onPayloadReceived(endpointId: String, payload: Payload) {
                    if (payload.type == Payload.Type.BYTES) {
                        val data = String(payload.asBytes()!!, Charsets.UTF_8)
                        if (data.startsWith("PING:")) {
                            val originalTime = data.substringAfter("PING:")
                            Nearby.getConnectionsClient(context).sendPayload(endpointId, Payload.fromBytes("PONG:$originalTime".toByteArray()))
                            return
                        } else if (data.startsWith("PONG:")) {
                            return
                        } else if (data.startsWith("MESH|")) {
                            val parts = data.split("|", limit = 7)
                            if (parts.size >= 7) {
                                val msgId = parts[1].toLongOrNull() ?: 0L
                                val targetId = parts[2]
                                val senderId = parts[3]
                                val senderName = parts[4]
                                val ttl = parts[5].toIntOrNull() ?: 0
                                val realContent = parts[6]

                                val prefs = Prefs(context)
                                val myLocalUserId = prefs.getUserProfile().id
                                val myLocalUserName = prefs.getUserProfile().name

                                val isForMe = targetId == myLocalUserId || targetId == myLocalUserName || targetId == P2PManager.activeChatIdentifier

                                // SOLUCIÓN: ENVIAR ACK INMEDIATAMENTE PARA QUE APAREZCA EL CHULITO EN EL OTRO CELULAR
                                if (msgId != 0L && isForMe) {
                                    val ackPayload = Payload.fromBytes("ACK:$msgId".toByteArray(Charsets.UTF_8))
                                    Nearby.getConnectionsClient(context).sendPayload(endpointId, ackPayload)
                                }

                                if (msgId != 0L && seenMessagesCache.contains(msgId)) return
                                if (msgId != 0L) seenMessagesCache.add(msgId)

                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        val db = AppDatabase.getDatabase(context)
                                        var textoToSave: String? = null
                                        var durationStr: String? = null

                                        if (realContent.startsWith("TXT:")) {
                                            textoToSave = realContent.substringAfter("TXT:").substringAfter(":")
                                        } else if (realContent.startsWith("EPH:")) {
                                            val ephParts = realContent.split(":", limit = 4)
                                            if (ephParts.size >= 4) {
                                                durationStr = ephParts[2]
                                                textoToSave = ephParts[3]
                                            }
                                        }

                                        if (textoToSave != null && !realContent.contains("SYS:")) {
                                            val expiresAt = if ((durationStr?.toLongOrNull() ?: 0L) > 0) System.currentTimeMillis() + durationStr!!.toLong() else null
                                            val entity = MessageEntity(
                                                msgId = msgId,
                                                localUserId = myLocalUserId,
                                                endpointId = senderName,
                                                originalSender = senderName,
                                                isGroup = false,
                                                text = textoToSave,
                                                imagePath = null,
                                                isMine = false,
                                                status = 0,
                                                timestamp = System.currentTimeMillis(),
                                                expiresAt = expiresAt
                                            )
                                            db.nexuDao().insertMessage(entity)

                                            // SOLUCIÓN: MOSTRAR NOTIFICACIÓN SI EL CHAT NO ESTÁ ABIERTO
                                            if (isForMe && P2PManager.activeChatIdentifier != senderName) {
                                                mostrarNotificacionMensaje(context, senderName, textoToSave, false)
                                            }
                                        }
                                    } catch(e: Exception) {}
                                }

                                if (isForMe) {
                                    val cleanPayload = Payload.fromBytes(realContent.toByteArray(Charsets.UTF_8))
                                    incomingPayloadMap[payload.id] = cleanPayload.id
                                    P2PManager.payloadCallback.onPayloadReceived(senderName, cleanPayload)
                                } else if (ttl > 0) {
                                    val forwardData = "MESH|$msgId|$targetId|$senderId|$senderName|${ttl - 1}|$realContent"
                                    val forwardPayload = Payload.fromBytes(forwardData.toByteArray(Charsets.UTF_8))
                                    val eps = connectedEndpoints.filter { it != endpointId }
                                    if (eps.isNotEmpty()) {
                                        Nearby.getConnectionsClient(context).sendPayload(eps, forwardPayload)
                                    }
                                }
                            }
                            return
                        } else if (data.startsWith("GMESH|")) {
                            val parts = data.split("|", limit = 7)
                            if (parts.size >= 7) {
                                val msgId = parts[1].toLongOrNull() ?: 0L
                                val groupId = parts[2]
                                val senderId = parts[3]
                                val senderName = parts[4]
                                val ttl = parts[5].toIntOrNull() ?: 0
                                val realContent = parts[6]

                                CoroutineScope(Dispatchers.IO).launch {
                                    try {
                                        val db = AppDatabase.getDatabase(context)
                                        val group = db.nexuDao().getGroup(groupId)
                                        val isMyGroup = group != null

                                        // SOLUCIÓN: ENVIAR ACK INMEDIATAMENTE A GRUPOS
                                        if (msgId != 0L && isMyGroup) {
                                            val ackPayload = Payload.fromBytes("ACK:$msgId".toByteArray(Charsets.UTF_8))
                                            Nearby.getConnectionsClient(context).sendPayload(endpointId, ackPayload)
                                        }

                                        if (msgId != 0L && seenMessagesCache.contains(msgId)) return@launch
                                        if (msgId != 0L) seenMessagesCache.add(msgId)

                                        if (isMyGroup) {
                                            var textoToSave: String? = null
                                            if (realContent.startsWith("TXT:")) textoToSave = realContent.substringAfter("TXT:").substringAfter(":")

                                            if (textoToSave != null && !realContent.contains("SYS:")) {
                                                val entity = MessageEntity(
                                                    msgId = msgId,
                                                    localUserId = Prefs(context).getUserProfile().id,
                                                    endpointId = groupId,
                                                    originalSender = senderName,
                                                    isGroup = true,
                                                    text = textoToSave,
                                                    imagePath = null,
                                                    isMine = false,
                                                    status = 0,
                                                    timestamp = System.currentTimeMillis(),
                                                    expiresAt = null
                                                )
                                                db.nexuDao().insertMessage(entity)

                                                // SOLUCIÓN: MOSTRAR NOTIFICACIÓN DE GRUPO SI EL CHAT NO ESTÁ ABIERTO
                                                if (P2PManager.activeChatIdentifier != groupId) {
                                                    mostrarNotificacionMensaje(context, group!!.groupName, "$senderName: $textoToSave", true)
                                                }
                                            }
                                        }

                                        val cleanPayload = Payload.fromBytes("GRP:$groupId:$senderId:$senderName:$realContent".toByteArray(Charsets.UTF_8))
                                        incomingPayloadMap[payload.id] = cleanPayload.id
                                        P2PManager.payloadCallback.onPayloadReceived(endpointId, cleanPayload)

                                        if (ttl > 0) {
                                            val forwardData = "GMESH|$msgId|$groupId|$senderId|$senderName|${ttl - 1}|$realContent"
                                            val forwardPayload = Payload.fromBytes(forwardData.toByteArray(Charsets.UTF_8))
                                            val eps = connectedEndpoints.filter { it != endpointId }
                                            if (eps.isNotEmpty()) {
                                                Nearby.getConnectionsClient(context).sendPayload(eps, forwardPayload)
                                            }
                                        }
                                    } catch(e: Exception) {}
                                }
                            }
                            return
                        }
                    }
                    P2PManager.payloadCallback.onPayloadReceived(endpointId, payload)
                }

                override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
                    val mappedId = incomingPayloadMap[update.payloadId]
                    if (mappedId != null) {
                        val cleanUpdate = PayloadTransferUpdate.Builder()
                            .setPayloadId(mappedId)
                            .setStatus(update.status)
                            .setTotalBytes(update.totalBytes)
                            .setBytesTransferred(update.bytesTransferred)
                            .build()
                        P2PManager.payloadCallback.onPayloadTransferUpdate(endpointId, cleanUpdate)
                    } else {
                        P2PManager.payloadCallback.onPayloadTransferUpdate(endpointId, update)
                    }
                }
            }
        }

        fun enviarMensajeMesh(context: Context, senderId: String, senderName: String, targetId: String, msgId: Long, mensajeRaw: String) {
            if (msgId != 0L) seenMessagesCache.add(msgId)
            val ttl = 4
            val meshPacket = "MESH|$msgId|$targetId|$senderId|$senderName|$ttl|$mensajeRaw"
            val payload = Payload.fromBytes(meshPacket.toByteArray(Charsets.UTF_8))

            P2PManager.outgoingPayloadsTracker[payload.id] = msgId

            val eps = connectedEndpoints.toList()
            if (eps.isNotEmpty()) {
                Nearby.getConnectionsClient(context).sendPayload(eps, payload)
            }
        }

        fun enviarMensajeGrupoMesh(context: Context, senderId: String, senderName: String, groupId: String, msgId: Long, mensajeRaw: String) {
            if (msgId != 0L) seenMessagesCache.add(msgId)
            val ttl = 4
            val meshPacket = "GMESH|$msgId|$groupId|$senderId|$senderName|$ttl|$mensajeRaw"
            val payload = Payload.fromBytes(meshPacket.toByteArray(Charsets.UTF_8))

            P2PManager.outgoingPayloadsTracker[payload.id] = msgId

            val eps = connectedEndpoints.toList()
            if (eps.isNotEmpty()) {
                Nearby.getConnectionsClient(context).sendPayload(eps, payload)
            }
        }
    }

    private val payloadCallback = getMeshPayloadCallback(context)

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            connectedNames[endpointId] = connectionInfo.endpointName
            payloadCallbackPendiente = payloadCallback

            val partes = connectionInfo.endpointName.split("|")
            val nombreReal = if (partes.size > 1) partes[1] else connectionInfo.endpointName
            endpointNames[endpointId] = nombreReal

            val activeActivity = P2PManager.currentActivity
            if (P2PManager.currentEndpointName == nombreReal || P2PManager.activeChatIdentifier == nombreReal) {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            } else if (activeActivity != null && activeActivity::class.java.simpleName != "ChatActivity" && activeActivity::class.java.simpleName != "HomeActivity") {
                mostrarNotificacionConexion(endpointId, nombreReal)
            } else if (activeActivity == null) {
                mostrarNotificacionConexion(endpointId, nombreReal)
            }
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                endpointNames[endpointId]?.let { nombreReal ->
                    P2PManager.connectedNames[endpointId] = nombreReal
                    if (P2PManager.currentEndpointId == null) {
                        P2PManager.currentEndpointId = endpointId
                        P2PManager.currentEndpointName = nombreReal
                    }
                    P2PManager.inicializarChatSiNoExiste(context, endpointId, nombreReal)
                }
                iniciarPingPeriodico(endpointId)
                P2PManager.reenviarMensajesPendientesGlobal(context)
            } else {
                endpointNames.remove(endpointId)
                P2PManager.connectedNames.remove(endpointId)
            }
        }
        override fun onDisconnected(endpointId: String) {
            detenerPingPeriodico()
            connectedEndpoints.remove(endpointId)
            endpointNames.remove(endpointId)
            P2PManager.connectedNames.remove(endpointId)
            if (P2PManager.currentEndpointId == endpointId) {
                P2PManager.currentEndpointId = null
                P2PManager.currentEndpointName = null

                if (P2PManager.connectedNames.isNotEmpty()) {
                    val first = P2PManager.connectedNames.entries.first()
                    P2PManager.currentEndpointId = first.key
                    P2PManager.currentEndpointName = first.value
                }
                distanceUpdateListener?.invoke("Desconectado")
            }
        }
    }

    private fun iniciarPingPeriodico(endpointId: String) {
        pingRunnable = object : Runnable {
            override fun run() {
                medirLatencia(endpointId)
                pingHandler.postDelayed(this, 3000)
            }
        }
        pingHandler.post(pingRunnable!!)
    }

    private fun detenerPingPeriodico() {
        pingRunnable?.let { pingHandler.removeCallbacks(it) }
    }

    fun iniciarModoWalkieTalkie() {
        try {
            connectionsClient.stopDiscovery()
            connectionsClient.stopAdvertising()
        } catch (e: Exception) {}

        // 1. LEER LA CONFIGURACIÓN DE PRIVACIDAD DEL USUARIO
        val sharedPrefs = context.getSharedPreferences("NexuSettings", Context.MODE_PRIVATE)
        val isRadarHidden = sharedPrefs.getBoolean("radar_hidden", false)

        // 2. MODO FANTASMA: Solo anunciarnos si NO está oculto
        if (!isRadarHidden) {
            val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
            connectionsClient.startAdvertising(nickname, SERVICE_ID, connectionLifecycleCallback, options)
        }

        // 3. SIEMPRE seguir descubriendo (Para que tú sí puedas ver a los demás)
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                // Al encontrar a alguien, solicitamos conexión silenciosa para armar la malla
                connectionsClient.requestConnection(nickname, endpointId, connectionLifecycleCallback)
            }
            override fun onEndpointLost(endpointId: String) {}
        }, discoveryOptions)
    }

    fun setVisibilidadRadar(visible: Boolean) {
        if (visible) {
            // El usuario desactivó el Modo Fantasma, volvemos a anunciarnos
            val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
            connectionsClient.startAdvertising(nickname, SERVICE_ID, connectionLifecycleCallback, options)
        } else {
            // El usuario activó el Modo Fantasma, nos volvemos invisibles de inmediato
            connectionsClient.stopAdvertising()
        }
    }

    private fun mostrarNotificacionConexion(endpointId: String, endpointName: String) {
        val channelId = "nexu_connections"
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Solicitudes de Radar", NotificationManager.IMPORTANCE_HIGH)
            notifManager.createNotificationChannel(channel)
        }
        val notifId = endpointId.hashCode()

        val acceptIntent = Intent(context, Class.forName("com.offline.nexu.notifications.ConnectionReceiver")).apply {
            action = "ACCEPT_CONNECTION"
            putExtra("ENDPOINT_ID", endpointId)
            putExtra("NOTIF_ID", notifId)
            putExtra("NOMBRE", endpointName)
        }
        val acceptPending = PendingIntent.getBroadcast(context, 0, acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val rejectIntent = Intent(context, Class.forName("com.offline.nexu.notifications.ConnectionReceiver")).apply {
            action = "REJECT_CONNECTION"
            putExtra("ENDPOINT_ID", endpointId)
            putExtra("NOTIF_ID", notifId)
            putExtra("NOMBRE", endpointName)
        }
        val rejectPending = PendingIntent.getBroadcast(context, 1, rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Nueva conexión entrante")
            .setContentText("$endpointName quiere conectarse.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(R.drawable.ic_close, "Rechazar", rejectPending)
            .addAction(R.drawable.ic_check, "Aceptar", acceptPending)
            .setAutoCancel(true)
        notifManager.notify(notifId, builder.build())
    }

    fun enviarDatos(endpointId: String, mensaje: String) {
        val payload = Payload.fromBytes(mensaje.toByteArray())
        connectionsClient.sendPayload(endpointId, payload)
    }

    private fun medirLatencia(endpointId: String) {
        pingStartTime = System.currentTimeMillis()
        enviarDatos(endpointId, "PING:$pingStartTime")
    }

    fun detener() {
        detenerPingPeriodico()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectedEndpoints.clear()
        endpointNames.clear()
    }
}