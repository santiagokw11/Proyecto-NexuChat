package com.offline.nexu.ui.home

import android.content.Context
import android.net.Uri
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate

// Me armé este object loco de Kotlin para tener un Singleton. 
// Así tengo los datos de la conexión globales y no los pierdo si cierro o giro la pantalla.
object P2PManager {
    // Me guardo el ID del chabón con el que estoy conectado.
    var currentEndpointId: String? = null
    // Y también su nombre, para no andar mostrando códigos raros en la pantalla.
    var currentEndpointName: String? = null 

    // Estos son mis callbacks falopa para actualizar la UI desde acá adentro sin romper nada.
    var onDistanceEstimatedListener: ((String) -> Unit)? = null
    var onMessageReceivedListener: ((String, String, Uri?) -> Unit)? = null
    var onMessageSentListener: ((Long) -> Unit)? = null // Para pintar 1 chulito (enviado)
    var onAckReceivedListener: ((Long) -> Unit)? = null // Para pintar 2 chulitos (leído)
    var context: Context? = null

    // Diccionarios locos para no perder el hilo de los archivos mientras se descargan por partes.
    private val incomingPayloads = mutableMapOf<Long, Payload>()
    private val incomingTexts = mutableMapOf<Long, String>()
    private val incomingMsgIds = mutableMapOf<Long, Long>()
    val outgoingPayloadsTracker = mutableMapOf<Long, Long>()

    // Armé un historial de latencia porque el ping salta para cualquier lado.
    private val latencyHistory = mutableListOf<Long>()
    private val MAX_HISTORY = 5

    // Acá me llegan los paquetes (Payloads) desde Nearby. Es tipo el cartero de la app.
    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    // Trato de decodificar todo como UTF-8 porque sino los emojis llegaban con rombitos de error.
                    val data = String(payload.asBytes()!!, Charsets.UTF_8)

                    when {
                        // Si me llega un PING, le reboto un PONG enseguida con su propio numerito.
                        data.startsWith("PING:") -> {
                            val timestamp = data.substringAfter("PING:")
                            val pongPayload = Payload.fromBytes("PONG:$timestamp".toByteArray(Charsets.UTF_8))
                            context?.let { Nearby.getConnectionsClient(it).sendPayload(endpointId, pongPayload) }
                        }
                        // Si me vuelve un PONG mío, mido cuánto tardó el viaje ida y vuelta.
                        data.startsWith("PONG:") -> {
                            val tiempoEnviado = data.substringAfter("PONG:").toLongOrNull() ?: return
                            val latenciaActual = System.currentTimeMillis() - tiempoEnviado
                            
                            // Si es menos de 2 seg, lo meto al historial para sacar promedio después.
                            if (latenciaActual < 2000) {
                                latencyHistory.add(latenciaActual)
                                if (latencyHistory.size > MAX_HISTORY) latencyHistory.removeAt(0)
                            }
                            // Tiro un cálculo medio rústico de distancia basado en el promedio de latencia. Funciona maso.
                            if (latencyHistory.isNotEmpty()) {
                                val promedio = latencyHistory.average().toLong()
                                val estimacion = when {
                                    promedio < 60 -> "📍 < 1m (Muy Cerca)"
                                    promedio in 60..120 -> "📍 ~3m (Cerca)"
                                    promedio in 121..250 -> "📍 ~8m (Media)"
                                    else -> "📍 > 15m (Lejos)"
                                }
                                onDistanceEstimatedListener?.invoke(estimacion)
                            }
                        }
                        // Si me llega un ACK, le pinto el doble check azul/gris en su chat.
                        data.startsWith("ACK:") -> {
                            val msgId = data.removePrefix("ACK:").toLongOrNull() ?: return
                            onAckReceivedListener?.invoke(msgId)
                        }
                        // Si me llega un texto normal...
                        data.startsWith("TXT:") -> {
                            val parts = data.removePrefix("TXT:").split(":", limit = 2)
                            val msgId = parts[0].toLongOrNull() ?: return
                            val text = if (parts.size > 1) parts[1] else ""
                            
                            // Primero aviso que ya me llegó (ACK).
                            enviarAck(endpointId, msgId)
                            // Y después le digo a la pantalla que dibuje el globito de mensaje.
                            onMessageReceivedListener?.invoke(endpointId, text, null)
                        }
                        // Si me avisan que viene una foto...
                        data.startsWith("IMG:") -> {
                            val parts = data.removePrefix("IMG:").split(":", limit = 3)
                            val filePayloadId = parts[0].toLongOrNull() ?: return
                            val msgId = parts[1].toLongOrNull() ?: return
                            val textoAdjunto = if (parts.size > 2) parts[2] else ""
                            
                            // Guardo el texto y el ID del mensaje en la 'sala de espera' hasta que termine de descargar el archivo pesado.
                            incomingTexts[filePayloadId] = textoAdjunto
                            incomingMsgIds[filePayloadId] = msgId
                        }
                    }
                }
                Payload.Type.FILE -> {
                    // Acá entran los archivos pesados enteros. Los dejo guardados un toque.
                    incomingPayloads[payload.id] = payload
                }
            }
        }

        // Esto salta todo el tiempo mientras se transfiere un archivo (tipo barrita de carga).
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Si terminó y todo joya (SUCCESS)...
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val payloadId = update.payloadId
                
                // Si yo fui el que mandó la foto, busco a qué mensaje corresponde y le pinto un chulito.
                if (outgoingPayloadsTracker.containsKey(payloadId)) {
                    val msgId = outgoingPayloadsTracker.remove(payloadId)!!
                    onMessageSentListener?.invoke(msgId)
                }
                
                // Si yo soy el que la recibió...
                if (incomingPayloads.containsKey(payloadId)) {
                    val payloadFile = incomingPayloads[payloadId]
                    val fileUri = payloadFile?.asFile()?.asUri()
                    
                    // Saco el texto y el ID de la sala de espera.
                    val textoAdjunto = incomingTexts[payloadId] ?: ""
                    val msgId = incomingMsgIds[payloadId]
                    
                    if (fileUri != null) {
                        // Le aviso al chat que ya tengo todo para que lo muestre.
                        onMessageReceivedListener?.invoke(endpointId, textoAdjunto, fileUri)
                        // Le reboto el ACK al otro pibe para que sepa que la vi.
                        if (msgId != null) enviarAck(endpointId, msgId)
                    }
                    
                    // Limpio la basura de la memoria.
                    incomingPayloads.remove(payloadId)
                    incomingTexts.remove(payloadId)
                    incomingMsgIds.remove(payloadId)
                }
            }
        }
    }

    // Funcincita boluda para tirar un ACK (acuse de recibo) de vuelta.
    private fun enviarAck(endpointId: String, msgId: Long) {
        val ackPayload = Payload.fromBytes("ACK:$msgId".toByteArray(Charsets.UTF_8))
        context?.let { Nearby.getConnectionsClient(it).sendPayload(endpointId, ackPayload) }
    }

    // Limpio toda esta cochinada cuando me desconecto, porque sino se acumula basura y la próxima vez que entro explota la app.
    fun clearSession() {
        currentEndpointId = null
        currentEndpointName = null
        latencyHistory.clear()
        incomingPayloads.clear()
        incomingTexts.clear()
        incomingMsgIds.clear()
        outgoingPayloadsTracker.clear()
    }
}