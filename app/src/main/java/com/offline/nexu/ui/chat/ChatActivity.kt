package com.offline.nexu.ui.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
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
    private var lastSentNudgeTime = 0L

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
        // Carga en segundo plano para evitar LAG
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
        chatIdentifier = intent.getStringExtra("EXTRA_USER_NAME") ?: "Astronauta"
        database = AppDatabase.getDatabase(this)

        auraColor = ThemeUtils.getAuraColor(this)
        binding.btnBack.setColorFilter(auraColor)
        binding.btnSend.backgroundTintList = ColorStateList.valueOf(auraColor)
        binding.btnNudge.setColorFilter(auraColor)
        binding.btnAttach.setColorFilter(auraColor)

        setupRecyclerView()
        binding.tvChatTitle.text = chatIdentifier
        cargarAvatarContacto(prefs.getContactAvatar(chatIdentifier))

        NexuMeshManager.distanceUpdateListener = { distancia ->
            runOnUiThread { binding.tvDistance.text = "📍 $distancia" }
        }

        binding.llChatHeaderInfo.setOnClickListener {
            val intent = Intent(this, ContactInfoActivity::class.java).apply { putExtra("EXTRA_USER_NAME", chatIdentifier) }
            startActivityForResult(intent, 100)
        }

        actualizarEstadoConexion()

        if (!isDebugMode) {
            P2PManager.context = applicationContext

            // Listener de progreso
            P2PManager.onPayloadProgress = { msgId, progress ->
                runOnUiThread { adapter.updateProgress(msgId, progress) }
            }

            P2PManager.onMessageReceivedListener = { endpointName, texto, uriImagen ->
                runOnUiThread {
                    when (texto) {
                        "SYS:TYPING:1" -> { binding.tvStatus.text = "Escribiendo... ✍️"; binding.tvStatus.setTextColor(auraColor) }
                        "SYS:TYPING:0" -> { actualizarEstadoConexion() }
                        "SYS:NUDGE" -> {
                            hacerVibrarTelefono()
                            val msgId = System.currentTimeMillis()
                            adapter.addMessage(Message(msgId, "¡Te ha enviado un zumbido! 📳", null, false, 0))
                            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                        }
                        else -> {
                            actualizarEstadoConexion()
                            val msgId = System.currentTimeMillis()
                            adapter.addMessage(Message(msgId, texto, uriImagen, false, 0, progress = 100))
                            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                            guardarMensajeEnBD(msgId, texto, uriImagen?.toString(), false, 0)
                            mostrarNotificacionMensaje(chatIdentifier, texto)
                        }
                    }
                }
            }

            P2PManager.onMessageSentListener = { msgId ->
                runOnUiThread {
                    adapter.updateMessageStatus(msgId, 1)
                    adapter.updateProgress(msgId, 100) // Oculta barra
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
            adapter.addMessage(Message(msgId, "Enviaste un zumbido 📳", null, true, 0))
            binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
        }

        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (P2PManager.currentEndpointId == null) return
                if (s.isNullOrEmpty() && isTyping) { isTyping = false; enviarPayloadSistema("SYS:TYPING:0") }
                else if (!s.isNullOrEmpty() && !isTyping) { isTyping = true; enviarPayloadSistema("SYS:TYPING:1") }
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

    override fun onDestroy() {
        super.onDestroy()
        NexuMeshManager.distanceUpdateListener = null
    }

    private fun reenviarMensajesEnEspera() {
        lifecycleScope.launch(Dispatchers.IO) {
            val historial = database.nexuDao().getMessagesForUserSync(chatIdentifier, myLocalUserId)
            val pendientes = historial.filter { it.isMine && it.status == 0 }
            withContext(Dispatchers.Main) {
                if (pendientes.isNotEmpty() && P2PManager.currentEndpointId != null) {
                    Toast.makeText(this@ChatActivity, "Enviando ${pendientes.size} en espera", Toast.LENGTH_SHORT).show()
                    pendientes.forEach { msg ->
                        val uri = if (msg.imagePath != null) Uri.parse(msg.imagePath) else null
                        enviarMensajeP2PReal(msg.msgId, msg.text ?: "", uri)
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

    private fun mostrarNotificacionMensaje(emisor: String, mensaje: String) {
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
            try { binding.ivHeaderAvatar.setImageURI(Uri.parse(avatar)) }
            catch (e: Exception) { binding.tvHeaderAvatar.visibility = View.VISIBLE; binding.ivHeaderAvatar.visibility = View.GONE }
        } else {
            binding.ivHeaderAvatar.visibility = View.GONE; binding.tvHeaderAvatar.visibility = View.VISIBLE
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
                if (adapter.itemCount > 0) binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
            }
        }
    }

    private fun guardarMensajeEnBD(msgId: Long, text: String, imagePath: String?, isMine: Boolean, status: Int) {
        val entity = MessageEntity(msgId = msgId, localUserId = myLocalUserId, endpointId = chatIdentifier, text = text, imagePath = imagePath, isMine = isMine, status = status, timestamp = System.currentTimeMillis())
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
            adapter.removeMessage(msgId); lifecycleScope.launch(Dispatchers.IO) { try { database.nexuDao().deleteMessageById(msgId) } catch (e: Exception) {} }; dialog.dismiss()
        }
        dialog.findViewById<Button>(R.id.btnCancelDelete).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun enviarMensajeSimulado(msgId: Long, text: String, image: Uri?) {
        Handler(Looper.getMainLooper()).postDelayed({ adapter.updateMessageStatus(msgId, 1); actualizarEstadoEnBD(msgId, 1) }, 500)
        Handler(Looper.getMainLooper()).postDelayed({ adapter.updateMessageStatus(msgId, 2); actualizarEstadoEnBD(msgId, 2) }, 1500)
    }

    private fun enviarMensajeP2PReal(msgId: Long, text: String, imageUri: Uri?) {
        val endpointId = P2PManager.currentEndpointId
        if (endpointId == null) return

        try {
            if (imageUri != null) {
                // Al usar "File", Nearby sabe exactamente el totalBytes
                val file = File(imageUri.path!!)

                if (file.exists()) {
                    val filePayload = Payload.fromFile(file)
                    val path = imageUri.toString()
                    val headerType = when {
                        path.contains("Documents") || path.endsWith(".pdf") || path.contains("DOC_") -> "DOC"
                        path.contains("Videos") || path.endsWith(".mp4") || path.contains("VID_") -> "VID"
                        else -> "IMG"
                    }

                    val header = "$headerType:${filePayload.id}:$msgId:$text"
                    val textPayload = Payload.fromBytes(header.toByteArray(Charsets.UTF_8))

                    P2PManager.outgoingPayloadsTracker[filePayload.id] = msgId

                    // Inicializamos visualmente la barra en 0%
                    adapter.updateProgress(msgId, 0)

                    Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, textPayload)
                    Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, filePayload)
                }
            } else if (text.isNotEmpty()) {
                val payload = Payload.fromBytes("TXT:$msgId:$text".toByteArray(Charsets.UTF_8))
                P2PManager.outgoingPayloadsTracker[payload.id] = msgId
                Nearby.getConnectionsClient(applicationContext).sendPayload(endpointId, payload)
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