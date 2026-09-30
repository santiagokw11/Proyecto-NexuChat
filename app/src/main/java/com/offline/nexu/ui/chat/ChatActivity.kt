package com.offline.nexu.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.Payload
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.offline.nexu.R
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.data.model.Message
import com.offline.nexu.databinding.ActivityChatBinding
import com.offline.nexu.ui.home.P2PManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.random.Random

class ChatActivity : AppCompatActivity() {
    // Uso ViewBinding acá también, como debe ser.
    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter
    
    // Acá me guardo la imagen que el pibe seleccionó para mandar, si es que eligió alguna.
    private var selectedImageUri: Uri? = null

    // Variable para saber si estoy en el modo falopa de debug o en el mundo real.
    private var isDebugMode = false
    private lateinit var database: AppDatabase

    // Esto va a guardar el nombre o ID del chabón con el que estoy chateando.
    private var chatIdentifier: String = ""

    // Magia negra para el PING-PONG y saber la distancia de la otra persona.
    // Meto un Handler que corra en bucle cada 3-5 segundos.
    private val pingHandler = Handler(Looper.getMainLooper())
    private val pingRunnable = object : Runnable {
        override fun run() {
            val endpointId = P2PManager.currentEndpointId
            if (endpointId != null) {
                // Si estoy conectado, le tiro un PING con mi hora actual.
                val time = System.currentTimeMillis()
                val payload = Payload.fromBytes("PING:$time".toByteArray(Charsets.UTF_8))
                Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, payload)
            }
            // Tiro un delay random entre 3 y 5 segs para no saturar el canal de Bluetooth.
            val nextPingDelay = 3000L + Random.nextLong(0, 2000)
            pingHandler.postDelayed(this, nextPingDelay)
        }
    }

    // Esto es el nuevo selector de galería de Android para agarrar fotos. 
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            // Cuando la elige, la copio a una carpeta privada mía así no me quedo sin permisos después.
            val localUri = copiarImagenLocal(it)
            if (localUri != null) {
                selectedImageUri = localUri
                // La muestro chiquita arriba de la caja de texto para que sepa qué va a mandar.
                binding.ivPreview.setImageURI(localUri)
                binding.cvImagePreview.visibility = View.VISIBLE
            }
        }
    }

    private fun copiarImagenLocal(uri: Uri): Uri? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null

            // Le armo una carpeta parecida a como hace WhatsApp (Android/media/...)
            val baseMediaDir = externalMediaDirs.firstOrNull() ?: getExternalFilesDir(null)
            val mediaDir = File(baseMediaDir, "NexuChat/Media/NexuChat Images")

            if (!mediaDir.exists()) {
                mediaDir.mkdirs() // Creo toda la ruta si no existe.
            }

            // Le clavo un .nomedia para que estas fotos falopa no le ensucien la galería principal del celu al usuario.
            val noMediaFile = File(mediaDir, ".nomedia")
            if (!noMediaFile.exists()) {
                noMediaFile.createNewFile()
            }

            // Armo el archivo con la hora para que el nombre sea único.
            val file = File(mediaDir, "IMG_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(file)
            
            // Copio los bytes a lo bestia.
            inputStream.copyTo(outputStream)

            inputStream.close()
            outputStream.close()

            Uri.fromFile(file)
        } catch (e: Exception) {
            Log.e("NexuChat", "Error guardando imagen", e)
            null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Traigo la data del Intent. Si falla, el loco es un Astronauta misterioso.
        isDebugMode = intent.getBooleanExtra("EXTRA_IS_DEBUG", false)
        chatIdentifier = intent.getStringExtra("EXTRA_USER_NAME") ?: "Astronauta"
        database = AppDatabase.getDatabase(this)

        // Preparo la lista para que se vean los globitos de texto.
        setupRecyclerView()
        binding.tvChatTitle.text = chatIdentifier

        // Levanto los mensajes viejos de la base de datos para no arrancar el chat vacío.
        cargarHistorialDeMensajes()

        if (!isDebugMode) {
            // Si estamos en P2P posta, prendo todo el motor de Nearby.
            binding.tvStatus.text = "Conectado P2P 🟢"
            binding.tvStatus.setTextColor(getColor(R.color.secondary_color))

            P2PManager.context = applicationContext

            // Escucho los cálculos falopa de distancia y los pongo en la barrita de arriba.
            P2PManager.onDistanceEstimatedListener = { distancia ->
                runOnUiThread { binding.tvDistance.text = distancia }
            }

            // Si me llega un mensaje (texto o foto), lo pinto en pantalla y lo clavo en la base de datos de una.
            P2PManager.onMessageReceivedListener = { _, texto, uriImagen ->
                runOnUiThread {
                    val msgId = System.currentTimeMillis()
                    adapter.addMessage(Message(msgId, texto, uriImagen, false, 0))
                    binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                    guardarMensajeEnBD(msgId, texto, uriImagen?.toString(), false, 0)
                }
            }

            // Si se envió mi mensaje con éxito, le pongo un (1) chulito en la UI y actualizo la DB.
            P2PManager.onMessageSentListener = { msgId ->
                runOnUiThread { adapter.updateMessageStatus(msgId, 1) }
                actualizarEstadoEnBD(msgId, 1)
            }

            // Si el otro me confirma que le llegó el mensaje (ACK), le pongo los dos (2) chulitos.
            P2PManager.onAckReceivedListener = { msgId ->
                runOnUiThread { adapter.updateMessageStatus(msgId, 2) }
                actualizarEstadoEnBD(msgId, 2)
            }

            // Arranco la máquina de hacer pings.
            pingHandler.post(pingRunnable)
        }

        binding.btnBack.setOnClickListener { finish() }

        // El botoncito de la 'X' en la miniatura de la imagen. Por si se arrepiente de mandarla.
        binding.btnRemoveImage.setOnClickListener {
            selectedImageUri = null
            binding.cvImagePreview.visibility = View.GONE
        }

        // El clásico clip para adjuntar cosas. Abro un menú de abajo (BottomSheet).
        binding.btnAttach.setOnClickListener { showAttachmentMenu() }

        // Botón de Enviar. Acá se pica.
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            val image = selectedImageUri
            
            // Si apretó enviar y no hay texto ni foto, me hago el boludo y no hago nada.
            if (text.isEmpty() && image == null) return@setOnClickListener

            // Genero un ID único para el mensaje basado en la hora.
            val msgId = System.currentTimeMillis()

            // Lo agrego a MI pantalla primero para que parezca instantáneo (isMine = true).
            adapter.addMessage(Message(msgId, text, image, true, 0))
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1) // Scrolleo para abajo del todo.

            // Lo guardo en mi base de datos por las dudas.
            guardarMensajeEnBD(msgId, text, image?.toString(), true, 0)

            // Limpio la cajita de texto y la previsualización.
            binding.etMessage.text.clear()
            binding.cvImagePreview.visibility = View.GONE
            selectedImageUri = null

            // Dependiendo del modo, mando el paquete posta o simulo que lo mando.
            if (isDebugMode) {
                enviarMensajeSimulado(msgId, text, image)
            } else {
                enviarMensajeP2PReal(msgId, text, image)
            }
        }
    }

    private fun cargarHistorialDeMensajes() {
        // Corrutina IO porque si leo la base de datos en el hilo principal la app crashea hermoso.
        lifecycleScope.launch(Dispatchers.IO) {
            val historial = database.nexuDao().getMessagesForUserSync(chatIdentifier)
            
            // Vuelvo al main thread para poder meter cosas en el RecyclerView.
            withContext(Dispatchers.Main) {
                historial.forEach { entidad ->
                    val uri = if (entidad.imagePath.isNullOrEmpty()) null else Uri.parse(entidad.imagePath)
                    adapter.addMessage(Message(entidad.msgId, entidad.text, uri, entidad.isMine, entidad.status))
                }
                // Si cargué mensajes, bajo el scroll hasta el último para que no tenga que bajar a mano.
                if (adapter.itemCount > 0) {
                    binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                }
            }
        }
    }

    private fun guardarMensajeEnBD(msgId: Long, text: String, imagePath: String?, isMine: Boolean, status: Int) {
        val entity = MessageEntity(
            msgId = msgId,
            endpointId = chatIdentifier, // Para saber con quién fue esta charla.
            text = text,
            imagePath = imagePath,
            isMine = isMine,
            status = status,
            timestamp = System.currentTimeMillis()
        )
        // Corrutina IO de nuevo para insertar sin quemar la interfaz.
        lifecycleScope.launch(Dispatchers.IO) {
            database.nexuDao().insertMessage(entity)
        }
    }

    private fun actualizarEstadoEnBD(msgId: Long, status: Int) {
        // Funciocinta rápida para cambiarle el estado a un mensaje (enviado, leído) en la BD.
        lifecycleScope.launch(Dispatchers.IO) {
            database.nexuDao().updateStatus(msgId, status)
        }
    }

    private fun setupRecyclerView() {
        // Armo el adapter. Si tocan una imagen, abro el visor de imágenes gigante.
        adapter = MessageAdapter { uri ->
            val intent = Intent(this, ImageViewerActivity::class.java)
            intent.putExtra("IMAGE_URI", uri.toString())
            startActivity(intent)
        }
        // stackFromEnd es clave para que los mensajes empiecen desde abajo como en WhatsApp.
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = adapter
    }

    private fun enviarMensajeSimulado(msgId: Long, text: String, image: Uri?) {
        // Humo puro. Pongo delays falopa para que parezca que viaja por internet y le cambia los chulitos.
        Handler(Looper.getMainLooper()).postDelayed({
            adapter.updateMessageStatus(msgId, 1)
            actualizarEstadoEnBD(msgId, 1)
        }, 500)

        Handler(Looper.getMainLooper()).postDelayed({
            adapter.updateMessageStatus(msgId, 2)
            actualizarEstadoEnBD(msgId, 2)
        }, 1500)

        // Me auto-respondo como si fuera el otro pibe.
        Handler(Looper.getMainLooper()).postDelayed({
            val rcvMsgId = System.currentTimeMillis()
            val receivedText = if (image != null) "¡Llegó la imagen!" else "Eco: $text"
            adapter.addMessage(Message(rcvMsgId, receivedText, image, false, 0))
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            guardarMensajeEnBD(rcvMsgId, receivedText, image?.toString(), false, 0)
        }, 1500)
    }

    private fun enviarMensajeP2PReal(msgId: Long, text: String, imageUri: Uri?) {
        val endpointId = P2PManager.currentEndpointId ?: return

        try {
            if (imageUri != null) {
                // Si hay foto, es un bardo. Tengo que mandarle primero un mensajito diciendo "che, ahí te va una foto con este ID y este texto".
                val pfd = contentResolver.openFileDescriptor(imageUri, "r")
                if (pfd != null) {
                    val filePayload = Payload.fromFile(pfd) // Empaqueto el archivo.
                    val header = "IMG:${filePayload.id}:$msgId:$text" // Armo la cabecera.
                    val textPayload = Payload.fromBytes(header.toByteArray(Charsets.UTF_8))

                    // Guardo el rastro para saber cuándo terminó de enviarse.
                    P2PManager.outgoingPayloadsTracker[filePayload.id] = msgId

                    // Mando el aviso primero, y después el archivo pesado.
                    Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, textPayload)
                    Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, filePayload)
                }
            } else if (text.isNotEmpty()) {
                // Si es solo texto, es una papa. Lo envuelvo en bytes y lo mando.
                val payload = Payload.fromBytes("TXT:$msgId:$text".toByteArray(Charsets.UTF_8))
                P2PManager.outgoingPayloadsTracker[payload.id] = msgId
                Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, payload)
            }
        } catch (e: Exception) {
            Log.e("NexuChat", "Error enviando: ", e)
        }
    }

    private fun showAttachmentMenu() {
        // Muestro el menucito de abajo que por ahora solo tiene "Galería". Si agrego más cosas, van acá.
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_attachment_menu, null)
        dialog.setContentView(view)
        
        view.findViewById<View>(R.id.btnGallery).setOnClickListener {
            // Lanzo el intent de la galería y cierro el menucito.
            pickImageLauncher.launch("image/*")
            dialog.dismiss()
        }
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Me aseguro de apagar el bucle infinito del PING para que no me drene la batería en segundo plano.
        pingHandler.removeCallbacks(pingRunnable)
    }
}