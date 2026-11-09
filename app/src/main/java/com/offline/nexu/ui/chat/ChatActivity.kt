package com.offline.nexu.ui.chat

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.Payload
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.button.MaterialButton
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.data.model.Message
import com.offline.nexu.databinding.ActivityChatBinding
import com.offline.nexu.ui.chat.helpers.AttachmentHelper
import com.offline.nexu.ui.chat.helpers.EmojiHelper
import com.offline.nexu.ui.home.P2PManager
import com.offline.nexu.data.network.NexuMeshManager
import com.offline.nexu.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

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
    private var lastSentNudgeTime = 0L

    private var ephemeralDuration: Long = 0L
    private val activeMessagesTracker = mutableListOf<Message>()
    private var wasConnected = false
    private var lastSyncTime = 0L

    private lateinit var emojiHelper: EmojiHelper

    // Variables para notas de voz
    private var mediaRecorder: MediaRecorder? = null
    private var audioFilePath: String? = null
    private var isRecording = false
    private var isRecordingLocked = false
    private var recordStartMillis = 0L
    private val timerHandler = Handler(Looper.getMainLooper())
    private val amplitudesRecordadas = mutableListOf<Int>()

    private val requestGalleryPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            AttachmentHelper.openCustomGallery(this, layoutInflater) { uri -> procesarUriSeleccionada(uri, false) }
        } else Toast.makeText(this, "Permiso denegado", Toast.LENGTH_SHORT).show()
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Toast.makeText(this, "Permiso concedido. Mantén presionado para grabar.", Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, "Permiso de micrófono denegado", Toast.LENGTH_SHORT).show()
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        if (bitmap != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val file = File(externalCacheDir, "Cam_${System.currentTimeMillis()}.jpg")
                    val out = java.io.FileOutputStream(file)
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                    out.flush(); out.close()
                    withContext(Dispatchers.Main) {
                        selectedImageUri = Uri.fromFile(file)
                        binding.ivPreview.setImageURI(selectedImageUri)
                        binding.cvImagePreview.visibility = View.VISIBLE
                        emojiHelper.hide()
                        setButtonSendIcon()
                    }
                } catch (e: Exception) {}
            }
        }
    }

    private val pickDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            procesarUriSeleccionada(it, esDocumento = true)
            emojiHelper.hide()
        }
    }

    private fun procesarUriSeleccionada(uri: Uri, esDocumento: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val localUri = AttachmentHelper.copiarImagenLocal(this@ChatActivity, uri, esDocumento)
            withContext(Dispatchers.Main) {
                if (localUri != null) {
                    selectedImageUri = localUri
                    if (esDocumento) binding.ivPreview.setImageResource(android.R.drawable.ic_menu_save)
                    else {
                        val isVideo = localUri.toString().endsWith(".mp4") || localUri.toString().contains("VID_") || localUri.toString().contains("video")
                        if (isVideo) AttachmentHelper.cargarMiniaturaVideoVistaPrevia(this@ChatActivity, localUri, binding.ivPreview)
                        else binding.ivPreview.setImageURI(localUri)
                    }
                    binding.cvImagePreview.visibility = View.VISIBLE
                    setButtonSendIcon()
                }
            }
        }
    }

    private fun borrarArchivoLocalUri(uri: Uri?) {
        if (uri == null) return
        try {
            val file = File(uri.path ?: return)
            if (file.exists()) file.delete()
        } catch (e: Exception) {}
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
        binding.btnForwardSelectedMsgs.setColorFilter(auraColor)

        try { binding.root.findViewById<ImageView>(R.id.btnCopySelectedMsgs)?.setColorFilter(auraColor) } catch(e: Exception) {}
        try { binding.root.findViewById<ImageView>(R.id.btnEmoji)?.setColorFilter(auraColor) } catch(e: Exception) {}

        binding.btnSend.setImageResource(android.R.drawable.ic_btn_speak_now)

        setupRecyclerView()
        iniciarLimpiezaEfimeros()

        emojiHelper = EmojiHelper(binding.root, auraColor) { emoji -> binding.etMessage.append(emoji) }
        emojiHelper.setup()

        val llSearchBox = binding.root.findViewById<View>(R.id.llSearchBox)
        val etSearchMessages = binding.root.findViewById<EditText>(R.id.etSearchMessages)
        val btnCloseSearch = binding.root.findViewById<ImageView>(R.id.btnCloseSearch)

        if (intent.getBooleanExtra("EXTRA_SEARCH_MODE", false)) {
            llSearchBox?.visibility = View.VISIBLE
            etSearchMessages?.requestFocus()
        }

        btnCloseSearch?.setOnClickListener {
            llSearchBox?.visibility = View.GONE
            etSearchMessages?.text?.clear()
        }

        etSearchMessages?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        lifecycleScope.launch(Dispatchers.Main) {
            while (isActive) {
                actualizarEstadoConexion()
                delay(2000)
            }
        }

        NexuMeshManager.distanceUpdateListener = { distancia -> runOnUiThread { binding.tvDistance.text = "📍 $distancia" } }

        binding.llChatHeaderInfo.setOnClickListener {
            if (!isGroup) {
                val intent = Intent(this, ContactInfoActivity::class.java).apply { putExtra("EXTRA_USER_NAME", chatIdentifier) }
                startActivityForResult(intent, 100)
            } else {
                val intent = Intent(this, EditGroupActivity::class.java).apply { putExtra("EXTRA_GROUP_ID", groupId) }
                startActivity(intent)
            }
        }

        binding.ivAddTime.setOnClickListener {
            val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 40, 60, 20) }
            val inputLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 2f }
            val etValue = EditText(this).apply { hint = "Cantidad"; inputType = InputType.TYPE_CLASS_NUMBER; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            val spinnerUnit = Spinner(this).apply { adapter = ArrayAdapter(this@ChatActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Segundos", "Minutos", "Horas", "Días")); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            inputLayout.addView(etValue); inputLayout.addView(spinnerUnit); layout.addView(inputLayout)

            AlertDialog.Builder(this).setTitle("Añadir tiempo al grupo").setView(layout)
                .setPositiveButton("Agregar") { _, _ ->
                    val value = etValue.text.toString().trim().toLongOrNull() ?: 0L
                    if (value > 0) {
                        val multiplier = when (spinnerUnit.selectedItemPosition) { 0 -> 1000L; 1 -> 60 * 1000L; 2 -> 60 * 60 * 1000L; 3 -> 24 * 60 * 60 * 1000L; else -> 1000L }
                        groupExpirationTime = groupExpirationTime!! + (value * multiplier)
                        val myName = prefs.getUserProfile().name

                        lifecycleScope.launch(Dispatchers.IO) {
                            database.nexuDao().updateGroupExpiration(groupId, groupExpirationTime!!)
                            val cmd = "SYS:ADD_TIME:$groupId:${groupExpirationTime!!}"
                            NexuMeshManager.enviarMensajeGrupoMesh(applicationContext, myLocalUserId, myName, groupId, System.currentTimeMillis(), "TXT:0:$cmd")

                            val eventText = "$myName añadió tiempo extra al grupo"
                            NexuMeshManager.enviarMensajeGrupoMesh(applicationContext, myLocalUserId, myName, groupId, System.currentTimeMillis() + 10, "TXT:0:SYS:EVENT:$eventText")

                            val localMsg = MessageEntity(msgId = System.currentTimeMillis() + 20, localUserId = myLocalUserId, endpointId = groupId, originalSender = myLocalUserId, isGroup = true, text = "Añadiste tiempo extra al grupo", imagePath = null, isMine = true, status = 99, timestamp = System.currentTimeMillis())
                            database.nexuDao().insertMessage(localMsg)
                            withContext(Dispatchers.Main) { Toast.makeText(this@ChatActivity, "Tiempo extra añadido", Toast.LENGTH_SHORT).show() }
                        }
                    } else Toast.makeText(this, "Ingresa un valor válido", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Cancelar", null).show()
        }

        binding.btnCloseMsgSelection.setOnClickListener { adapter.clearSelection() }
        binding.btnDeleteSelectedMsgs.setOnClickListener { confirmarEliminacionMultiplesMensajes() }
        binding.btnForwardSelectedMsgs.setOnClickListener {
            val messagesToForward = adapter.messages.filter { it.msgId in adapter.selectedMessages }
            if (messagesToForward.isNotEmpty()) mostrarDialogoReenvioMultiple(messagesToForward)
        }

        binding.root.findViewById<ImageView>(R.id.btnCopySelectedMsgs)?.setOnClickListener {
            copiarMensajesSeleccionados()
        }

        actualizarEstadoConexion()

        if (!isDebugMode) {
            P2PManager.context = applicationContext
            P2PManager.onPayloadProgress = { msgId, progress -> runOnUiThread { adapter.updateProgress(msgId, progress) } }

            P2PManager.onMessageFailedListener = { msgId ->
                runOnUiThread { adapter.updateMessageStatus(msgId, -2); adapter.updateProgress(msgId, 0) }
                actualizarEstadoEnBD(msgId, -2)
            }

            P2PManager.onMessageReceivedListener = { incomingMsgId, texto, uriImagen ->
                runOnUiThread {
                    val isRefresh = texto.contains("SYS:REFRESH_GROUP")
                    val isEvent = texto.contains("SYS:EVENT:")
                    val isNudge = texto.contains("SYS:NUDGE")
                    val isTyping1 = texto.contains("SYS:TYPING:1")
                    val isTyping0 = texto.contains("SYS:TYPING:0")
                    val isAddTime = texto.contains("SYS:ADD_TIME:")

                    when {
                        isTyping1 -> { binding.tvStatus.text = "Escribiendo... ✍️"; binding.tvStatus.setTextColor(auraColor) }
                        isTyping0 -> { actualizarEstadoConexion() }
                        isRefresh -> { cargarHistorialDeMensajes() }
                        isAddTime -> {
                            val timePart = texto.substringAfterLast(":")
                            val newTime = timePart.toLongOrNull() ?: return@runOnUiThread
                            lifecycleScope.launch(Dispatchers.IO) {
                                val gId = if (isGroup) groupId else chatIdentifier
                                database.nexuDao().updateGroupExpiration(gId, newTime)
                                if (isGroup && groupId == gId) groupExpirationTime = newTime
                            }
                        }
                        isEvent -> {
                            val eventText = texto.substringAfter("SYS:EVENT:")
                            val sender = if (isGroup) texto.substringAfter("GRP_IN_CHAT:").substringBefore(":") else chatIdentifier
                            val newMsg = Message(incomingMsgId, eventText, null, false, 99, originalSender = sender, isGroup = isGroup)
                            adapter.addMessage(newMsg)
                            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                        }
                        isNudge -> {
                            val sender = if (isGroup) texto.substringAfter("GRP_IN_CHAT:").substringBefore(":") else chatIdentifier
                            val newMsg = Message(incomingMsgId, "¡Te han enviado un zumbido! 📳", null, false, 99, originalSender = sender, isGroup = isGroup)
                            adapter.addMessage(newMsg)
                            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                        }
                        texto.startsWith("GRP_IN_CHAT:") -> {
                            val parts = texto.split(":", limit = 3)
                            if (parts.size >= 3) {
                                val newMsg = Message(incomingMsgId, parts[2], uriImagen, false, 0, progress = 100, originalSender = parts[1], isGroup = true)
                                adapter.addMessage(newMsg)
                                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                            }
                        }
                        texto.startsWith("GRP_EPH_IN_CHAT:") -> {
                            val parts = texto.split(":", limit = 4)
                            if (parts.size >= 4) {
                                val expiresAt = if ((parts[2].toLongOrNull() ?: 0L) > 0) System.currentTimeMillis() + parts[2].toLong() else null
                                val newMsg = Message(incomingMsgId, parts[3], uriImagen, false, 0, progress = 100, originalSender = parts[1], isGroup = true, expiresAt = expiresAt)
                                adapter.addMessage(newMsg)
                                if (expiresAt != null) activeMessagesTracker.add(newMsg)
                                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                            }
                        }
                        texto.startsWith("EPH_IN_CHAT:") -> {
                            val parts = texto.split(":", limit = 3)
                            if (parts.size >= 3) {
                                val expiresAt = if ((parts[1].toLongOrNull() ?: 0L) > 0) System.currentTimeMillis() + parts[1].toLong() else null
                                val newMsg = Message(incomingMsgId, parts[2], uriImagen, false, 0, progress = 100, originalSender = chatIdentifier, isGroup = false, expiresAt = expiresAt)
                                adapter.addMessage(newMsg)
                                activeMessagesTracker.add(newMsg)
                                binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                            }
                        }
                        else -> {
                            actualizarEstadoConexion()
                            val cleanText = if (texto.startsWith("TXT:") || texto.startsWith("EPH:")) texto.substringAfterLast(":", texto) else texto
                            val newMsg = Message(incomingMsgId, cleanText, uriImagen, false, 0, progress = 100, originalSender = chatIdentifier, isGroup = false)
                            adapter.addMessage(newMsg)
                            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                        }
                    }
                    reenviarMensajesEnEspera()
                }
            }

            P2PManager.onMessageSentListener = { msgId -> runOnUiThread { adapter.updateMessageStatus(msgId, 1); adapter.updateProgress(msgId, 100) } }

            P2PManager.onAckReceivedListener = { msgId ->
                runOnUiThread {
                    adapter.updateMessageStatus(msgId, 2)
                    reenviarMensajesEnEspera()
                }
            }
        }

        binding.btnBack.setOnClickListener { finish() }

        binding.btnRemoveImage.setOnClickListener {
            borrarArchivoLocalUri(selectedImageUri)
            selectedImageUri = null
            binding.cvImagePreview.visibility = View.GONE
            setButtonSendIcon()
        }

        binding.root.findViewById<View>(R.id.btnGallery)?.setOnClickListener {
            binding.root.findViewById<View>(R.id.flAttachmentMenu)?.visibility = View.GONE
            val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (ContextCompat.checkSelfPermission(this, p[0]) != PackageManager.PERMISSION_GRANTED) requestGalleryPermissionLauncher.launch(p)
            else AttachmentHelper.openCustomGallery(this, layoutInflater) { uri -> procesarUriSeleccionada(uri, false) }
        }

        binding.root.findViewById<View>(R.id.btnCamera)?.setOnClickListener {
            binding.root.findViewById<View>(R.id.flAttachmentMenu)?.visibility = View.GONE
            takePictureLauncher.launch(null)
        }

        binding.root.findViewById<View>(R.id.btnFiles)?.setOnClickListener {
            binding.root.findViewById<View>(R.id.flAttachmentMenu)?.visibility = View.GONE
            pickDocumentLauncher.launch(arrayOf("*/*"))
        }

        binding.btnAttach.setOnClickListener {
            if (emojiHelper.isVisible()) emojiHelper.hide()
            val flAttachmentMenu = binding.root.findViewById<View>(R.id.flAttachmentMenu)
            if (flAttachmentMenu != null) {
                flAttachmentMenu.visibility = if (flAttachmentMenu.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }
        }

        binding.root.findViewById<ImageView>(R.id.btnEmoji)?.setOnClickListener { emojiHelper.toggle() }
        binding.etMessage.setOnClickListener { emojiHelper.hide() }

        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                setButtonSendIcon()
                if (s.isNullOrEmpty() && isTyping) { isTyping = false; enviarPayloadSistema(System.currentTimeMillis(), "SYS:TYPING:0") }
                else if (!s.isNullOrEmpty() && !isTyping) { isTyping = true; enviarPayloadSistema(System.currentTimeMillis(), "SYS:TYPING:1") }
            }
        })

        var initialX = 0f
        var initialY = 0f

        binding.btnSend.setOnTouchListener { view, event ->
            if (isRecordingLocked) {
                if (event.action == MotionEvent.ACTION_UP) view.performClick()
                return@setOnTouchListener true
            }

            val text = binding.etMessage.text.toString().trim()
            if (text.isNotEmpty() || selectedImageUri != null) {
                if (event.action == MotionEvent.ACTION_UP) view.performClick()
                return@setOnTouchListener true
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = event.rawX
                    initialY = event.rawY
                    isRecordingLocked = false
                    view.parent?.requestDisallowInterceptTouchEvent(true)

                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        startRecording()
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isRecording && !isRecordingLocked) {
                        val deltaX = initialX - event.rawX
                        val deltaY = initialY - event.rawY

                        if (deltaY > 50 && deltaY > abs(deltaX)) {
                            isRecordingLocked = true
                            binding.btnSend.setImageResource(android.R.drawable.ic_menu_send)
                            binding.root.findViewById<View>(R.id.btnCancelRecording)?.visibility = View.VISIBLE
                        }
                        else if (deltaX > 50 && deltaX > abs(deltaY)) {
                            stopRecording(cancel = true)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isRecording) {
                        if (isRecordingLocked) {
                            // Sigue grabando
                        } else {
                            val duration = System.currentTimeMillis() - recordStartMillis
                            if (duration < 1000) {
                                stopRecording(cancel = true)
                                Toast.makeText(this@ChatActivity, "Mantén presionado para grabar", Toast.LENGTH_SHORT).show()
                            } else {
                                stopRecording(cancel = event.action == MotionEvent.ACTION_CANCEL)
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }

        binding.btnSend.setOnClickListener {
            if (isRecordingLocked) {
                stopRecording(cancel = false)
                return@setOnClickListener
            }

            val text = binding.etMessage.text.toString().trim()
            val image = selectedImageUri
            if (text.isEmpty() && image == null) return@setOnClickListener

            emojiHelper.hide()
            val msgId = System.currentTimeMillis()
            val expiresAt = if (ephemeralDuration > 0) System.currentTimeMillis() + ephemeralDuration else null

            val newMsg = Message(msgId, text, image, true, 0, originalSender = myLocalUserId, isGroup = isGroup, expiresAt = expiresAt)
            adapter.addMessage(newMsg); activeMessagesTracker.add(newMsg); binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            guardarMensajeEnBD(msgId, text, image?.toString(), true, 0, myLocalUserId, expiresAt)

            binding.etMessage.text.clear(); binding.cvImagePreview.visibility = View.GONE; selectedImageUri = null; setButtonSendIcon()
            isTyping = false; enviarPayloadSistema(System.currentTimeMillis(), "SYS:TYPING:0")

            if (isDebugMode) enviarMensajeSimulado(msgId)
            else enviarMensajeRed(msgId, text, image, ephemeralDuration, isGroup)
        }

        binding.root.findViewById<View>(R.id.btnCancelRecording)?.setOnClickListener {
            if (isRecordingLocked) {
                stopRecording(cancel = true)
            }
        }

        binding.btnSend.setOnLongClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isEmpty() && selectedImageUri == null) return@setOnLongClickListener false

            val view = layoutInflater.inflate(R.layout.dialog_ephemeral_mode, null)
            val dialog = AlertDialog.Builder(this).setView(view).create()
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            view.findViewById<TextView>(R.id.tvEphemeralTitle).setTextColor(auraColor)
            view.findViewById<ImageView>(R.id.ivEphemeralIcon).setColorFilter(auraColor)
            val btnConfirm = view.findViewById<MaterialButton>(R.id.btnConfirmEphemeral)
            btnConfirm.setTextColor(auraColor)

            val switchEnable = view.findViewById<SwitchMaterial>(R.id.switchEnable)
            val etValue = view.findViewById<EditText>(R.id.etValue)
            val spinnerUnit = view.findViewById<Spinner>(R.id.spinnerUnit)
            val llTimeInput = view.findViewById<LinearLayout>(R.id.llTimeInput)

            val states = arrayOf(intArrayOf(-android.R.attr.state_checked), intArrayOf(android.R.attr.state_checked))
            switchEnable.thumbTintList = ColorStateList(states, intArrayOf(Color.LTGRAY, auraColor))
            switchEnable.trackTintList = ColorStateList(states, intArrayOf(Color.GRAY, ColorUtils.setAlphaComponent(auraColor, 100)))

            spinnerUnit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Segundos", "Minutos", "Horas", "Días"))
            switchEnable.isChecked = ephemeralDuration > 0L

            fun updateInputs() { val e = switchEnable.isChecked; etValue.isEnabled = e; spinnerUnit.isEnabled = e; llTimeInput.alpha = if (e) 1f else 0.5f }
            updateInputs(); switchEnable.setOnCheckedChangeListener { _, _ -> updateInputs() }

            view.findViewById<MaterialButton>(R.id.btnCancelEphemeral).setOnClickListener { dialog.dismiss() }
            btnConfirm.setOnClickListener {
                if (!switchEnable.isChecked) {
                    ephemeralDuration = 0L
                    Toast.makeText(this, "Mensajes efímeros desactivados", Toast.LENGTH_SHORT).show()
                    binding.etMessage.hint = "Escribe un mensaje..."
                    dialog.dismiss()
                } else {
                    val value = etValue.text.toString().trim().toLongOrNull() ?: 0L
                    if (value > 0) {
                        val m = when (spinnerUnit.selectedItemPosition) { 0 -> 1000L; 1 -> 60 * 1000L; 2 -> 60 * 60 * 1000L; 3 -> 24 * 60 * 60 * 1000L; else -> 1000L }
                        ephemeralDuration = value * m
                        Toast.makeText(this, "Modo efímero activado", Toast.LENGTH_SHORT).show()
                        binding.etMessage.hint = "Escribe un mensaje efímero (⏳)..."
                        dialog.dismiss()
                    } else Toast.makeText(this, "Valor inválido", Toast.LENGTH_SHORT).show()
                }
            }
            dialog.show()
            true
        }

        binding.btnNudge.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastSentNudgeTime < 5000) {
                Toast.makeText(this, "Espera ${(5000 - (currentTime - lastSentNudgeTime)) / 1000}s", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lastSentNudgeTime = currentTime; val msgId = System.currentTimeMillis()

            enviarPayloadSistema(msgId, "SYS:NUDGE")
            val newMsg = Message(msgId, "Enviaste un zumbido 📳", null, true, 99, originalSender = myLocalUserId, isGroup = isGroup)
            adapter.addMessage(newMsg); binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            guardarMensajeEnBD(msgId, "Enviaste un zumbido 📳", null, true, 99, myLocalUserId, null)
        }
    }

    private fun setButtonSendIcon() {
        if (binding.etMessage.text.isNullOrEmpty() && selectedImageUri == null && !isRecordingLocked) {
            binding.btnSend.setImageResource(android.R.drawable.ic_btn_speak_now)
        } else {
            binding.btnSend.setImageResource(android.R.drawable.ic_menu_send)
        }
    }

    private fun startRecording() {
        binding.btnSend.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

        val baseDir = externalMediaDirs.firstOrNull() ?: getExternalFilesDir(null)
        val mediaDir = File(baseDir, "NexuChat/Media/NexuChat Voice Notes/Sent")
        if (!mediaDir.exists()) mediaDir.mkdirs()
        File(mediaDir, ".nomedia").createNewFile()

        val ext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ".ogg" else ".m4a"
        val file = File(mediaDir, "AUD_${System.currentTimeMillis()}$ext")
        audioFilePath = file.absolutePath
        amplitudesRecordadas.clear()

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            } else {
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            setOutputFile(audioFilePath)
            try {
                prepare()
                start()
                isRecording = true
                recordStartMillis = System.currentTimeMillis()

                binding.etMessage.visibility = View.GONE
                binding.btnAttach.visibility = View.GONE
                binding.btnEmoji.visibility = View.GONE
                binding.btnNudge.visibility = View.GONE
                binding.root.findViewById<View>(R.id.llRecordingUI)?.visibility = View.VISIBLE
                binding.root.findViewById<View>(R.id.btnCancelRecording)?.visibility = View.GONE

                timerHandler.post(recordingTimerRunnable)
            } catch (e: Exception) {
                e.printStackTrace()
                isRecording = false
            }
        }
    }

    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                val amp = mediaRecorder?.maxAmplitude ?: 0
                amplitudesRecordadas.add(amp)

                val elapsed = System.currentTimeMillis() - recordStartMillis
                val s = (elapsed / 1000) % 60
                val m = (elapsed / (1000 * 60)) % 60

                binding.root.findViewById<TextView>(R.id.tvRecordingTime)?.text = String.format(Locale.getDefault(), "%02d:%02d", m, s)

                val blinkIcon = binding.root.findViewById<ImageView>(R.id.ivRecordingBlink)
                blinkIcon?.alpha = if (blinkIcon?.alpha == 1f) 0.3f else 1f
                timerHandler.postDelayed(this, 100)
            }
        }
    }

    private fun stopRecording(cancel: Boolean) {
        if (!isRecording) return
        val elapsed = System.currentTimeMillis() - recordStartMillis

        try { mediaRecorder?.stop() } catch (e: Exception) {}
        mediaRecorder?.release()
        mediaRecorder = null
        isRecording = false
        isRecordingLocked = false
        timerHandler.removeCallbacks(recordingTimerRunnable)

        binding.etMessage.visibility = View.VISIBLE
        binding.btnAttach.visibility = View.VISIBLE
        binding.btnEmoji.visibility = View.VISIBLE
        binding.btnNudge.visibility = View.VISIBLE
        binding.root.findViewById<View>(R.id.llRecordingUI)?.visibility = View.GONE

        if (cancel || elapsed < 1000) {
            audioFilePath?.let { File(it).delete() }
        } else {
            val s = (elapsed / 1000) % 60
            val m = (elapsed / (1000 * 60)) % 60
            val durationStr = String.format(Locale.getDefault(), "%02d:%02d", m, s)
            audioFilePath?.let { sendAudioMessage(it, durationStr) }
        }
        audioFilePath = null
        setButtonSendIcon()
    }

    private fun sendAudioMessage(filePath: String, durationStr: String) {
        val uri = Uri.fromFile(File(filePath))
        val msgId = System.currentTimeMillis()

        val maxAmp = amplitudesRecordadas.maxOrNull()?.coerceAtLeast(1) ?: 1
        val step = if (amplitudesRecordadas.size > 35) amplitudesRecordadas.size / 35.0 else 1.0
        val normalized = FloatArray(35) { i ->
            val index = (i * step).toInt().coerceAtMost(amplitudesRecordadas.size - 1)
            val raw = if (amplitudesRecordadas.isNotEmpty()) amplitudesRecordadas[index] else 0
            (raw.toFloat() / maxAmp.toFloat()).coerceIn(0.05f, 1f)
        }

        val ampString = normalized.joinToString(",")
        val text = "[Audio]:$durationStr:$ampString"
        val expiresAt = if (ephemeralDuration > 0) System.currentTimeMillis() + ephemeralDuration else null

        val newMsg = Message(msgId, text, uri, true, 0, originalSender = myLocalUserId, isGroup = isGroup, expiresAt = expiresAt)
        adapter.addMessage(newMsg)
        activeMessagesTracker.add(newMsg)
        binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
        guardarMensajeEnBD(msgId, text, uri.toString(), true, 0, myLocalUserId, expiresAt)

        if (isDebugMode) enviarMensajeSimulado(msgId)
        else enviarMensajeRed(msgId, text, uri, ephemeralDuration, isGroup)
    }

    override fun onDestroy() {
        super.onDestroy()
        adapter.releaseMediaPlayer()
    }

    private fun actualizarModoSeleccion(isSelectionMode: Boolean, count: Int) {
        val llInput = binding.root.findViewById<View>(R.id.llInputLayout)
        if (isSelectionMode) {
            binding.llChatHeaderInfo.visibility = View.GONE; binding.llMessageSelectionHeader.visibility = View.VISIBLE
            binding.tvMsgSelectionCount.text = count.toString(); llInput?.visibility = View.GONE
        } else {
            binding.llChatHeaderInfo.visibility = View.VISIBLE; binding.llMessageSelectionHeader.visibility = View.GONE
            llInput?.visibility = View.VISIBLE
        }
    }

    private fun confirmarEliminacionMultiplesMensajes() {
        val selectedIds = adapter.selectedMessages.toList()
        if (selectedIds.isEmpty()) return

        // Usamos layoutInflater y AlertDialog para que respete el tamaño y márgenes del diseño
        val view = layoutInflater.inflate(R.layout.dialog_delete_message, null)
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnDeleteMsg).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val msgsToDelete = adapter.messages.filter { it.msgId in selectedIds }
                msgsToDelete.forEach { msg -> borrarArchivoLocalUri(msg.imageUrl) }

                selectedIds.forEach { msgId -> try { database.nexuDao().deleteMessageById(msgId) } catch (e: Exception) {} }
                withContext(Dispatchers.Main) {
                    selectedIds.forEach { msgId -> adapter.removeMessage(msgId); activeMessagesTracker.removeAll { it.msgId == msgId } }
                    adapter.clearSelection()
                    Toast.makeText(this@ChatActivity, "Mensajes eliminados", Toast.LENGTH_SHORT).show()
                }
            }
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnCancelDelete).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    // --- FUNCIÓN PARA COPIAR MENSAJES ---
    private fun copiarMensajesSeleccionados() {
        val selectedIds = adapter.selectedMessages.toList()
        if (selectedIds.isEmpty()) return

        val messagesToCopy = adapter.messages.filter { it.msgId in selectedIds }
        // Se extrae solo el texto ignorando nulos o vacíos y separando con saltos de línea
        val textToCopy = messagesToCopy.mapNotNull { it.text }.filter { it.isNotBlank() && !it.startsWith("[Audio]") }.joinToString("\n\n")

        if (textToCopy.isNotEmpty()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Mensajes NexuChat", textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Mensajes copiados al portapapeles", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No hay texto para copiar", Toast.LENGTH_SHORT).show()
        }
        adapter.clearSelection()
    }

    private fun enviarPayloadSistema(msgId: Long, comando: String) {
        if (isGroup) return
        try {
            val myName = prefs.getUserProfile().name
            NexuMeshManager.enviarMensajeMesh(applicationContext, myLocalUserId, myName, chatIdentifier, msgId, "TXT:$msgId:$comando")
        } catch (e: Exception) {}
    }

    private fun enviarMensajeRed(msgId: Long, text: String, imageUri: Uri?, duration: Long, isGroupMsg: Boolean) {
        try {
            val eps = NexuMeshManager.connectedEndpoints.toList()
            val target = if (isGroupMsg) groupId else chatIdentifier
            if (!isGroupMsg && P2PManager.currentEndpointId == null && eps.isEmpty()) {
                runOnUiThread { adapter.updateMessageStatus(msgId, -2) }
                actualizarEstadoEnBD(msgId, -2)
                return
            }

            val myName = prefs.getUserProfile().name
            if (imageUri != null) {
                val file = File(imageUri.path!!)
                if (file.exists()) {
                    val filePayload = Payload.fromFile(file)
                    val headerType = when {
                        imageUri.toString().contains("Documents") || imageUri.toString().endsWith(".pdf") -> "DOC"
                        imageUri.toString().contains("Videos") || imageUri.toString().endsWith(".mp4") -> "VID"
                        imageUri.toString().endsWith(".ogg") || imageUri.toString().endsWith(".m4a") || imageUri.toString().endsWith(".mp3") -> "AUD"
                        else -> "IMG"
                    }
                    val header = if (duration > 0) "EPH:$msgId:$duration:$text" else "$headerType:${filePayload.id}:$msgId:$text"

                    if (isGroupMsg) NexuMeshManager.enviarMensajeGrupoMesh(applicationContext, myLocalUserId, myName, target, msgId, header)
                    else NexuMeshManager.enviarMensajeMesh(applicationContext, myLocalUserId, myName, target, msgId, header)

                    P2PManager.outgoingPayloadsTracker[filePayload.id] = msgId
                    runOnUiThread { adapter.updateProgress(msgId, 0) }

                    lifecycleScope.launch(Dispatchers.IO) {
                        delay(500)
                        try {
                            if (eps.isNotEmpty()) Nearby.getConnectionsClient(applicationContext).sendPayload(eps, filePayload)
                            else if (!isGroupMsg && P2PManager.currentEndpointId != null) Nearby.getConnectionsClient(applicationContext).sendPayload(P2PManager.currentEndpointId!!, filePayload)
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { adapter.updateMessageStatus(msgId, -2) }
                            actualizarEstadoEnBD(msgId, -2)
                        }
                    }
                } else {
                    runOnUiThread { adapter.updateMessageStatus(msgId, -2) }
                    actualizarEstadoEnBD(msgId, -2)
                }
            } else if (text.isNotEmpty()) {
                val header = if (duration > 0) "EPH:$msgId:$duration:$text" else "TXT:$msgId:$text"
                if (isGroupMsg) NexuMeshManager.enviarMensajeGrupoMesh(applicationContext, myLocalUserId, myName, target, msgId, header)
                else NexuMeshManager.enviarMensajeMesh(applicationContext, myLocalUserId, myName, target, msgId, header)
            }
        } catch (e: Exception) {
            runOnUiThread { adapter.updateMessageStatus(msgId, -2) }
            actualizarEstadoEnBD(msgId, -2)
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
                    toRemove.forEach {
                        borrarArchivoLocalUri(it.imageUrl)
                        adapter.removeMessage(it.msgId); activeMessagesTracker.remove(it)
                    }
                    if (isGroup && groupExpirationTime != null) {
                        val remaining = groupExpirationTime!! - now
                        if (remaining > 0) {
                            val s = (remaining / 1000) % 60; val m = (remaining / (1000 * 60)) % 60; val h = (remaining / (1000 * 60 * 60)) % 24; val d = remaining / (1000 * 60 * 60 * 24)
                            binding.tvStatus.text = "⏱️ " + when { d > 0 -> "${d}d ${h}h ${m}m"; h > 0 -> "${h}h ${m}m ${s}s"; else -> "${m}m ${s}s" }
                            binding.tvStatus.setTextColor(Color.parseColor("#FF3B30"))
                            binding.ivAddTime.visibility = View.VISIBLE
                        } else {
                            Toast.makeText(this@ChatActivity, "El grupo ha expirado.", Toast.LENGTH_SHORT).show(); finish()
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    private fun reenviarMensajesEnEspera() {
        val now = System.currentTimeMillis()
        if (now - lastSyncTime < 1500) return
        lastSyncTime = now

        lifecycleScope.launch(Dispatchers.IO) {
            val historial = database.nexuDao().getMessagesForUserSync(chatIdentifier, myLocalUserId)
            val pendientes = historial.filter { it.isMine && (it.status == 0 || it.status == 1 || it.status == -2) }
            val eps = NexuMeshManager.connectedEndpoints

            if (pendientes.isNotEmpty() && eps.isNotEmpty()) {
                pendientes.forEach { msg ->
                    val uri = if (msg.imagePath != null) Uri.parse(msg.imagePath) else null
                    withContext(Dispatchers.Main) { adapter.updateMessageStatus(msg.msgId, 0) }
                    enviarMensajeRed(msg.msgId, msg.text ?: "", uri, 0L, isGroup)
                    delay(300)
                }
            }
        }
    }

    private fun actualizarEstadoConexion() {
        if (isGroup) {
            lifecycleScope.launch(Dispatchers.IO) {
                val group = database.nexuDao().getGroup(groupId)
                val members = group?.members?.split(",")?.map { it.trim() } ?: emptyList()
                var isAnyConnected = false
                for (member in members) {
                    if (member != myLocalUserId && (P2PManager.connectedNames.containsValue(member) || NexuMeshManager.connectedEndpoints.contains(member))) {
                        isAnyConnected = true; break
                    }
                }
                withContext(Dispatchers.Main) {
                    if (groupExpirationTime == null) {
                        binding.tvStatus.text = if (isAnyConnected) "Grupo de chat (En línea 🟢)" else "Grupo de chat"
                        binding.tvStatus.setTextColor(getColor(R.color.text_primary))
                    }
                    if (isAnyConnected) { if (!wasConnected) { wasConnected = true; reenviarMensajesEnEspera() } }
                    else wasConnected = false
                }
            }
            return
        }

        val sharedPrefs = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
        val isConnected = (P2PManager.currentEndpointName == chatIdentifier) || P2PManager.connectedNames.containsValue(chatIdentifier) || NexuMeshManager.connectedEndpoints.contains(chatIdentifier)

        if (isConnected) {
            binding.tvStatus.text = "En línea 🟢"; binding.tvStatus.setTextColor(getColor(R.color.text_primary))
            sharedPrefs.edit().putLong("last_seen_$chatIdentifier", System.currentTimeMillis()).apply()
            if (!wasConnected) { wasConnected = true; reenviarMensajesEnEspera() }
        } else {
            wasConnected = false
            val lastSeen = sharedPrefs.getLong("last_seen_$chatIdentifier", 0)
            if (lastSeen == 0L) binding.tvStatus.text = "Desconectado 🔴"
            else binding.tvStatus.text = "Últ. vez: ${SimpleDateFormat("dd/MM hh:mm a", Locale.getDefault()).format(Date(lastSeen))} 🔴"
            binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
        }
    }

    private fun cargarAvatarContacto(avatar: String) {
        if (avatar.startsWith("file://") || avatar.startsWith("content://") || avatar.startsWith("/")) {
            binding.tvHeaderAvatar.visibility = View.GONE; binding.ivHeaderAvatar.visibility = View.VISIBLE
            try { binding.ivHeaderAvatar.setImageURI(Uri.parse(avatar)) } catch (e: Exception) { binding.tvHeaderAvatar.visibility = View.VISIBLE; binding.ivHeaderAvatar.visibility = View.GONE; binding.tvHeaderAvatar.text = "👥" }
        } else {
            binding.ivHeaderAvatar.visibility = View.GONE; binding.tvHeaderAvatar.visibility = View.VISIBLE; binding.tvHeaderAvatar.text = avatar
        }
    }

    override fun onResume() {
        super.onResume()
        P2PManager.activeChatIdentifier = chatIdentifier; P2PManager.currentActivity = this
        cargarHistorialDeMensajes(); actualizarEstadoConexion()
    }

    override fun onPause() {
        super.onPause()
        if (P2PManager.activeChatIdentifier == chatIdentifier) P2PManager.activeChatIdentifier = null
        if (P2PManager.currentActivity == this) P2PManager.currentActivity = null
    }

    private fun cargarHistorialDeMensajes() {
        lifecycleScope.launch(Dispatchers.IO) {
            val historial = database.nexuDao().getMessagesForUserSync(chatIdentifier, myLocalUserId)
            val cleanHistory = historial.filter { (it.expiresAt == null || it.expiresAt > System.currentTimeMillis()) && it.status != -1 }
            withContext(Dispatchers.Main) {
                setupRecyclerView(); activeMessagesTracker.clear()
                cleanHistory.forEach { entidad ->
                    val msg = Message(entidad.msgId, entidad.text, if (entidad.imagePath.isNullOrEmpty()) null else Uri.parse(entidad.imagePath), entidad.isMine, entidad.status, originalSender = entidad.originalSender, isGroup = entidad.isGroup, expiresAt = entidad.expiresAt)
                    adapter.addMessage(msg)
                    if (entidad.expiresAt != null) activeMessagesTracker.add(msg)
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
                if (uriStr.endsWith(".mp4") || uriStr.contains("video") || uriStr.contains("VID_")) startActivity(Intent(this, Class.forName("com.offline.nexu.ui.chat.VideoPlayerActivity")).apply { putExtra("VIDEO_URI", uriStr) })
                else startActivity(Intent(this, ImageViewerActivity::class.java).apply { putExtra("IMAGE_URI", uriStr) })
            },
            onRetryClick = { msg ->
                adapter.updateMessageStatus(msg.msgId, 0); actualizarEstadoEnBD(msg.msgId, 0)
                enviarMensajeRed(msg.msgId, msg.text ?: "", if (msg.imageUrl != null) msg.imageUrl else null, 0L, isGroup)
            },
            onSelectionModeChanged = { isSelectionMode, count -> actualizarModoSeleccion(isSelectionMode, count) }
        )
        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.rvMessages.adapter = adapter
    }

    private fun mostrarDialogoReenvioMultiple(mensajes: List<Message>) {
        lifecycleScope.launch(Dispatchers.IO) {
            val recentChats = database.nexuDao().getRecentChats(myLocalUserId).distinctBy { it.endpointId }
            val displayNames = recentChats.map { if (it.isGroup) database.nexuDao().getGroup(it.endpointId)?.groupName ?: "Grupo" else it.endpointId }

            withContext(Dispatchers.Main) {
                if (recentChats.isEmpty()) { Toast.makeText(this@ChatActivity, "No hay chats", Toast.LENGTH_SHORT).show(); return@withContext }
                val bottomSheet = BottomSheetDialog(this@ChatActivity)
                val view = layoutInflater.inflate(R.layout.dialog_forward_message, null)
                bottomSheet.setContentView(view)

                val llForwardChatList = view.findViewById<LinearLayout>(R.id.llForwardChatList)
                for (i in recentChats.indices) {
                    val chat = recentChats[i]; val targetId = chat.endpointId; val isTargetGroup = chat.isGroup; val displayName = displayNames[i]
                    val itemView = layoutInflater.inflate(R.layout.item_forward_chat, llForwardChatList, false)
                    itemView.findViewById<androidx.cardview.widget.CardView>(R.id.cvAvatar).setCardBackgroundColor(auraColor)
                    itemView.findViewById<TextView>(R.id.tvAvatarInitial).text = if (isTargetGroup) "👥" else displayName.take(1).uppercase()
                    itemView.findViewById<TextView>(R.id.tvChatName).text = displayName

                    itemView.setOnClickListener {
                        bottomSheet.dismiss(); mensajes.forEach { msg -> ejecutarReenvio(msg, targetId, isTargetGroup) }; adapter.clearSelection()
                    }
                    llForwardChatList.addView(itemView)
                }
                (view.parent as View).setBackgroundColor(Color.TRANSPARENT); bottomSheet.show()
            }
        }
    }

    private fun ejecutarReenvio(msg: Message, targetId: String, targetIsGroup: Boolean) {
        val newMsgId = System.currentTimeMillis() + (Math.random() * 1000).toLong()
        val text = msg.text ?: ""
        val imageUri = msg.imageUrl
        val entity = MessageEntity(msgId = newMsgId, localUserId = myLocalUserId, endpointId = targetId, originalSender = myLocalUserId, isGroup = targetIsGroup, text = text, imagePath = imageUri?.toString(), isMine = true, status = 0, timestamp = System.currentTimeMillis(), expiresAt = null)
        lifecycleScope.launch(Dispatchers.IO) { database.nexuDao().insertMessage(entity) }
        try {
            val myName = prefs.getUserProfile().name
            if (imageUri != null) {
                val file = File(imageUri.path!!)
                if (file.exists()) {
                    val filePayload = Payload.fromFile(file)
                    val headerType = when {
                        imageUri.toString().contains("Documents") || imageUri.toString().endsWith(".pdf") -> "DOC"
                        imageUri.toString().contains("Videos") || imageUri.toString().endsWith(".mp4") -> "VID"
                        imageUri.toString().endsWith(".ogg") || imageUri.toString().endsWith(".m4a") || imageUri.toString().endsWith(".mp3") -> "AUD"
                        else -> "IMG"
                    }
                    val realHeader = "$headerType:${filePayload.id}:$newMsgId:$text"

                    if (targetIsGroup) NexuMeshManager.enviarMensajeGrupoMesh(applicationContext, myLocalUserId, myName, targetId, newMsgId, realHeader)
                    else NexuMeshManager.enviarMensajeMesh(applicationContext, myLocalUserId, myName, targetId, newMsgId, realHeader)
                    P2PManager.outgoingPayloadsTracker[filePayload.id] = newMsgId

                    lifecycleScope.launch(Dispatchers.IO) {
                        delay(500)
                        try {
                            val targetEndpoint = P2PManager.connectedNames.entries.find { it.value == targetId }?.key
                            if (targetEndpoint != null) Nearby.getConnectionsClient(applicationContext).sendPayload(targetEndpoint, filePayload)
                            else {
                                val eps = NexuMeshManager.connectedEndpoints.toList()
                                if (eps.isNotEmpty()) Nearby.getConnectionsClient(applicationContext).sendPayload(eps, filePayload)
                            }
                        } catch (e: Exception) {}
                    }
                }
            } else {
                val realHeader = "TXT:$newMsgId:$text"
                if (targetIsGroup) NexuMeshManager.enviarMensajeGrupoMesh(applicationContext, myLocalUserId, myName, targetId, newMsgId, realHeader)
                else NexuMeshManager.enviarMensajeMesh(applicationContext, myLocalUserId, myName, targetId, newMsgId, realHeader)
            }
        } catch (e: Exception) {}
        Toast.makeText(this, "Mensaje reenviado", Toast.LENGTH_SHORT).show()
    }

    private fun enviarMensajeSimulado(msgId: Long) {
        Handler(Looper.getMainLooper()).postDelayed({ adapter.updateMessageStatus(msgId, 1); actualizarEstadoEnBD(msgId, 1) }, 500)
        Handler(Looper.getMainLooper()).postDelayed({ adapter.updateMessageStatus(msgId, 2); actualizarEstadoEnBD(msgId, 2) }, 1500)
    }
}