package com.offline.nexu.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.network.NexuMeshManager
import com.offline.nexu.ui.home.P2PManager

class ConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val endpointId = intent.getStringExtra("ENDPOINT_ID") ?: return
        val notifId = intent.getIntExtra("NOTIF_ID", -1)
        val nombre = intent.getStringExtra("NOMBRE") ?: "Usuario"

        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notifId != -1) {
            notifManager.cancel(notifId)
        }

        when (action) {
            "ACCEPT_CONNECTION" -> {
                P2PManager.currentEndpointId = endpointId
                P2PManager.currentEndpointName = nombre
                NexuMeshManager.aceptarConexionPendiente(context, endpointId)
                Toast.makeText(context, "Aceptando conexión de $nombre...", Toast.LENGTH_SHORT).show()
            }
            "RECONNECT_CONNECTION" -> {
                Toast.makeText(context, "Solicitando reconexión con $nombre...", Toast.LENGTH_SHORT).show()
                val prefs = Prefs(context)
                val profile = prefs.getUserProfile()
                val displayEmoji = if (profile.avatar.isNotEmpty() && !profile.avatar.startsWith("/")) profile.avatar else "👤"
                val miPerfilP2P = "$displayEmoji|${profile.name}|${prefs.getDeviceId()}"

                // Callback correcto requerido por Nearby Connections para reconectar
                val lifecycleCallback = object : ConnectionLifecycleCallback() {
                    override fun onConnectionInitiated(endpId: String, info: ConnectionInfo) {
                        Nearby.getConnectionsClient(context).acceptConnection(endpId, NexuMeshManager.getMeshPayloadCallback(context))
                    }

                    override fun onConnectionResult(endpId: String, result: ConnectionResolution) {
                        if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                            NexuMeshManager.connectedEndpoints.add(endpId)
                            P2PManager.currentEndpointId = endpId
                            P2PManager.currentEndpointName = nombre
                            P2PManager.sendProfileHandshake(endpId, profile, context)
                            Toast.makeText(context, "¡Reconectado con $nombre!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Reconexión fallida", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onDisconnected(endpId: String) {
                        NexuMeshManager.connectedEndpoints.remove(endpId)
                        if (P2PManager.currentEndpointId == endpId) {
                            P2PManager.currentEndpointId = null
                            P2PManager.currentEndpointName = null
                        }
                        Toast.makeText(context, "Desconectado de $nombre", Toast.LENGTH_SHORT).show()
                    }
                }

                Nearby.getConnectionsClient(context).requestConnection(
                    miPerfilP2P,
                    endpointId,
                    lifecycleCallback
                ).addOnFailureListener {
                    Toast.makeText(context, "No se pudo iniciar la reconexión", Toast.LENGTH_SHORT).show()
                }
            }
            "REJECT_CONNECTION" -> {
                NexuMeshManager.rechazarConexionPendiente(context, endpointId)
                Toast.makeText(context, "Conexión rechazada", Toast.LENGTH_SHORT).show()
            }
        }
    }
}