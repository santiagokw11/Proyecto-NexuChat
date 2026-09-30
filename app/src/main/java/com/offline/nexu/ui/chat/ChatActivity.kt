package com.offline.nexu.ui.chat

import android.content.Intent
import android.content.res.ColorStateList
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
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.data.model.Message
import com.offline.nexu.databinding.ActivityChatBinding
import com.offline.nexu.ui.home.P2PManager
import com.offline.nexu.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// El activity principal del chat. Acá pasa toda la magia (y la mayoría de los bugs 🐛)
class ChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter
    private var selectedImageUri: Uri? = null

    private var isDebugMode = false
    private lateinit var database: AppDatabase
    private var chatIdentifier: String = ""
    private var auraColor: Int = 0

    // Handler para mandar pings y que no se duerma la conexión.
    // Ojalá esto no mate la batería del celular de la raza 😅
    private val pingHandler = Handler(Looper.getMainLooper())
    private val pingRunnable = object : Runnable {
        override fun run() {
            val endpointId = P2PManager.currentEndpointId
            if (endpointId != null) {
                val time = System.currentTimeMillis()
                val payload = Payload.fromBytes("PING:$time".toByteArray(Charsets.UTF_8))
                Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, payload)
            }
            val nextPingDelay = 3000L + kotlin.random.Random.nextLong(0, 2000)
            pingHandler.postDelayed(this, nextPingDelay)
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val localUri = copiarImagenLocal(it)
            if (localUri != null) {
                selectedImageUri = localUri
                binding.ivPreview.setImageURI(localUri)
                binding.cvImagePreview.visibility = View.VISIBLE
            }
        }
    }

    // Función pa' copiar la imagen localmente.
    // Android siempre la hace cansada con los URIs y los permisos, así que mejor la guardamos en nuestra carpeta y ya.
    private fun copiarImagenLocal(uri: Uri): Uri? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val baseMediaDir = externalMediaDirs.firstOrNull() ?: getExternalFilesDir(null)
            val mediaDir = File(baseMediaDir, "NexuChat/Media/NexuChat Images")
            if (!mediaDir.exists()) mediaDir.mkdirs()
            val noMediaFile = File(mediaDir, ".nomedia")
            if (!noMediaFile.exists()) noMediaFile.createNewFile()

            val file = File(mediaDir, "IMG_${System.currentTimeMillis()}.jpg")
            val outputStream = FileOutputStream(file)
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

        isDebugMode = intent.getBooleanExtra("EXTRA_IS_DEBUG", false)
        chatIdentifier = intent.getStringExtra("EXTRA_USER_NAME") ?: "Astronauta"
        database = AppDatabase.getDatabase(this)

        // APLICAR COLOR DE AURA - Pa' que se vea aesthetic ✨
        auraColor = ThemeUtils.getAuraColor(this)
        binding.btnBack.setColorFilter(auraColor)
        binding.btnSend.backgroundTintList = ColorStateList.valueOf(auraColor)

        setupRecyclerView()
        binding.tvChatTitle.text = chatIdentifier

        cargarAvatarContacto(Prefs(this).getContactAvatar(chatIdentifier))

        binding.llChatHeaderInfo.setOnClickListener {
            val intent = Intent(this, ContactInfoActivity::class.java)
            intent.putExtra("EXTRA_USER_NAME", chatIdentifier)
            startActivityForResult(intent, 100)
        }

        if (!isDebugMode) {
            binding.tvStatus.text = "Conectado P2P 🟢"
            binding.tvStatus.setTextColor(auraColor)

            P2PManager.context = applicationContext
            P2PManager.onDistanceEstimatedListener = { distancia ->
                runOnUiThread { binding.tvDistance.text = distancia }
            }

            P2PManager.onMessageReceivedListener = { _, texto, uriImagen ->
                runOnUiThread {
                    val msgId = System.currentTimeMillis()
                    adapter.addMessage(Message(msgId, texto, uriImagen, false, 0))
                    binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                    guardarMensajeEnBD(msgId, texto, uriImagen?.toString(), false, 0)
                }
            }

            P2PManager.onMessageSentListener = { msgId ->
                runOnUiThread { adapter.updateMessageStatus(msgId, 1) }
                actualizarEstadoEnBD(msgId, 1)
            }

            P2PManager.onAckReceivedListener = { msgId ->
                runOnUiThread { adapter.updateMessageStatus(msgId, 2) }
                actualizarEstadoEnBD(msgId, 2)
            }

            P2PManager.onProfileReceivedListener = { _, nombre, avatar ->
                if (nombre == chatIdentifier) {
                    runOnUiThread { cargarAvatarContacto(avatar) }
                }
            }

            pingHandler.post(pingRunnable)
        } else {
            binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRemoveImage.setOnClickListener {
            selectedImageUri = null
            binding.cvImagePreview.visibility = View.GONE
        }
        binding.btnAttach.setOnClickListener { showAttachmentMenu() }

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            val image = selectedImageUri
            if (text.isEmpty() && image == null) return@setOnClickListener

            val msgId = System.currentTimeMillis()
            adapter.addMessage(Message(msgId, text, image, true, 0))
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            guardarMensajeEnBD(msgId, text, image?.toString(), true, 0)

            binding.etMessage.text.clear()
            binding.cvImagePreview.visibility = View.GONE
            selectedImageUri = null

            if (isDebugMode) enviarMensajeSimulado(msgId, text, image)
            else enviarMensajeP2PReal(msgId, text, image)
        }
    }

    private fun cargarAvatarContacto(avatar: String) {
        if (avatar.startsWith("file://") || avatar.startsWith("content://") || avatar.startsWith("/")) {
            binding.tvHeaderAvatar.visibility = View.GONE
            binding.ivHeaderAvatar.visibility = View.VISIBLE
            try {
                binding.ivHeaderAvatar.setImageURI(Uri.parse(avatar))
            } catch (e: Exception) {
                binding.tvHeaderAvatar.visibility = View.VISIBLE
                binding.ivHeaderAvatar.visibility = View.GONE
            }
        } else {
            binding.ivHeaderAvatar.visibility = View.GONE
            binding.tvHeaderAvatar.visibility = View.VISIBLE
            binding.tvHeaderAvatar.text = avatar
        }
    }

    override fun onResume() {
        super.onResume()
        cargarHistorialDeMensajes()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) {
            val action = data?.getStringExtra("ACTION")
            if (action == "DELETE") finish()
        }
    }

    private fun cargarHistorialDeMensajes() {
        // Traemos los mensajes de la BD en un hilo secundario para no trabar la UI.
        lifecycleScope.launch(Dispatchers.IO) {
            val historial = database.nexuDao().getMessagesForUserSync(chatIdentifier)
            withContext(Dispatchers.Main) {
                setupRecyclerView()
                historial.forEach { entidad ->
                    val uri = if (entidad.imagePath.isNullOrEmpty()) null else Uri.parse(entidad.imagePath)
                    adapter.addMessage(Message(entidad.msgId, entidad.text, uri, entidad.isMine, entidad.status))
                }
                if (adapter.itemCount > 0) {
                    binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                }
            }
        }
    }

    private fun guardarMensajeEnBD(msgId: Long, text: String, imagePath: String?, isMine: Boolean, status: Int) {
        val entity = MessageEntity(
            msgId = msgId, endpointId = chatIdentifier, text = text,
            imagePath = imagePath, isMine = isMine, status = status, timestamp = System.currentTimeMillis()
        )
        lifecycleScope.launch(Dispatchers.IO) { database.nexuDao().insertMessage(entity) }
    }

    private fun actualizarEstadoEnBD(msgId: Long, status: Int) {
        lifecycleScope.launch(Dispatchers.IO) { database.nexuDao().updateStatus(msgId, status) }
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter(auraColor) { uri ->
            val intent = Intent(this, ImageViewerActivity::class.java)
            intent.putExtra("IMAGE_URI", uri.toString())
            startActivity(intent)
        }
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = adapter
    }

    private fun enviarMensajeSimulado(msgId: Long, text: String, image: Uri?) {
        Handler(Looper.getMainLooper()).postDelayed({
            adapter.updateMessageStatus(msgId, 1)
            actualizarEstadoEnBD(msgId, 1)
        }, 500)
        Handler(Looper.getMainLooper()).postDelayed({
            adapter.updateMessageStatus(msgId, 2)
            actualizarEstadoEnBD(msgId, 2)
        }, 1500)
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
            // Aquí mandamos el mensaje de verdad, cruzando los dedos para que Nearby Connections se porte bien.
            if (imageUri != null) {
                val pfd = contentResolver.openFileDescriptor(imageUri, "r")
                if (pfd != null) {
                    val filePayload = Payload.fromFile(pfd)
                    val header = "IMG:${filePayload.id}:$msgId:$text"
                    val textPayload = Payload.fromBytes(header.toByteArray(Charsets.UTF_8))
                    P2PManager.outgoingPayloadsTracker[filePayload.id] = msgId
                    Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, textPayload)
                    Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, filePayload)
                }
            } else if (text.isNotEmpty()) {
                val payload = Payload.fromBytes("TXT:$msgId:$text".toByteArray(Charsets.UTF_8))
                P2PManager.outgoingPayloadsTracker[payload.id] = msgId
                Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, payload)
            }
        } catch (e: Exception) {
            Log.e("NexuChat", "Error enviando: ", e)
        }
    }

    private fun showAttachmentMenu() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_attachment_menu, null)
        dialog.setContentView(view)
        view.findViewById<View>(R.id.btnGallery).setOnClickListener {
            pickImageLauncher.launch("image/*")
            dialog.dismiss()
        }
        dialog.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        pingHandler.removeCallbacks(pingRunnable)
    }
}