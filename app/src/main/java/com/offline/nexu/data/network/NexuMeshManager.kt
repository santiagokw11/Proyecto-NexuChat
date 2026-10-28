package com.offline.nexu.data.network

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.offline.nexu.ui.home.P2PManager

class NexuMeshManager(private val context: Context, private val nickname: String) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_CLUSTER
    private val SERVICE_ID = "com.offline.nexu.MESH_NETWORK"

    private var pingStartTime: Long = 0
    private var distanceListener: ((String) -> Unit)? = null

    private val pingHandler = Handler(Looper.getMainLooper())
    private var pingRunnable: Runnable? = null

    companion object {
        var payloadCallbackPendiente: PayloadCallback? = null
        var distanceUpdateListener: ((String) -> Unit)? = null

        val connectedEndpoints = mutableSetOf<String>()
        val connectedNames = mutableMapOf<String, String>()
        val seenMessagesCache = mutableSetOf<Long>()

        fun aceptarConexionPendiente(context: Context, endpointId: String) {
            payloadCallbackPendiente?.let { callback ->
                Nearby.getConnectionsClient(context).acceptConnection(endpointId, callback)
            }
        }

        fun rechazarConexionPendiente(context: Context, endpointId: String) {
            Nearby.getConnectionsClient(context).rejectConnection(endpointId)
        }

        fun enviarMensajeMesh(context: Context, senderId: String, targetId: String, msgId: Long, mensajeRaw: String) {
            seenMessagesCache.add(msgId)
            val ttl = 4
            val meshPacket = "MESH|$msgId|$targetId|$senderId|$ttl|$mensajeRaw"
            val payload = Payload.fromBytes(meshPacket.toByteArray(Charsets.UTF_8))

            connectedEndpoints.forEach { ep ->
                Nearby.getConnectionsClient(context).sendPayload(ep, payload)
            }
        }

        fun enviarMensajeGrupoMesh(context: Context, senderId: String, groupId: String, msgId: Long, mensajeRaw: String) {
            seenMessagesCache.add(msgId)
            val ttl = 4
            val meshPacket = "GMESH|$msgId|$groupId|$senderId|$ttl|$mensajeRaw"
            val payload = Payload.fromBytes(meshPacket.toByteArray(Charsets.UTF_8))

            connectedEndpoints.forEach { ep ->
                Nearby.getConnectionsClient(context).sendPayload(ep, payload)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val data = String(payload.asBytes()!!, Charsets.UTF_8)

                if (data.startsWith("PING:")) {
                    val originalTime = data.substringAfter("PING:")
                    enviarDatosRaw(endpointId, "PONG:$originalTime")
                    return
                } else if (data.startsWith("PONG:")) {
                    val originalTime = data.substringAfter("PONG:").toLongOrNull() ?: return
                    calcularDistanciaAproximada(System.currentTimeMillis() - originalTime)
                    return
                }

                if (data.startsWith("MESH|")) {
                    val parts = data.split("|", limit = 6)
                    if (parts.size >= 6) {
                        val msgId = parts[1].toLongOrNull() ?: return
                        val targetId = parts[2]
                        val senderId = parts[3]
                        val ttl = parts[4].toIntOrNull() ?: return
                        val realContent = parts[5]

                        if (seenMessagesCache.contains(msgId)) return
                        seenMessagesCache.add(msgId)

                        if (targetId == nickname) {
                            val cleanPayload = Payload.fromBytes(realContent.toByteArray(Charsets.UTF_8))
                            P2PManager.payloadCallback.onPayloadReceived(senderId, cleanPayload)
                        } else if (ttl > 0) {
                            val forwardData = "MESH|$msgId|$targetId|$senderId|${ttl - 1}|$realContent"
                            val forwardPayload = Payload.fromBytes(forwardData.toByteArray(Charsets.UTF_8))
                            connectedEndpoints.forEach { ep ->
                                if (ep != endpointId) {
                                    connectionsClient.sendPayload(ep, forwardPayload)
                                }
                            }
                        }
                    }
                    return
                }

                if (data.startsWith("GMESH|")) {
                    val parts = data.split("|", limit = 6)
                    if (parts.size >= 6) {
                        val msgId = parts[1].toLongOrNull() ?: return
                        val groupId = parts[2]
                        val senderId = parts[3]
                        val ttl = parts[4].toIntOrNull() ?: return
                        val realContent = parts[5]

                        if (seenMessagesCache.contains(msgId)) return
                        seenMessagesCache.add(msgId)

                        val cleanPayload = Payload.fromBytes("GRP:$groupId:$senderId:$realContent".toByteArray(Charsets.UTF_8))
                        P2PManager.payloadCallback.onPayloadReceived(senderId, cleanPayload)

                        if (ttl > 0) {
                            val forwardData = "GMESH|$msgId|$groupId|$senderId|${ttl - 1}|$realContent"
                            val forwardPayload = Payload.fromBytes(forwardData.toByteArray(Charsets.UTF_8))
                            connectedEndpoints.forEach { ep ->
                                if (ep != endpointId) {
                                    connectionsClient.sendPayload(ep, forwardPayload)
                                }
                            }
                        }
                    }
                    return
                }
            }
            P2PManager.payloadCallback.onPayloadReceived(endpointId, payload)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            P2PManager.payloadCallback.onPayloadTransferUpdate(endpointId, update)
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            connectedNames[endpointId] = connectionInfo.endpointName
            payloadCallbackPendiente = payloadCallback
            val activeActivity = P2PManager.currentActivity

            if (P2PManager.currentEndpointName == connectionInfo.endpointName) {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            } else if (activeActivity != null && activeActivity::class.java.simpleName != "ChatActivity") {
                mostrarDialogoGlobal(activeActivity, endpointId, connectionInfo.endpointName)
            } else {
                mostrarNotificacionConexion(endpointId, connectionInfo.endpointName)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                P2PManager.currentEndpointId = endpointId
                iniciarPingPeriodico(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            connectedNames.remove(endpointId)
            detenerPingPeriodico()
            if (P2PManager.currentEndpointId == endpointId) {
                P2PManager.currentEndpointId = null
                distanceUpdateListener?.invoke("Desconectado")
            }
        }
    }

    private fun mostrarDialogoGlobal(activity: android.app.Activity, endpointId: String, endpointName: String) {
        activity.runOnUiThread {
            android.app.AlertDialog.Builder(activity)
                .setTitle("Radar Nexu 📡")
                .setMessage("¡$endpointName quiere conectarse contigo!")
                .setCancelable(false)
                .setPositiveButton("Aceptar") { dialog, _ ->
                    aceptarConexionPendiente(activity, endpointId)
                    dialog.dismiss()
                }
                .setNegativeButton("Rechazar") { dialog, _ ->
                    rechazarConexionPendiente(activity, endpointId)
                    dialog.dismiss()
                }
                .show()
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
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(nickname, SERVICE_ID, connectionLifecycleCallback, options)
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                connectionsClient.requestConnection(nickname, endpointId, connectionLifecycleCallback)
            }
            override fun onEndpointLost(endpointId: String) {}
        }, discoveryOptions)
    }

    fun setVisibilidadRadar(visible: Boolean) {
        if (visible) {
            val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
            connectionsClient.startAdvertising(nickname, SERVICE_ID, connectionLifecycleCallback, options)
        } else {
            connectionsClient.stopAdvertising()
        }
    }

    private fun mostrarNotificacionConexion(endpointId: String, endpointName: String) {
        val channelId = "nexu_connections"
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Solicitudes de Radar", android.app.NotificationManager.IMPORTANCE_HIGH)
            notifManager.createNotificationChannel(channel)
        }
        val notifId = endpointId.hashCode()
        val acceptIntent = Intent(context, Class.forName("com.offline.nexu.notifications.ConnectionReceiver")).apply {
            action = "ACCEPT_CONNECTION"; putExtra("ENDPOINT_ID", endpointId); putExtra("NOTIF_ID", notifId)
        }
        val acceptPending = android.app.PendingIntent.getBroadcast(context, 0, acceptIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

        val rejectIntent = Intent(context, Class.forName("com.offline.nexu.notifications.ConnectionReceiver")).apply {
            action = "REJECT_CONNECTION"; putExtra("ENDPOINT_ID", endpointId); putExtra("NOTIF_ID", notifId)
        }
        val rejectPending = android.app.PendingIntent.getBroadcast(context, 1, rejectIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Nueva conexión entrante")
            .setContentText("$endpointName quiere conectarse.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_add, "Aceptar", acceptPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Rechazar", rejectPending)
            .setAutoCancel(true)
        notifManager.notify(notifId, builder.build())
    }

    fun enviarDatosRaw(endpointId: String, mensaje: String) {
        val payload = Payload.fromBytes(mensaje.toByteArray())
        connectionsClient.sendPayload(endpointId, payload)
    }

    private fun medirLatencia(endpointId: String) {
        pingStartTime = System.currentTimeMillis()
        enviarDatosRaw(endpointId, "PING:$pingStartTime")
    }

    private fun calcularDistanciaAproximada(latenciaMs: Long) {
        val estimacion = when {
            latenciaMs < 20 -> "Muy Cerca (< 5m)"
            latenciaMs in 20..60 -> "Cerca (5m - 20m)"
            latenciaMs in 61..150 -> "Rango Medio (20m - 50m)"
            else -> "Límite de Rango (> 50m)"
        }
        distanceListener?.invoke(estimacion)
        distanceUpdateListener?.invoke(estimacion)
    }

    fun setDistanceListener(listener: (String) -> Unit) { this.distanceListener = listener }

    fun detener() {
        detenerPingPeriodico()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectedEndpoints.clear()
    }
}