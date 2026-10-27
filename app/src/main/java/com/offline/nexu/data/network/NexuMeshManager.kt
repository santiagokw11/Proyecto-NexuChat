package com.offline.nexu.data.network

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.offline.nexu.ui.home.P2PManager
import java.util.Collections

class NexuMeshManager(private val context: Context, private val nickname: String) {

    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_CLUSTER // Ideal para redes Mesh / Colmena
    private val SERVICE_ID = "com.offline.nexu.MESH_NETWORK"

    // Variables para el cálculo de latencia
    private var pingStartTime: Long = 0
    private var distanceListener: ((String) -> Unit)? = null

    // Callback para recibir mensajes y pings
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val data = String(payload.asBytes()!!)

                when {
                    data.startsWith("PING:") -> {
                        // Nos hacen ping, respondemos con pong y el mismo timestamp
                        val originalTime = data.substringAfter("PING:")
                        enviarDatos(endpointId, "PONG:$originalTime")
                    }
                    data.startsWith("PONG:") -> {
                        // Recibimos nuestro pong, calculamos latencia
                        val originalTime = data.substringAfter("PONG:").toLong()
                        val latencyMs = System.currentTimeMillis() - originalTime
                        calcularDistanciaAproximada(latencyMs)
                    }
                    else -> {
                        // Es un mensaje de chat normal
                        Log.d("NexuMesh", "Mensaje recibido de $endpointId: $data")
                    }
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
            // En la fase 1 aceptamos todas las conexiones automáticamente
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d("NexuMesh", "Conectado al nodo: $endpointId")
                connectedEndpoints.add(endpointId)
                // Iniciamos la prueba de latencia al conectarnos
                medirLatencia(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d("NexuMesh", "Desconectado del nodo: $endpointId")
            connectedEndpoints.remove(endpointId)
        }
    }

    fun iniciarModoWalkieTalkie() {
        // Empieza a anunciar y descubrir al mismo tiempo (Nodo híbrido)
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

    fun setVisibilidadRadar(isVisible: Boolean) {
        if (!isVisible) {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
        } else {
            iniciarModoWalkieTalkie()
        }
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
        // La latencia inalámbrica fluctúa, esta es una aproximación cualitativa
        val estimacion = when {
            latenciaMs < 20 -> "Muy Cerca (< 5m)"
            latenciaMs in 20..60 -> "Cerca (5m - 20m)"
            latenciaMs in 61..150 -> "Rango Medio (20m - 50m)"
            else -> "Límite de Rango Mesh (> 50m)"
        }
        Log.d("NexuMesh", "Latencia: ${latenciaMs}ms -> Distancia: $estimacion")
        distanceListener?.invoke(estimacion)
    }

    fun setDistanceListener(listener: (String) -> Unit) {
        this.distanceListener = listener
    }

    fun detener() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
    }

    companion object {
        val connectedEndpoints: MutableSet<String> = Collections.synchronizedSet(HashSet())

        fun getMeshPayloadCallback(context: Context): PayloadCallback {
            return P2PManager.payloadCallback
        }

        fun aceptarConexionPendiente(context: Context, endpointId: String) {
            Nearby.getConnectionsClient(context).acceptConnection(endpointId, getMeshPayloadCallback(context))
        }

        fun rechazarConexionPendiente(context: Context, endpointId: String) {
            Nearby.getConnectionsClient(context).rejectConnection(endpointId)
        }

        fun enviarMensajeMesh(context: Context, senderId: String, senderName: String, target: String, msgId: Long, message: String) {
            val payload = Payload.fromBytes(message.toByteArray(Charsets.UTF_8))
            val client = Nearby.getConnectionsClient(context)
            if (connectedEndpoints.contains(target)) {
                client.sendPayload(target, payload)
            } else {
                connectedEndpoints.forEach { ep ->
                    client.sendPayload(ep, payload)
                }
            }
        }

        fun enviarMensajeGrupoMesh(context: Context, senderId: String, senderName: String, groupId: String, eventId: Long, message: String) {
            val payload = Payload.fromBytes(message.toByteArray(Charsets.UTF_8))
            val client = Nearby.getConnectionsClient(context)
            connectedEndpoints.forEach { ep ->
                client.sendPayload(ep, payload)
            }
        }
    }
}