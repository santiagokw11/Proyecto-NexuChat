package com.offline.nexu.ui.chat

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.model.Message
import com.offline.nexu.databinding.ActivityChatBinding

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter

    private var opponentEndpointId: String? = null
    private var opponentName: String = "Usuario" 
    private lateinit var miPerfilP2P: String

    // Aquí guardo los archivos pesados (fotos) mientras se están descargando de la otra persona.
    private val incomingFilePayloads = mutableMapOf<Long, Payload>()
    // Aquí guardo la foto que yo escogí de mi galería antes de enviarla.
    private var selectedImageUri: Uri? = null

    // Este es el "abridor de galería". Cuando escojo una foto, la pego en el cuadro de vista previa de la pantalla.
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            binding.ivPreview.setImageURI(uri)
            binding.layoutPreview.visibility = View.VISIBLE
        }
    }

    // Igual que el de fotos, pero para documentos (aún en pañales).
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) Toast.makeText(this, "Documentos pronto", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inflando la pantalla del chat
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configuro mi RecyclerView (lista de mensajes) con mi adaptador especial de chat.
        adapter = MessageAdapter()
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = adapter

        // Recupero mi avatar y nombre de las preferencias de la app.
        val prefs = Prefs(this)
        miPerfilP2P = "${prefs.getAvatar()}|${prefs.getUsername()}"

        // Reviso si yo fui quien inició la charla (TARGET) o si fue el otro (INCOMING).
        val targetEndpointId = intent.getStringExtra("TARGET_ENDPOINT")
        val targetName = intent.getStringExtra("TARGET_NAME")
        val incomingEndpointId = intent.getStringExtra("INCOMING_ENDPOINT")
        val incomingName = intent.getStringExtra("INCOMING_NAME")

        if (targetEndpointId != null) {
            // Si yo la inicié, le pido amablemente a Google Nearby que nos conecte.
            opponentEndpointId = targetEndpointId
            opponentName = targetName ?: "Usuario"
            binding.toolbarChat.title = "Esperando a $opponentName..."
            Nearby.getConnectionsClient(applicationContext).requestConnection(
                miPerfilP2P, targetEndpointId, connectionLifecycleCallback
            )
        } else if (incomingEndpointId != null) {
            // Si la inició el otro (y yo acepté en la pantalla anterior), confirmo la conexión aquí.
            opponentEndpointId = incomingEndpointId
            opponentName = incomingName ?: "Usuario"
            binding.toolbarChat.title = "Conectado con $opponentName 🟢"
            Nearby.getConnectionsClient(applicationContext).acceptConnection(incomingEndpointId, payloadCallback)
        }

        // Botón "X" para arrepentirme de mandar la foto.
        binding.btnRemovePreview.setOnClickListener {
            selectedImageUri = null
            binding.layoutPreview.visibility = View.GONE
        }

        // Botón del clip para abrir el menú de adjuntos tipo WhatsApp.
        binding.btnAttach.setOnClickListener { showAttachmentMenu() }

        // BOTÓN DE ENVIAR: LA MAGIA DEL EMPAQUETADO
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            val imageUri = selectedImageUri

            // Si no escribí nada y no puse foto, no hago nada.
            if (text.isEmpty() && imageUri == null) return@setOnClickListener
            // Si el otro se desconectó, tampoco.
            if (opponentEndpointId == null) return@setOnClickListener

            var filePayloadId: Long = -1L

            // 1. Si escogí una foto, la convierto en un "Paquete pesado" (File Payload) y lo lanzo por el aire.
            if (imageUri != null) {
                try {
                    val pfd = contentResolver.openFileDescriptor(imageUri, "r")
                    if (pfd != null) {
                        val filePayload = Payload.fromFile(pfd)
                        filePayloadId = filePayload.id // Guardo su número de guía.
                        Nearby.getConnectionsClient(applicationContext).sendPayload(opponentEndpointId!!, filePayload)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Error con la imagen", Toast.LENGTH_SHORT).show()
                }
            }

            // 2. Aquí armo un combo: envío el texto NORMAL pegado al número de guía de la foto (si hay).
            // Esto sirve para que cuando el otro reciba ambos, sepa que la foto y el texto iban juntos en la misma burbuja.
            val textToSend = "$filePayloadId|$text"
            val textPayload = Payload.fromBytes(textToSend.toByteArray(Charsets.UTF_8))
            Nearby.getConnectionsClient(applicationContext).sendPayload(opponentEndpointId!!, textPayload)

            // 3. Pinto el mensaje completo en mi propia pantalla para que parezca instantáneo.
            adapter.addMessage(Message(text = text, imageUri = imageUri, isMine = true, payloadId = null))
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)

            // Limpio la barra de escribir.
            binding.etMessage.text.clear()
            selectedImageUri = null
            binding.layoutPreview.visibility = View.GONE
        }
    }

    private fun showAttachmentMenu() {
        // Levanto el menú inferior con los botoncitos redondos de colores.
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_attachment_menu, null)
        bottomSheetDialog.setContentView(view)

        // Si toco la galería, cierro el menú y abro el buscador de fotos.
        view.findViewById<View>(R.id.btnGallery).setOnClickListener {
            bottomSheetDialog.dismiss()
            pickImageLauncher.launch("image/*")
        }
        view.findViewById<View>(R.id.btnFiles).setOnClickListener {
            bottomSheetDialog.dismiss()
            pickFileLauncher.launch("*/*")
        }
        // Botones de relleno (adornos por ahora).
        view.findViewById<View>(R.id.btnCamera).setOnClickListener { bottomSheetDialog.dismiss() }
        view.findViewById<View>(R.id.btnLocation).setOnClickListener { bottomSheetDialog.dismiss() }
        view.findViewById<View>(R.id.btnContact).setOnClickListener { bottomSheetDialog.dismiss() }

        bottomSheetDialog.show()
    }

    // Reglas de qué hacer cuando cambia el estado de la conexión.
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Si el otro acepta, yo también acepto por debajo de la mesa para armar el canal de datos.
            Nearby.getConnectionsClient(applicationContext).acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            // Si todo salió bien, pongo el título en verde. Si no, en rojo.
            if (result.status.isSuccess) binding.toolbarChat.title = "Conectado con $opponentName 🟢"
            else binding.toolbarChat.title = "Rechazado 🔴"
        }
        override fun onDisconnected(endpointId: String) {
            // Uy, se fue el internet (o se alejó).
            opponentEndpointId = null
            binding.toolbarChat.title = "Desconectado 🔴"
        }
    }

    // El recibidor de cosas de la otra persona.
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                // RECIBÍ TEXTO:
                val content = String(payload.asBytes()!!, Charsets.UTF_8)
                
                // Desempaco el "combo" que me armaron arriba: el número de guía de la foto y el texto en sí.
                val parts = content.split("|", limit = 2)
                val fId = parts[0].toLongOrNull() ?: -1L
                val receivedText = if (parts.size > 1) parts[1] else ""

                // Pinto el texto en la pantalla inmediatamente. 
                // Si traía un número de guía, se lo pego a la burbuja para cuando llegue la foto.
                adapter.addMessage(Message(
                    text = receivedText,
                    imageUri = null,
                    isMine = false,
                    payloadId = if (fId != -1L) fId else null
                ))
                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)

            } else if (payload.type == Payload.Type.FILE) {
                // RECIBÍ UNA FOTO (INICIO DE DESCARGA): 
                // La guardo en mi lista de "paquetes pesados" esperando a que termine.
                incomingFilePayloads[payload.id] = payload
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Este evento me avisa del progreso de las cosas pesadas.
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                // ¡Descarga completada! Saco el archivo de mi lista de pendientes.
                val payload = incomingFilePayloads.remove(update.payloadId)
                if (payload != null && payload.type == Payload.Type.FILE) {
                    // Consigo la ruta en mi teléfono de la foto descargada.
                    val fileUri = payload.asFile()?.asUri() ?: Uri.fromFile(payload.asFile()?.asJavaFile())
                    
                    // Busco en el chat la burbuja que tenía este número de guía y le pego la foto para que por fin se vea.
                    adapter.updateMessageImage(update.payloadId, fileUri)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Si cierro el chat, corto la llamada.
        Nearby.getConnectionsClient(applicationContext).stopAllEndpoints()
    }
}