package com.offline.nexu.data.network

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
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

        fun aceptarConexionPendiente(context: Context, endpointId: String) {
            payloadCallbackPendiente?.let { callback ->
                Nearby.getConnectionsClient(context).acceptConnection(endpointId, callback)
            }
        }

        fun rechazarConexionPendiente(context: Context, endpointId: String) {
            Nearby.getConnectionsClient(context).rejectConnection(endpointId)
        }

        fun enviarMensajeMesh(context: Context, localUserId: String, localUserName: String, targetName: String, msgId: Long, text: String) {
            val endpointId = P2PManager.connectedNames.entries.find { it.value == targetName }?.key ?: targetName
            val payload = Payload.fromBytes("TXT:$msgId:$text".toByteArray(Charsets.UTF_8))
            Nearby.getConnectionsClient(context).sendPayload(endpointId, payload)
        }

        fun enviarMensajeGrupoMesh(context: Context, localUserId: String, localUserName: String, groupId: String, msgId: Long, text: String) {
            val payload = Payload.fromBytes("TXT:$msgId:$text".toByteArray(Charsets.UTF_8))
            P2PManager.connectedEndpoints.forEach { ep ->
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
                    enviarDatos(endpointId, "PONG:$originalTime")
                    return
                } else if (data.startsWith("PONG:")) {
                    val originalTime = data.substringAfter("PONG:").toLongOrNull() ?: return
                    calcularDistanciaAproximada(System.currentTimeMillis() - originalTime)
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
            payloadCallbackPendiente = payloadCallback

            val activeActivity = P2PManager.currentActivity

            if (P2PManager.currentEndpointName == connectionInfo.endpointName) {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            }
            // Si la app está abierta PERO no estamos en la ventana de Chat, mostramos un Dialog hermoso
            else if (activeActivity != null && activeActivity::class.java.simpleName != "ChatActivity") {
                mostrarDialogoGlobal(activeActivity, endpointId, connectionInfo.endpointName)
            }
            // Si estamos fuera de la app o dentro del ChatActivity, mostramos Notificación
            else {
                mostrarNotificacionConexion(endpointId, connectionInfo.endpointName)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                P2PManager.currentEndpointId = endpointId
                iniciarPingPeriodico(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
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

    fun enviarDatos(endpointId: String, mensaje: String) {
        val payload = Payload.fromBytes(mensaje.toByteArray())
        connectionsClient.sendPayload(endpointId, payload)
    }

    private fun medirLatencia(endpointId: String) {
        pingStartTime = System.currentTimeMillis()
        enviarDatos(endpointId, "PING:$pingStartTime")
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
    }
}