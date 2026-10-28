package com.offline.nexu.ui.chat

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import com.offline.nexu.data.network.NexuMeshManager
import com.offline.nexu.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    private var isGroup: Boolean = false
    private var groupId: String = ""
    private var groupExpirationTime: Long? = null

    private var isTyping = false
    private val typingHandler = Handler(Looper.getMainLooper())
    private var lastSentNudgeTime = 0L

    private var ephemeralDuration: Long = 0L
    private val activeMessagesTracker = mutableListOf<Message>()

    private val requestGalleryPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) openCustomGalleryBottomSheet()
        else Toast.makeText(this, "Permiso denegado", Toast.LENGTH_SHORT).show()
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val file = File(externalCacheDir, "Cam_${System.currentTimeMillis()}.jpg")
                    val out = FileOutputStream(file)
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush()
                    out.close()
                    val uri = Uri.fromFile(file)
                    withContext(Dispatchers.Main) {
                        selectedImageUri = uri
                        binding.ivPreview.setImageURI(uri)
                        binding.cvImagePreview.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private val pickDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { procesarUriSeleccionada(it, esDocumento = true) }
    }

    private fun procesarUriSeleccionada(uri: Uri, esDocumento: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val localUri = copiarImagenLocal(uri, esDocumento)
            withContext(Dispatchers.Main) {
                if (localUri != null) {
                    selectedImageUri = localUri
                    if (esDocumento) {
                        binding.ivPreview.setImageResource(android.R.drawable.ic_menu_save)
                    } else {
                        val isVideo = localUri.toString().endsWith(".mp4") || localUri.toString().contains("VID_") || localUri.toString().contains("video")
                        if (isVideo) {
                            cargarMiniaturaVideoVistaPrevia(localUri, binding.ivPreview)
                        } else {
                            binding.ivPreview.setImageURI(localUri)
                        }
                    }
                    binding.cvImagePreview.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun cargarMiniaturaVideoVistaPrevia(uri: Uri, imageView: ImageView) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(uri.path!!)
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.media.ThumbnailUtils.createVideoThumbnail(file, android.util.Size(500, 500), null)
                } else {
                    @Suppress("DEPRECATION")
                    android.media.ThumbnailUtils.createVideoThumbnail(file.absolutePath, android.provider.MediaStore.Images.Thumbnails.MINI_KIND)
                }

                if (bitmap != null) {
                    val canvasBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    val canvas = Canvas(canvasBitmap)
                    val playDrawable = ContextCompat.getDrawable(this@ChatActivity, android.R.drawable.ic_media_play)
                    if (playDrawable != null) {
                        val iconSize = 120
                        val left = (canvas.width - iconSize) / 2
                        val top = (canvas.height - iconSize) / 2
                        playDrawable.setBounds(left, top, left + iconSize, top + iconSize)
                        playDrawable.setTint(android.graphics.Color.WHITE)
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#80000000")
                            style = android.graphics.Paint.Style.FILL
                            isAntiAlias = true
                        }
                        canvas.drawCircle(canvas.width / 2f, canvas.height / 2f, iconSize / 1.5f, paint)
                        playDrawable.draw(canvas)
                    }
                    withContext(Dispatchers.Main) {
                        imageView.setImageBitmap(canvasBitmap)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun copiarImagenLocal(uri: Uri, esDocumento: Boolean): Uri? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val baseMediaDir = externalMediaDirs.firstOrNull() ?: getExternalFilesDir(null)
            val isVideo = contentResolver.getType(uri)?.contains("video") == true || uri.toString().endsWith(".mp4")

            val subFolder = when {
                esDocumento -> "NexuChat Documents"
                isVideo -> "NexuChat Videos"
                else -> "NexuChat Images"
            }
            val ext = when {
                esDocumento -> ".pdf"
                isVideo -> ".mp4"
                else -> ".jpg"
            }
            val prefix = when {
                esDocumento -> "DOC_"
                isVideo -> "VID_"
                else -> "IMG_"
            }

            val mediaDir = File(baseMediaDir, "NexuChat/Media/$subFolder")
            if (!mediaDir.exists()) mediaDir.mkdirs()
            val noMediaFile = File(mediaDir, ".nomedia")
            if (!noMediaFile.exists()) noMediaFile.createNewFile()

            val file = File(mediaDir, "${prefix}${System.currentTimeMillis()}$ext")
            val outputStream = FileOutputStream(file)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            Uri.fromFile(file)
        } catch (e: Exception) { null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        myLocalUserId = prefs.getUserProfile().id
        isDebugMode = intent.getBooleanExtra("EXTRA_IS_DEBUG", false)
        database = AppDatabase.getDatabase(this)

        isGroup = intent.getBooleanExtra("EXTRA_IS_GROUP", false)
        if (isGroup) {
            groupId = intent.getStringExtra("EXTRA_GROUP_ID") ?: ""
            chatIdentifier = groupId
            binding.tvChatTitle.text = intent.getStringExtra("EXTRA_GROUP_NAME") ?: "Grupo"

            lifecycleScope.launch(Dispatchers.IO) {
                val group = database.nexuDao().getGroup(groupId)
                val avatarStr = group?.groupAvatar ?: "👥"
                groupExpirationTime = group?.expirationTime

                withContext(Dispatchers.Main) {
                    cargarAvatarContacto(avatarStr)
                    actualizarEstadoConexion()
                }
            }
        } else {
            chatIdentifier = intent.getStringExtra("EXTRA_USER_NAME") ?: "Astronauta"
            binding.tvChatTitle.text = chatIdentifier
            cargarAvatarContacto(prefs.getContactAvatar(chatIdentifier))
        }

        auraColor = ThemeUtils.getAuraColor(this)
        binding.btnBack.setColorFilter(auraColor)
        binding.btnSend.backgroundTintList = ColorStateList.valueOf(auraColor)
        binding.btnNudge.setColorFilter(auraColor)
        binding.btnAttach.setColorFilter(auraColor)

        setupRecyclerView()
        iniciarLimpiezaEfimeros()

        NexuMeshManager.distanceUpdateListener = { distancia ->
            runOnUiThread { binding.tvDistance.text = "📍 $distancia" }
        }

        binding.llChatHeaderInfo.setOnClickListener {
            if (!isGroup) {
                val intent = Intent(this, ContactInfoActivity::class.java).apply { putExtra("EXTRA_USER_NAME", chatIdentifier) }
                startActivityForResult(intent, 100)
            }
        }

        binding.ivAddTime.setOnClickListener {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 40, 60, 20)
            }
            val inputLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            val etValue = EditText(this).apply {
                hint = "Cantidad"
                inputType = InputType.TYPE_CLASS_NUMBER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val spinnerUnit = Spinner(this).apply {
                adapter = ArrayAdapter(this@ChatActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Segundos", "Minutos", "Horas", "Días"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            inputLayout.addView(etValue)
            inputLayout.addView(spinnerUnit)
            layout.addView(inputLayout)

            AlertDialog.Builder(this)
                .setTitle("Añadir tiempo al grupo")
                .setView(layout)
                .setPositiveButton("Agregar") { _, _ ->
                    val valStr = etValue.text.toString().trim()
                    val value = valStr.toLongOrNull() ?: 0L
                    if (value > 0) {
                        val multiplier = when (spinnerUnit.selectedItemPosition) {
                            0 -> 1000L
                            1 -> 60 * 1000L
                            2 -> 60 * 60 * 1000L
                            3 -> 24 * 60 * 60 * 1000L
                            else -> 1000L
                        }
                        val addedTime = value * multiplier
                        groupExpirationTime = groupExpirationTime!! + addedTime

                        lifecycleScope.launch(Dispatchers.IO) {
                            database.nexuDao().updateGroupExpiration(groupId, groupExpirationTime!!)
                            val cmd = "SYS:ADD_TIME:$groupId:${groupExpirationTime!!}"
                            NexuMeshManager.enviarMensajeGrupoMesh(applicationContext, myLocalUserId, groupId, System.currentTimeMillis(), "TXT:0:$cmd")

                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ChatActivity, "Tiempo extra añadido", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        Toast.makeText(this, "Ingresa un valor válido", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        actualizarEstadoConexion()

        if (!isDebugMode) {
            P2PManager.context = applicationContext

            P2PManager.onPayloadProgress = { msgId, progress ->
                runOnUiThread { adapter.updateProgress(msgId, progress) }
            }

            P2PManager.onMessageReceivedListener = { incomingMsgId, texto, uriImagen ->
                runOnUiThread {
                    when {
                        texto.startsWith("SYS:ADD_TIME:") -> {
                            val parts = texto.split(":", limit = 4)
                            if (parts.size >= 4) {
                                val gId = parts[2]
                                val newTime = parts[3].toLongOrNull() ?: return@runOnUiThread
                                lifecycleScope.launch(Dispatchers.IO) {
                                    database.nexuDao().updateGroupExpiration(gId, newTime)
                                    if (isGroup && groupId == gId) {
                                        groupExpirationTime = newTime
                                    }
                                }
                            }
                        }
                        texto.startsWith("EPH:") -> {
                            val parts = texto.split(":", limit = 4)
                            if (parts.size >= 4) {
                                val msgId = parts[1].toLongOrNull() ?: System.currentTimeMillis()
                                val duration = parts[2].toLongOrNull() ?: 0L
                                val content = parts[3]
                                val expiresAt = if (duration > 0) System.currentTimeMillis() + duration else null
                                val newMsg = Message(msgId, content, uriImagen, false, 0, progress = 100, originalSender = chatIdentifier, isGroup = false, expiresAt = expiresAt)
                                adapter.addMessage(newMsg)
                                activeMessagesTracker.add(newMsg)
                                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                                guardarMensajeEnBD(msgId, content, uriImagen?.toString(), false, 0, chatIdentifier, expiresAt)
                                mostrarNotificacionMensaje(chatIdentifier, content)
                            }
                        }
                        texto.startsWith("GRP:") -> {
                            val parts = texto.split(":", limit = 4)
                            if (parts.size >= 4 && parts[1] == chatIdentifier) {
                                val senderId = parts[2]
                                val content = parts[3]
                                val msgId = System.currentTimeMillis()
                                val newMsg = Message(msgId, content, uriImagen, false, 0, progress = 100, originalSender = senderId, isGroup = true)
                                adapter.addMessage(newMsg)
                                activeMessagesTracker.add(newMsg)
                                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                                guardarMensajeEnBD(msgId, content, uriImagen?.toString(), false, 0, senderId, null)
                                mostrarNotificacionMensaje(chatIdentifier, content)
                            }
                        }
                        texto.startsWith("GRP_EPH:") -> {
                            val parts = texto.split(":", limit = 5)
                            if (parts.size >= 5 && parts[1] == chatIdentifier) {
                                val senderId = parts[2]
                                val duration = parts[3].toLongOrNull() ?: 0L
                                val content = parts[4]
                                val expiresAt = if (duration > 0) System.currentTimeMillis() + duration else null
                                val msgId = System.currentTimeMillis()
                                val newMsg = Message(msgId, content, uriImagen, false, 0, progress = 100, originalSender = senderId, isGroup = true, expiresAt = expiresAt)
                                adapter.addMessage(newMsg)
                                activeMessagesTracker.add(newMsg)
                                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                                guardarMensajeEnBD(msgId, content, uriImagen?.toString(), false, 0, senderId, expiresAt)
                                mostrarNotificacionMensaje(chatIdentifier, content)
                            }
                        }
                        texto == "SYS:TYPING:1" -> { binding.tvStatus.text = "Escribiendo... ✍️"; binding.tvStatus.setTextColor(auraColor) }
                        texto == "SYS:TYPING:0" -> { actualizarEstadoConexion() }
                        texto == "SYS:NUDGE" -> {
                            hacerVibrarTelefono()
                            val msgId = System.currentTimeMillis()
                            val newMsg = Message(msgId, "¡Te ha enviado un zumbido! 📳", null, false, 0, originalSender = chatIdentifier, isGroup = isGroup)
                            adapter.addMessage(newMsg)
                            activeMessagesTracker.add(newMsg)
                            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                        }
                        else -> {
                            actualizarEstadoConexion()
                            val msgId = System.currentTimeMillis()
                            val newMsg = Message(msgId, texto, uriImagen, false, 0, progress = 100, originalSender = chatIdentifier, isGroup = false)
                            adapter.addMessage(newMsg)
                            activeMessagesTracker.add(newMsg)
                            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                            guardarMensajeEnBD(msgId, texto, uriImagen?.toString(), false, 0, chatIdentifier, null)
                            mostrarNotificacionMensaje(chatIdentifier, texto)
                        }
                    }
                }
            }

            P2PManager.onMessageSentListener = { msgId ->
                runOnUiThread {
                    adapter.updateMessageStatus(msgId, 1)
                    adapter.updateProgress(msgId, 100)
                }
                actualizarEstadoEnBD(msgId, 1)
            }

            P2PManager.onAckReceivedListener = { msgId ->
                runOnUiThread { adapter.updateMessageStatus(msgId, 2) }
                actualizarEstadoEnBD(msgId, 2)
            }
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnRemoveImage.setOnClickListener { selectedImageUri = null; binding.cvImagePreview.visibility = View.GONE }
        binding.btnAttach.setOnClickListener { showAttachmentMenu() }

        binding.btnSend.setOnLongClickListener {
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 40, 60, 20)
            }

            val switchDisable = Switch(this).apply {
                text = "Desactivar modo efímero"
                isChecked = ephemeralDuration == 0L
                setPadding(0, 0, 0, 40)
            }

            val inputLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }

            val etValue = EditText(this).apply {
                hint = "Cantidad"
                inputType = InputType.TYPE_CLASS_NUMBER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val spinnerUnit = Spinner(this).apply {
                adapter = ArrayAdapter(this@ChatActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Segundos", "Minutos", "Horas", "Días"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            inputLayout.addView(etValue)
            inputLayout.addView(spinnerUnit)

            layout.addView(switchDisable)
            layout.addView(inputLayout)

            fun updateInputs() {
                val disabled = switchDisable.isChecked
                etValue.isEnabled = !disabled
                spinnerUnit.isEnabled = !disabled
            }
            updateInputs()
            switchDisable.setOnCheckedChangeListener { _, _ -> updateInputs() }

            AlertDialog.Builder(this)
                .setTitle("Configurar Mensajes Efímeros")
                .setView(layout)
                .setPositiveButton("Guardar") { _, _ ->
                    if (switchDisable.isChecked) {
                        ephemeralDuration = 0L
                        Toast.makeText(this, "Mensajes efímeros desactivados", Toast.LENGTH_SHORT).show()
                        binding.etMessage.hint = "Escribe un mensaje..."
                    } else {
                        val valStr = etValue.text.toString().trim()
                        val value = valStr.toLongOrNull() ?: 0L
                        if (value > 0) {
                            val multiplier = when (spinnerUnit.selectedItemPosition) {
                                0 -> 1000L
                                1 -> 60 * 1000L
                                2 -> 60 * 60 * 1000L
                                3 -> 24 * 60 * 60 * 1000L
                                else -> 1000L
                            }
                            ephemeralDuration = value * multiplier
                            val unitStr = spinnerUnit.selectedItem.toString().lowercase()
                            Toast.makeText(this, "Modo efímero: Desaparecerán en $value $unitStr", Toast.LENGTH_SHORT).show()
                            binding.etMessage.hint = "Escribe un mensaje efímero (⏳)..."
                        } else {
                            Toast.makeText(this, "Ingresa un valor válido mayor a 0", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
            true
        }

        binding.btnNudge.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSentNudgeTime < 5000) {
                val faltan = (5000 - (currentTime - lastSentNudgeTime)) / 1000
                Toast.makeText(this, "Calma... espera $faltan s", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastSentNudgeTime = currentTime
            val msgId = System.currentTimeMillis()
            enviarPayloadSistema("SYS:NUDGE")
            val newMsg = Message(msgId, "Enviaste un zumbido 📳", null, true, 0, originalSender = myLocalUserId, isGroup = isGroup)
            adapter.addMessage(newMsg)
            activeMessagesTracker.add(newMsg)
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
        }

        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty() && isTyping) { isTyping = false; enviarPayloadSistema("SYS:TYPING:0") }
                else if (!s.isNullOrEmpty() && !isTyping) { isTyping = true; enviarPayloadSistema("SYS:TYPING:1") }
            }
        })

        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            val image = selectedImageUri
            if (text.isEmpty() && image == null) return@setOnClickListener

            val msgId = System.currentTimeMillis()
            val expiresAt = if (ephemeralDuration > 0) System.currentTimeMillis() + ephemeralDuration else null

            val newMsg = Message(msgId, text, image, true, 0, originalSender = myLocalUserId, isGroup = isGroup, expiresAt = expiresAt)
            adapter.addMessage(newMsg)
            activeMessagesTracker.add(newMsg)
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            guardarMensajeEnBD(msgId, text, image?.toString(), true, 0, myLocalUserId, expiresAt)

            binding.etMessage.text.clear()
            binding.cvImagePreview.visibility = View.GONE
            selectedImageUri = null
            isTyping = false
            enviarPayloadSistema("SYS:TYPING:0")

            if (isDebugMode) enviarMensajeSimulado(msgId, text, image)
            else {
                if (isGroup) {
                    val mensajeRaw = if (ephemeralDuration > 0) "GRP_EPH:${chatIdentifier}:${myLocalUserId}:${ephemeralDuration}:$text" else "TXT:$msgId:$text"
                    NexuMeshManager.enviarMensajeGrupoMesh(applicationContext, myLocalUserId, groupId, msgId, mensajeRaw)
                } else {
                    enviarMensajeP2PReal(msgId, text, image, ephemeralDuration)
                }
            }
        }
    }

    private fun iniciarLimpiezaEfimeros() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val now = System.currentTimeMillis()
                database.nexuDao().deleteExpiredMessages(now)
                database.nexuDao().deleteMessagesOfExpiredGroups(now)
                database.nexuDao().deleteExpiredGroups(now)

                withContext(Dispatchers.Main) {
                    val toRemove = activeMessagesTracker.filter { it.expiresAt != null && it.expiresAt < now }
                    toRemove.forEach { expiredMsg ->
                        adapter.removeMessage(expiredMsg.msgId)
                        activeMessagesTracker.remove(expiredMsg)
                    }

                    if (isGroup && groupExpirationTime != null) {
                        val remaining = groupExpirationTime!! - now
                        if (remaining > 0) {
                            val s = (remaining / 1000) % 60
                            val m = (remaining / (1000 * 60)) % 60
                            val h = (remaining / (1000 * 60 * 60)) % 24
                            val d = remaining / (1000 * 60 * 60 * 24)

                            val timeStr = when {
                                d > 0 -> "${d}d ${h}h ${m}m"
                                h > 0 -> "${h}h ${m}m ${s}s"
                                else -> "${m}m ${s}s"
                            }
                            binding.tvStatus.text = "⏱️ $timeStr"
                            binding.tvStatus.setTextColor(Color.parseColor("#FF3B30"))
                            binding.ivAddTime.visibility = View.VISIBLE
                        } else {
                            Toast.makeText(this@ChatActivity, "El grupo ha expirado.", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NexuMeshManager.distanceUpdateListener = null
    }

    private fun reenviarMensajesEnEspera() {
        lifecycleScope.launch(Dispatchers.IO) {
            val historial = database.nexuDao().getMessagesForUserSync(chatIdentifier, myLocalUserId)
            val pendientes = historial.filter { it.isMine && it.status == 0 }
            withContext(Dispatchers.Main) {
                if (pendientes.isNotEmpty()) {
                    Toast.makeText(this@ChatActivity, "Enviando ${pendientes.size} en espera", Toast.LENGTH_SHORT).show()
                    pendientes.forEach { msg ->
                        val uri = if (msg.imagePath != null) Uri.parse(msg.imagePath) else null
                        if (isGroup) {
                            NexuMeshManager.enviarMensajeGrupoMesh(applicationContext, myLocalUserId, groupId, msg.msgId, "TXT:${msg.msgId}:${msg.text}")
                        } else {
                            enviarMensajeP2PReal(msg.msgId, msg.text, uri, 0L)
                        }
                    }
                }
            }
        }
    }

    private fun hacerVibrarTelefono() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pattern = longArrayOf(0, 200, 100, 200)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 200, 100, 200), -1)
        }
    }

    // NUEVO: Verificamos silenciamiento antes de notificar
    private fun mostrarNotificacionMensaje(emisor: String, mensaje: String) {
        val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
        val mutedUntil = sharedPreferences.getLong("muted_until_${myLocalUserId}_${emisor}", 0L)

        // Si está silenciado "Para siempre" (-1) o el tiempo guardado aún no ha expirado, cancelamos.
        if (mutedUntil == -1L || mutedUntil > System.currentTimeMillis()) {
            return
        }

        val channelId = "nexu_messages"
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Mensajes de NexuChat", NotificationManager.IMPORTANCE_HIGH)
            notifManager.createNotificationChannel(channel)
        }
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("EXTRA_USER_NAME", emisor)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, emisor.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(emisor)
            .setContentText(mensaje)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        notifManager.notify(emisor.hashCode(), builder.build())
    }

    private fun actualizarEstadoConexion() {
        if (isGroup) {
            if (groupExpirationTime == null) {
                binding.tvStatus.text = "Grupo de chat"
                binding.tvStatus.setTextColor(getColor(R.color.text_primary))
            }
            return
        }

        val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
        if (P2PManager.currentEndpointName == chatIdentifier && P2PManager.currentEndpointId != null) {
            binding.tvStatus.text = "En línea 🟢"
            binding.tvStatus.setTextColor(getColor(R.color.text_primary))
            sharedPreferences.edit().putLong("last_seen_$chatIdentifier", System.currentTimeMillis()).apply()
            reenviarMensajesEnEspera()
        } else {
            val lastSeen = sharedPreferences.getLong("last_seen_$chatIdentifier", 0)
            if (lastSeen == 0L) binding.tvStatus.text = "Desconectado 🔴"
            else {
                val formatter = SimpleDateFormat("dd/MM hh:mm a", Locale.getDefault())
                binding.tvStatus.text = "Últ. vez: ${formatter.format(Date(lastSeen))} 🔴"
            }
            binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
        }
    }

    private fun enviarPayloadSistema(comando: String) {
        if (isGroup) return
        val msgId = System.currentTimeMillis()
        val mensajeRaw = "TXT:0:$comando"
        NexuMeshManager.enviarMensajeMesh(applicationContext, myLocalUserId, chatIdentifier, msgId, mensajeRaw)
    }

    private fun cargarAvatarContacto(avatar: String) {
        if (avatar.startsWith("file://") || avatar.startsWith("content://") || avatar.startsWith("/")) {
            binding.tvHeaderAvatar.visibility = View.GONE
            binding.ivHeaderAvatar.visibility = View.VISIBLE
            try { binding.ivHeaderAvatar.setImageURI(Uri.parse(avatar)) }
            catch (e: Exception) {
                binding.tvHeaderAvatar.visibility = View.VISIBLE
                binding.ivHeaderAvatar.visibility = View.GONE
                binding.tvHeaderAvatar.text = "👥"
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
            val now = System.currentTimeMillis()
            val cleanHistory = historial.filter { it.expiresAt == null || it.expiresAt > now }

            withContext(Dispatchers.Main) {
                setupRecyclerView()
                activeMessagesTracker.clear()
                cleanHistory.forEach { entidad ->
                    val uri = if (entidad.imagePath.isNullOrEmpty()) null else Uri.parse(entidad.imagePath)
                    val msg = Message(entidad.msgId, entidad.text, uri, entidad.isMine, entidad.status, originalSender = entidad.originalSender, isGroup = entidad.isGroup, expiresAt = entidad.expiresAt)
                    adapter.addMessage(msg)
                    activeMessagesTracker.add(msg)
                }
                if (adapter.itemCount > 0) binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    private fun guardarMensajeEnBD(msgId: Long, text: String, imagePath: String?, isMine: Boolean, status: Int, originalSender: String, expiresAt: Long?) {
        val entity = MessageEntity(msgId = msgId, localUserId = myLocalUserId, endpointId = chatIdentifier, originalSender = originalSender, isGroup = isGroup, text = text, imagePath = imagePath, isMine = isMine, status = status, timestamp = System.currentTimeMillis(), expiresAt = expiresAt)
        lifecycleScope.launch(Dispatchers.IO) { database.nexuDao().insertMessage(entity) }
    }

    private fun actualizarEstadoEnBD(msgId: Long, status: Int) {
        lifecycleScope.launch(Dispatchers.IO) { database.nexuDao().updateStatus(msgId, status) }
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter(
            auraColor = auraColor,
            onImageClick = { uri ->
                val uriStr = uri.toString()
                if (uriStr.endsWith(".mp4") || uriStr.contains("video") || uriStr.contains("VID_")) {
                    val intent = Intent(this, Class.forName("com.offline.nexu.ui.chat.VideoPlayerActivity")).apply { putExtra("VIDEO_URI", uriStr) }
                    startActivity(intent)
                } else {
                    val intent = Intent(this, ImageViewerActivity::class.java).apply { putExtra("IMAGE_URI", uriStr) }
                    startActivity(intent)
                }
            },
            onMessageLongClick = { message -> confirmarEliminacionMensaje(message.msgId) }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = adapter
    }

    private fun confirmarEliminacionMensaje(msgId: Long) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_delete_message)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.findViewById<Button>(R.id.btnDeleteMsg).setOnClickListener {
            adapter.removeMessage(msgId);
            activeMessagesTracker.removeAll { it.msgId == msgId }
            lifecycleScope.launch(Dispatchers.IO) { try { database.nexuDao().deleteMessageById(msgId) } catch (e: Exception) {} };
            dialog.dismiss()
        }
        dialog.findViewById<Button>(R.id.btnCancelDelete).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun enviarMensajeSimulado(msgId: Long, text: String, image: Uri?) {
        Handler(Looper.getMainLooper()).postDelayed({ adapter.updateMessageStatus(msgId, 1); actualizarEstadoEnBD(msgId, 1) }, 500)
        Handler(Looper.getMainLooper()).postDelayed({ adapter.updateMessageStatus(msgId, 2); actualizarEstadoEnBD(msgId, 2) }, 1500)
    }

    private fun enviarMensajeP2PReal(msgId: Long, text: String, imageUri: Uri?, duration: Long) {
        try {
            if (imageUri != null) {
                val endpointId = P2PManager.currentEndpointId ?: return
                val file = File(imageUri.path!!)
                if (file.exists()) {
                    val filePayload = Payload.fromFile(file)
                    val path = imageUri.toString()
                    val headerType = when {
                        path.contains("Documents") || path.endsWith(".pdf") || path.contains("DOC_") -> "DOC"
                        path.contains("Videos") || path.endsWith(".mp4") || path.contains("VID_") -> "VID"
                        else -> "IMG"
                    }
                    val header = if (duration > 0) "EPH:$msgId:$duration:$text" else "$headerType:${filePayload.id}:$msgId:$text"
                    val textPayload = Payload.fromBytes(header.toByteArray(Charsets.UTF_8))

                    P2PManager.outgoingPayloadsTracker[filePayload.id] = msgId
                    adapter.updateProgress(msgId, 0)

                    Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, textPayload)
                    Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, filePayload)
                }
            } else if (text.isNotEmpty()) {
                val mensajeRaw = if (duration > 0) "EPH:$msgId:$duration:$text" else "TXT:$msgId:$text"
                NexuMeshManager.enviarMensajeMesh(applicationContext, myLocalUserId, chatIdentifier, msgId, mensajeRaw)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showAttachmentMenu() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_attachment_menu, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.btnGallery).setOnClickListener { dialog.dismiss(); comprobarPermisosYAbrirGaleria() }
        view.findViewById<View>(R.id.btnCamera).setOnClickListener { dialog.dismiss(); takePictureLauncher.launch(null) }
        view.findViewById<View>(R.id.btnFiles).setOnClickListener { dialog.dismiss(); pickDocumentLauncher.launch(arrayOf("*/*")) }
        dialog.show()
    }

    private fun comprobarPermisosYAbrirGaleria() {
        val permissionImages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        val permissionVideos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

        if (ContextCompat.checkSelfPermission(this, permissionImages) != PackageManager.PERMISSION_GRANTED) {
            val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            requestGalleryPermissionLauncher.launch(perms)
        } else {
            openCustomGalleryBottomSheet()
        }
    }

    private fun openCustomGalleryBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_custom_gallery, null)
        val rvGallery = view.findViewById<RecyclerView>(R.id.rvCustomGallery)

        rvGallery.layoutManager = GridLayoutManager(this, 3)
        val mediaUris = obtenerMediosLocales()

        rvGallery.adapter = CustomGalleryAdapter(mediaUris) { uri -> procesarUriSeleccionada(uri, esDocumento = false); dialog.dismiss() }
        dialog.setContentView(view)
        dialog.show()
    }

    private fun obtenerMediosLocales(): List<Pair<Uri, Boolean>> {
        val mediaList = mutableListOf<Pair<Uri, Boolean>>()
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=" + "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR " + "${MediaStore.Files.FileColumns.MEDIA_TYPE}=" + "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"

        try {
            contentResolver.query(uri, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                var count = 0
                while (cursor.moveToNext() && count < 60) {
                    val id = cursor.getLong(idColumn)
                    val isVideo = cursor.getInt(typeColumn) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    val contentUri = Uri.withAppendedPath(uri, "" + id)
                    mediaList.add(Pair(contentUri, isVideo))
                    count++
                }
            }
        } catch (e: Exception) {}
        return mediaList
    }

    inner class CustomGalleryAdapter(private val media: List<Pair<Uri, Boolean>>, private val onClick: (Uri) -> Unit) : RecyclerView.Adapter<CustomGalleryAdapter.GalleryViewHolder>() {
        inner class GalleryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.ivGalleryThumbnail)
            val iconVideo: ImageView = view.findViewById(R.id.ivVideoIcon)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
            return GalleryViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_media, parent, false))
        }
        override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
            val (uri, isVideo) = media[position]
            holder.iconVideo.visibility = if (isVideo) View.VISIBLE else View.GONE
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { contentResolver.loadThumbnail(uri, android.util.Size(200, 200), null) }
                    else { MediaStore.Images.Media.getBitmap(contentResolver, uri) }
                    withContext(Dispatchers.Main) { holder.image.setImageBitmap(bmp) }
                } catch (e: Exception) {}
            }
            holder.itemView.setOnClickListener { onClick(uri) }
        }
        override fun getItemCount() = media.size
    }
}