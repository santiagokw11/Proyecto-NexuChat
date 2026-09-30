package com.offline.nexu.ui.home

import android.content.Context
import android.net.Uri
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.offline.nexu.data.model.UserProfile

object P2PManager {
    var currentEndpointId: String? = null
    var currentEndpointName: String? = null
    var onDistanceEstimatedListener: ((String) -> Unit)? = null
    var onMessageReceivedListener: ((String, String, Uri?) -> Unit)? = null
    var onMessageSentListener: ((Long) -> Unit)? = null
    var onAckReceivedListener: ((Long) -> Unit)? = null
    var onProfileReceivedListener: ((String, UserProfile) -> Unit)? = null // NUEVO: Callback de perfil
    var context: Context? = null

    val knownProfiles = mutableMapOf<String, UserProfile>() // Almacena los perfiles de la red local

    private val incomingPayloads = mutableMapOf<Long, Payload>()
    private val incomingTexts = mutableMapOf<Long, String>()
    private val incomingMsgIds = mutableMapOf<Long, Long>()
    val outgoingPayloadsTracker = mutableMapOf<Long, Long>()

    private val latencyHistory = mutableListOf<Long>()
    private val MAX_HISTORY = 5

    fun sendProfileHandshake(endpointId: String, myProfile: UserProfile) {
        // Para que la otra persona vea mi color, foto y bio, convierto mi perfil a JSON (texto plano)
        // y se lo mando apenas nos conectamos.
        val jsonStr = myProfile.toJson()
        val payload = Payload.fromBytes("PROFILE:$jsonStr".toByteArray(Charsets.UTF_8))
        context?.let { Nearby.getConnectionsClient(it).sendPayload(endpointId, payload) }
    }

    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    // Si recibo bytes, significa que es texto (un mensaje, un comando oculto, etc).
                    val data = String(payload.asBytes()!!, Charsets.UTF_8)

                    when {
                        data.startsWith("PROFILE:") -> {
                            // Me acaban de mandar su perfil (su foto, color, nombre). Lo guardo en mi lista de conocidos.
                            val jsonString = data.substringAfter("PROFILE:")
                            val remoteProfile = UserProfile.fromJson(jsonString)
                            knownProfiles[endpointId] = remoteProfile
                            currentEndpointName = remoteProfile.name
                            onProfileReceivedListener?.invoke(endpointId, remoteProfile)
                        }
                        data.startsWith("PING:") -> {
                            // Alguien está midiendo la distancia conmigo. Le devuelvo un PONG con la misma hora que me mandó.
                            val timestamp = data.substringAfter("PING:")
                            val pongPayload = Payload.fromBytes("PONG:$timestamp".toByteArray(Charsets.UTF_8))
                            context?.let { Nearby.getConnectionsClient(it).sendPayload(endpointId, pongPayload) }
                        }
                        data.startsWith("PONG:") -> {
                            // ¡Me respondieron el ping! Calculo cuánto tardó en ir y volver (latencia).
                            val tiempoEnviado = data.substringAfter("PONG:").toLongOrNull() ?: return
                            val latenciaActual = System.currentTimeMillis() - tiempoEnviado
                            
                            // Guardo el tiempo si no es absurdamente alto.
                            if (latenciaActual < 2000) {
                                latencyHistory.add(latenciaActual)
                                if (latencyHistory.size > MAX_HISTORY) latencyHistory.removeAt(0)
                            }
                            
                            // Hago un promedio de los últimos pings para que la estimación sea más estable.
                            if (latencyHistory.isNotEmpty()) {
                                val promedio = latencyHistory.average().toLong()
                                val estimacion = when {
                                    promedio < 60 -> "🟢 < 1m (Muy Cerca)"
                                    promedio in 60..120 -> "🟡 ~3m (Cerca)"
                                    promedio in 121..250 -> "🟠 ~8m (Media)"
                                    else -> "🔴 > 15m (Lejos)"
                                }
                                // Le aviso a la pantalla de chat para que actualice la barrita de distancia.
                                onDistanceEstimatedListener?.invoke(estimacion)
                            }
                        }
                        data.startsWith("ACK:") -> {
                            // Me confirmaron que un mensaje mío fue entregado. Aparecerán las palomitas azules.
                            val msgId = data.removePrefix("ACK:").toLongOrNull() ?: return
                            onAckReceivedListener?.invoke(msgId)
                        }
                        data.startsWith("TXT:") -> {
                            // Recibí un mensaje de texto normal.
                            val parts = data.removePrefix("TXT:").split(":", limit = 2)
                            val msgId = parts[0].toLongOrNull() ?: return
                            val text = if (parts.size > 1) parts[1] else ""
                            
                            // Le devuelvo un ACK para que sepa que sí lo leí.
                            enviarAck(endpointId, msgId)
                            onMessageReceivedListener?.invoke(endpointId, text, null)
                        }
                        data.startsWith("IMG:") -> {
                            // Me avisaron que me van a mandar una imagen. 
                            // Guardo la información de la imagen (su ID y el texto adjunto si trae) para cuando termine de descargar.
                            val parts = data.removePrefix("IMG:").split(":", limit = 3)
                            val filePayloadId = parts[0].toLongOrNull() ?: return
                            val msgId = parts[1].toLongOrNull() ?: return
                            val textoAdjunto = if (parts.size > 2) parts[2] else ""
                            incomingTexts[filePayloadId] = textoAdjunto
                            incomingMsgIds[filePayloadId] = msgId
                        }
                    }
                }
                Payload.Type.FILE -> {
                    // Está entrando un archivo pesado. Lo guardo en "pendientes" hasta que me avisen que ya terminó.
                    incomingPayloads[payload.id] = payload
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Aquí me entero de cómo van las descargas/subidas de archivos.
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val payloadId = update.payloadId
                
                // Si yo fui el que mandó un archivo y terminó bien, le digo a la UI que ya se envió.
                if (outgoingPayloadsTracker.containsKey(payloadId)) {
                    val msgId = outgoingPayloadsTracker.remove(payloadId)!!
                    onMessageSentListener?.invoke(msgId)
                }
                
                // Si yo fui el que lo recibió y ya terminó, lo muestro en el chat.
                if (incomingPayloads.containsKey(payloadId)) {
                    val payloadFile = incomingPayloads[payloadId]
                    val fileUri = payloadFile?.asFile()?.asUri()
                    val textoAdjunto = incomingTexts[payloadId] ?: ""
                    val msgId = incomingMsgIds[payloadId]
                    
                    if (fileUri != null) {
                        onMessageReceivedListener?.invoke(endpointId, textoAdjunto, fileUri)
                        if (msgId != null) enviarAck(endpointId, msgId) // Le digo al otro que la foto sí me llegó.
                    }
                    
                    // Limpio mi lista de descargas pendientes.
                    incomingPayloads.remove(payloadId)
                    incomingTexts.remove(payloadId)
                    incomingMsgIds.remove(payloadId)
                }
            }
        }
    }

    private fun enviarAck(endpointId: String, msgId: Long) {
        // Función rápida para confirmar recibidos (palomitas azules).
        val ackPayload = Payload.fromBytes("ACK:$msgId".toByteArray(Charsets.UTF_8))
        context?.let { Nearby.getConnectionsClient(it).sendPayload(endpointId, ackPayload) }
    }

    fun clearSession() {
        // Cuando cierro el chat, borro mi caché y limpios historiales.
        currentEndpointId = null
        currentEndpointName = null
        latencyHistory.clear()
        incomingPayloads.clear()
        incomingTexts.clear()
        incomingMsgIds.clear()
        outgoingPayloadsTracker.clear()
    }
}