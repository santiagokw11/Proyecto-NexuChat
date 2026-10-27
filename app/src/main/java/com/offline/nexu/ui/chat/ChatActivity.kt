package com.offline.nexu.ui.chat

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
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
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatBinding
    private lateinit var adapter: MessageAdapter
    private var selectedImageUri: Uri? = null

    private var isDebugMode = false
    private lateinit var database: AppDatabase
    private var chatIdentifier: String = ""
    private var auraColor: Int = 0
    private lateinit var myLocalUserId: String
    private lateinit var prefs: Prefs

    private var isTyping = false
    private val typingHandler = Handler(Looper.getMainLooper())

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

        prefs = Prefs(this)
        myLocalUserId = prefs.getUserProfile().id
        isDebugMode = intent.getBooleanExtra("EXTRA_IS_DEBUG", false)
        chatIdentifier = intent.getStringExtra("EXTRA_USER_NAME") ?: "Astronauta"
        database = AppDatabase.getDatabase(this)

        auraColor = ThemeUtils.getAuraColor(this)
        binding.btnBack.setColorFilter(auraColor)
        binding.btnSend.backgroundTintList = ColorStateList.valueOf(auraColor)

        setupRecyclerView()
        binding.tvChatTitle.text = chatIdentifier

        cargarAvatarContacto(prefs.getContactAvatar(chatIdentifier))

        binding.llChatHeaderInfo.setOnClickListener {
            val intent = Intent(this, ContactInfoActivity::class.java)
            intent.putExtra("EXTRA_USER_NAME", chatIdentifier)
            startActivityForResult(intent, 100)
        }

        actualizarEstadoConexion()

        if (!isDebugMode) {
            P2PManager.context = applicationContext

            P2PManager.onMessageReceivedListener = { _, texto, uriImagen ->
                runOnUiThread {
                    if (texto == "SYS:TYPING:1") {
                        binding.tvStatus.text = "Escribiendo... ✍️"
                        binding.tvStatus.setTextColor(auraColor)
                    } else if (texto == "SYS:TYPING:0") {
                        actualizarEstadoConexion()
                    } else {
                        actualizarEstadoConexion()
                        val msgId = System.currentTimeMillis()
                        adapter.addMessage(Message(msgId, texto, uriImagen, false, 0))
                        binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                        guardarMensajeEnBD(msgId, texto, uriImagen?.toString(), false, 0)
                    }
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
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRemoveImage.setOnClickListener {
            selectedImageUri = null
            binding.cvImagePreview.visibility = View.GONE
        }
        binding.btnAttach.setOnClickListener { showAttachmentMenu() }

        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (P2PManager.currentEndpointId == null) return

                if (s.isNullOrEmpty() && isTyping) {
                    isTyping = false
                    enviarPayloadSistema("SYS:TYPING:0")
                } else if (!s.isNullOrEmpty() && !isTyping) {
                    isTyping = true
                    enviarPayloadSistema("SYS:TYPING:1")
                }
            }
        })

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
            isTyping = false
            enviarPayloadSistema("SYS:TYPING:0")

            if (isDebugMode) enviarMensajeSimulado(msgId, text, image)
            else enviarMensajeP2PReal(msgId, text, image)
        }
    }

    private fun actualizarEstadoConexion() {
        val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)

        if (P2PManager.currentEndpointName == chatIdentifier && P2PManager.currentEndpointId != null) {
            binding.tvStatus.text = "En línea 🟢"
            binding.tvStatus.setTextColor(getColor(R.color.text_primary))
            sharedPreferences.edit().putLong("last_seen_$chatIdentifier", System.currentTimeMillis()).apply()
        } else {
            val lastSeen = sharedPreferences.getLong("last_seen_$chatIdentifier", 0)
            if (lastSeen == 0L) {
                binding.tvStatus.text = "Desconectado 🔴"
            } else {
                val formatter = SimpleDateFormat("dd/MM hh:mm a", Locale.getDefault())
                binding.tvStatus.text = "Últ. vez: ${formatter.format(Date(lastSeen))} 🔴"
            }
            binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
        }
    }

    private fun enviarPayloadSistema(comando: String) {
        val endpointId = P2PManager.currentEndpointId ?: return
        try {
            val payload = Payload.fromBytes("TXT:0:$comando".toByteArray(Charsets.UTF_8))
            Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, payload)
        } catch (e: Exception) {}
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
        actualizarEstadoConexion()
    }

    private fun cargarHistorialDeMensajes() {
        lifecycleScope.launch(Dispatchers.IO) {
            val historial = database.nexuDao().getMessagesForUserSync(chatIdentifier, myLocalUserId)
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
            msgId = msgId,
            localUserId = myLocalUserId,
            endpointId = chatIdentifier,
            text = text,
            imagePath = imagePath,
            isMine = isMine,
            status = status,
            timestamp = System.currentTimeMillis()
        )
        lifecycleScope.launch(Dispatchers.IO) { database.nexuDao().insertMessage(entity) }
    }

    private fun actualizarEstadoEnBD(msgId: Long, status: Int) {
        lifecycleScope.launch(Dispatchers.IO) { database.nexuDao().updateStatus(msgId, status) }
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter(
            auraColor = auraColor,
            onImageClick = { uri ->
                val intent = Intent(this, ImageViewerActivity::class.java)
                intent.putExtra("IMAGE_URI", uri.toString())
                startActivity(intent)
            },
            onMessageLongClick = { message ->
                confirmarEliminacionMensaje(message.msgId)
            }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = adapter
    }

    private fun confirmarEliminacionMensaje(msgId: Long) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_delete_message)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnDelete = dialog.findViewById<Button>(R.id.btnDeleteMsg)
        val btnCancel = dialog.findViewById<Button>(R.id.btnCancelDelete)

        btnDelete.setOnClickListener {
            adapter.removeMessage(msgId)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    database.nexuDao().deleteMessageById(msgId)
                } catch (e: Exception) {}
            }
            dialog.dismiss()
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun enviarMensajeSimulado(msgId: Long, text: String, image: Uri?) {
        // Simula "Enviado"
        Handler(Looper.getMainLooper()).postDelayed({ adapter.updateMessageStatus(msgId, 1); actualizarEstadoEnBD(msgId, 1) }, 500)

        // Simula "Entregado"
        Handler(Looper.getMainLooper()).postDelayed({ adapter.updateMessageStatus(msgId, 2); actualizarEstadoEnBD(msgId, 2) }, 1500)

        // Simula "Escribiendo..."
        Handler(Looper.getMainLooper()).postDelayed({
            binding.tvStatus.text = "Escribiendo... ✍️"
            binding.tvStatus.setTextColor(auraColor)
        }, 1800)

        // El Bot Responde (Eco Restaurado)
        Handler(Looper.getMainLooper()).postDelayed({
            actualizarEstadoConexion() // Quita el "Escribiendo"

            val rcvMsgId = System.currentTimeMillis()
            val receivedText = if (image != null) "¡Recibí tu archivo perfectamente! 📁" else "Eco Bot: $text"

            adapter.addMessage(Message(rcvMsgId, receivedText, image, false, 0))
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            guardarMensajeEnBD(rcvMsgId, receivedText, image?.toString(), false, 0)
        }, 3500)
    }

    private fun enviarMensajeP2PReal(msgId: Long, text: String, imageUri: Uri?) {
        val endpointId = P2PManager.currentEndpointId ?: return
        try {
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
        } catch (e: Exception) {}
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
}