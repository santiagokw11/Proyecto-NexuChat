package com.offline.nexu.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.offline.nexu.ConnectionService
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.databinding.ActivityHomeBinding
import com.offline.nexu.ui.chat.ChatActivity
import com.offline.nexu.ui.profile.ProfileActivity
import com.offline.nexu.ui.settings.SettingsActivity
import com.offline.nexu.utils.ThemeUtils
import com.offline.nexu.data.network.NexuMeshManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// GESTOR GLOBAL DE NOTIFICACIONES
object GlobalNotifications {
    data class NotificationItem(
        val endpointId: String,
        val nombre: String,
        val emoji: String,
        val isIncoming: Boolean
    )
    val pendingNotifications = mutableListOf<NotificationItem>()
    val rejectedConnectionsCount = mutableMapOf<String, Int>()
    const val MAX_REJECTS = 3

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyBadgeUpdate() {
        listeners.toList().forEach { it.invoke() }
    }
}

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val SERVICE_ID = "com.offline.nexu.MESH_NETWORK"
    private lateinit var miPerfilP2P: String

    private val PERMISSION_REQUEST_CODE = 1234
    private var isDebugMode = false
    private var tapCount = 0
    private val tapHandler = Handler(Looper.getMainLooper())
    private val resetTapRunnable = Runnable { tapCount = 0 }

    private lateinit var database: AppDatabase
    private lateinit var myLocalUserId: String
    private lateinit var sharedSettings: SharedPreferences

    private var isActivityVisible = false
    private var isRadarActive = true
    private var isVisibleToOthers = true // Nuevo estado para privacidad en red

    private val endpointNamesTemp = mutableMapOf<String, String>()

    private val badgeListener = { runOnUiThread { updateBellBadge() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)
        myLocalUserId = Prefs(this).getUserProfile().id
        sharedSettings = getSharedPreferences("NexuSettings", Context.MODE_PRIVATE)

        // Cargar estado inicial del radar sincronizado con privacidad
        isVisibleToOthers = !sharedSettings.getBoolean("radar_hidden", false)
        actualizarIconoVisibilidad()

        cargarPerfilUsuario()

        val serviceIntent = Intent(this, ConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        binding.fabReturnChat.setOnClickListener {
            val endpointActivo = P2PManager.currentEndpointId
            val nombreActivo = P2PManager.currentEndpointName ?: "Desconocido"
            if (endpointActivo != null) {
                irAlChat(endpointActivo, nombreActivo)
            } else {
                Toast.makeText(this, "La conexión se ha perdido", Toast.LENGTH_SHORT).show()
                binding.fabReturnChat.visibility = View.GONE
            }
        }

        binding.radarView.onUserClick = { endpointId, nombreDestino ->
            if (binding.cvNotificationsDropdown.visibility == View.VISIBLE) {
                toggleNotificationsMenu()
            } else {
                if (isDebugMode) {
                    irAlChat(endpointId, nombreDestino)
                } else {
                    preguntarSiConectar(endpointId, nombreDestino)
                }
            }
        }

        binding.ivBell.setOnClickListener {
            toggleNotificationsMenu()
        }

        binding.root.setOnClickListener {
            if (binding.cvNotificationsDropdown.visibility == View.VISIBLE) {
                toggleNotificationsMenu()
            }
        }

        binding.btnToggleRadar.setOnClickListener {
            isRadarActive = !isRadarActive
            actualizarEstadoRadar()
        }

        // Listener sincronizado para el botón de privacidad
        binding.btnVisibilityRadar.setOnClickListener {
            isVisibleToOthers = !isVisibleToOthers

            // Guardar instantáneamente para que SettingsPrivacyActivity lo lea
            sharedSettings.edit().putBoolean("radar_hidden", !isVisibleToOthers).apply()

            actualizarIconoVisibilidad()

            if (isVisibleToOthers) {
                Toast.makeText(this, "Radar visible: Los demás pueden encontrarte", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Modo fantasma: Eres invisible para los demás", Toast.LENGTH_SHORT).show()
            }

            // Aplicamos el cambio deteniendo la emisión pero manteniendo activo el radar visual
            if (isRadarActive && hasPermissions() && !isDebugMode) {
                val client = Nearby.getConnectionsClient(applicationContext)
                client.stopAdvertising() // Detenemos explícitamente la emisión
                if (isVisibleToOthers) {
                    binding.tvRadarStatus.text = "Escaneando colmena..."
                    val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
                    try { client.startAdvertising(miPerfilP2P, SERVICE_ID, connectionLifecycleCallback, options) } catch (e: Exception) {}
                } else {
                    binding.tvRadarStatus.text = "Escaneando en modo fantasma..."
                }
            }
        }

        binding.btnRefreshRadar.setOnClickListener {
            if (!isRadarActive) {
                Toast.makeText(this, "El radar está apagado", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            it.animate().rotationBy(360f).setDuration(500).start()

            binding.tvRadarStatus.text = "Reiniciando radar..."
            binding.radarView.clearUsers()

            val client = Nearby.getConnectionsClient(applicationContext)
            client.stopAdvertising()
            client.stopDiscovery()

            Handler(Looper.getMainLooper()).postDelayed({
                if (isRadarActive && hasPermissions() && !isDebugMode) {
                    iniciarRadarP2P()
                }
            }, 1000)
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_radar -> true
                R.id.nav_chats -> {
                    val intent = Intent(this, com.offline.nexu.ui.chat.ChatsListActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_profile -> {
                    val intent = Intent(this, ProfileActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_settings -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                else -> false
            }
        }

        setupEasterEgg()
    }

    private fun actualizarIconoVisibilidad() {
        if (isVisibleToOthers) {
            binding.btnVisibilityRadar.setImageResource(R.drawable.ic_visibility_on)
            binding.btnVisibilityRadar.alpha = 1.0f
        } else {
            binding.btnVisibilityRadar.setImageResource(R.drawable.ic_visibility_off)
            binding.btnVisibilityRadar.alpha = 0.5f
        }
    }

    private fun toggleNotificationsMenu() {
        if (binding.cvNotificationsDropdown.visibility == View.VISIBLE) {
            binding.cvNotificationsDropdown.animate()
                .alpha(0f)
                .translationY(-20f)
                .setDuration(150)
                .withEndAction { binding.cvNotificationsDropdown.visibility = View.GONE }
                .start()
        } else {
            refreshNotificationsDropdown()
            binding.cvNotificationsDropdown.alpha = 0f
            binding.cvNotificationsDropdown.translationY = -20f
            binding.cvNotificationsDropdown.visibility = View.VISIBLE
            binding.cvNotificationsDropdown.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(150)
                .start()
        }
    }

    private fun refreshNotificationsDropdown() {
        binding.llNotificationList.removeAllViews()

        if (GlobalNotifications.pendingNotifications.isEmpty()) {
            binding.tvEmptyNotifications.visibility = View.VISIBLE
        } else {
            binding.tvEmptyNotifications.visibility = View.GONE
            val iterator = GlobalNotifications.pendingNotifications.toList()
            val auraColor = ThemeUtils.getAuraColor(this)
            val textColorPrimary = ContextCompat.getColor(this, R.color.text_primary)
            val textColorSecondary = ContextCompat.getColor(this, R.color.text_secondary)

            for (notif in iterator) {
                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 32) }
                }

                val infoLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val emojiText = TextView(this).apply {
                    text = notif.emoji
                    textSize = 28f
                    setPadding(0, 0, 24, 0)
                }

                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val nameText = TextView(this).apply {
                    text = notif.nombre
                    setTextColor(textColorPrimary)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    textSize = 16f
                }

                val descText = TextView(this).apply {
                    text = if (notif.isIncoming) "Quiere conectarse contigo" else "Detectado en la red"
                    setTextColor(textColorSecondary)
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 4 }
                }

                textCol.addView(nameText)
                textCol.addView(descText)
                infoLayout.addView(emojiText)
                infoLayout.addView(textCol)

                val actionLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 24, 0, 0)
                }

                val btnReject = Button(this).apply {
                    text = "Ignorar"
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4A4A4A"))
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 16, 0)
                    }
                    setOnClickListener {
                        if (notif.isIncoming) {
                            Nearby.getConnectionsClient(applicationContext).rejectConnection(notif.endpointId)
                            val currentCount = GlobalNotifications.rejectedConnectionsCount[notif.endpointId] ?: 0
                            GlobalNotifications.rejectedConnectionsCount[notif.endpointId] = currentCount + 1
                        } else {
                            binding.radarView.addUser(notif.endpointId, notif.nombre, notif.emoji, null)
                        }
                        removerNotificacionYPopup(notif)
                    }
                }

                val btnAccept = Button(this).apply {
                    text = if (notif.isIncoming) "Aceptar" else "Conectar"
                    backgroundTintList = ColorStateList.valueOf(auraColor)
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        if (notif.isIncoming) {
                            if (P2PManager.currentEndpointId == null) {
                                P2PManager.currentEndpointId = notif.endpointId
                                P2PManager.currentEndpointName = notif.nombre
                            }
                            Nearby.getConnectionsClient(applicationContext).acceptConnection(notif.endpointId, NexuMeshManager.getMeshPayloadCallback(applicationContext))
                        } else {
                            preguntarSiConectar(notif.endpointId, notif.nombre)
                        }
                        removerNotificacionYPopup(notif)
                        toggleNotificationsMenu()
                    }
                }

                actionLayout.addView(btnReject)
                actionLayout.addView(btnAccept)

                itemLayout.addView(infoLayout)
                itemLayout.addView(actionLayout)

                if (iterator.indexOf(notif) < iterator.size - 1) {
                    val divider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                            setMargins(0, 16, 0, 0)
                        }
                        setBackgroundColor(Color.parseColor("#33808080"))
                    }
                    itemLayout.addView(divider)
                }

                binding.llNotificationList.addView(itemLayout)
            }
        }
    }

    private fun actualizarEstadoRadar() {
        val auraColor = ThemeUtils.getAuraColor(this)
        binding.radarView.isAnimating = isRadarActive

        if (isRadarActive) {
            binding.btnToggleRadar.setColorFilter(auraColor)
            binding.tvRadarStatus.text = if (isVisibleToOthers) "Escaneando colmena..." else "Escaneando en modo fantasma..."
            binding.radarView.invalidate()
            if (hasPermissions() && !isDebugMode) {
                iniciarRadarP2P()
            }
        } else {
            binding.btnToggleRadar.setColorFilter(Color.GRAY)
            binding.tvRadarStatus.text = "Radar apagado"
            Nearby.getConnectionsClient(applicationContext).stopAdvertising()
            Nearby.getConnectionsClient(applicationContext).stopDiscovery()
            binding.radarView.clearUsers()
        }
    }

    private fun updateBellBadge() {
        binding.vRedDot.visibility = if (GlobalNotifications.pendingNotifications.isNotEmpty()) View.VISIBLE else View.GONE
        if (binding.cvNotificationsDropdown.visibility == View.VISIBLE) {
            refreshNotificationsDropdown()
        }
    }

    private fun removerNotificacionYPopup(notif: GlobalNotifications.NotificationItem) {
        GlobalNotifications.pendingNotifications.remove(notif)
        GlobalNotifications.notifyBadgeUpdate()

        if (binding.cvIncoming.visibility == View.VISIBLE && binding.tvPopupName.text == notif.nombre) {
            binding.cvIncoming.visibility = View.GONE
        }
    }

    private fun cargarPerfilUsuario() {
        val prefs = Prefs(this)
        val profile = prefs.getUserProfile()
        binding.tvMyName.text = profile.name

        val auraColor = ThemeUtils.getAuraColor(this)

        binding.radarView.setAuraColor(auraColor)
        binding.tvMyName.setTextColor(auraColor)
        binding.radarCenterDot.backgroundTintList = ColorStateList.valueOf(auraColor)
        binding.fabReturnChat.backgroundTintList = ColorStateList.valueOf(auraColor)
        binding.ivBell.setColorFilter(auraColor)
        binding.btnToggleRadar.setColorFilter(auraColor)
        binding.btnRefreshRadar.setColorFilter(auraColor)
        binding.btnVisibilityRadar.setColorFilter(auraColor)

        val navColors = ThemeUtils.getBottomNavColorStateList(auraColor)
        binding.bottomNavigation.itemIconTintList = navColors
        binding.bottomNavigation.itemTextColor = navColors

        try {
            binding.tvMyEmoji.backgroundTintList = ColorStateList.valueOf(auraColor)
            binding.ivMyAvatar.backgroundTintList = ColorStateList.valueOf(auraColor)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val deviceId = prefs.getDeviceId()
        val avatarStr = profile.avatar
        if (avatarStr.startsWith("/")) {
            binding.tvMyEmoji.visibility = View.GONE
            binding.ivMyAvatar.visibility = View.VISIBLE

            val fileUri = Uri.fromFile(File(avatarStr))
            binding.ivMyAvatar.setImageURI(fileUri)
            binding.ivMyAvatar.clipToOutline = true

            miPerfilP2P = "🖼|${profile.name}|$deviceId"
        } else {
            binding.ivMyAvatar.visibility = View.GONE
            binding.tvMyEmoji.visibility = View.VISIBLE
            val displayEmoji = if (avatarStr.isNotEmpty()) avatarStr else "👤"
            binding.tvMyEmoji.text = displayEmoji
            miPerfilP2P = "$displayEmoji|${profile.name}|$deviceId"
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityVisible = true

        // Sincronizar el estado del radar por si se cambió desde Configuración de Privacidad
        isVisibleToOthers = !sharedSettings.getBoolean("radar_hidden", false)
        actualizarIconoVisibilidad()

        GlobalNotifications.addListener(badgeListener)
        updateBellBadge()
        cargarPerfilUsuario()
        binding.bottomNavigation.selectedItemId = R.id.nav_radar

        if (P2PManager.currentEndpointId != null) {
            binding.fabReturnChat.visibility = View.VISIBLE
        } else {
            binding.fabReturnChat.visibility = View.GONE
        }

        if (hasPermissions()) {
            if (!isDebugMode && isRadarActive) {
                iniciarRadarP2P()
            }
        } else {
            requestPermissions()
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityVisible = false
        GlobalNotifications.removeListener(badgeListener)
        overridePendingTransition(0, 0)
    }

    private fun setupEasterEgg() {
        binding.viewEasterEgg.setOnClickListener {
            tapCount++
            tapHandler.removeCallbacks(resetTapRunnable)

            if (tapCount >= 5) {
                isDebugMode = !isDebugMode
                val modo = if (isDebugMode) "🛠 MODO DEBUG" else "🌐 MODO REAL"
                Toast.makeText(this, modo, Toast.LENGTH_LONG).show()
                binding.tvModoText.text = if (isDebugMode) "Tu nodo actual (DEBUG)" else "Tu nodo actual (Modo P2P)"

                Nearby.getConnectionsClient(applicationContext).stopAdvertising()
                Nearby.getConnectionsClient(applicationContext).stopDiscovery()

                if (isDebugMode) {
                    binding.tvRadarStatus.text = "Simulando usuarios..."
                    binding.radarView.clearUsers()
                    binding.radarView.addUser("debug_endpoint", "Robot", "🤖", null)
                } else {
                    binding.radarView.clearUsers()
                    if (isRadarActive) iniciarRadarP2P()
                }
                tapCount = 0
            } else {
                tapHandler.postDelayed(resetTapRunnable, 1000)
            }
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return perms.toTypedArray()
    }

    private fun hasPermissions(): Boolean {
        return getRequiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (isRadarActive) iniciarRadarP2P()
        }
    }

    private fun iniciarRadarP2P() {
        if (isDebugMode) return

        val client = Nearby.getConnectionsClient(applicationContext)

        if (isVisibleToOthers) {
            binding.tvRadarStatus.text = "Buscando colmena P2P..."
            val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            try { client.startAdvertising(miPerfilP2P, SERVICE_ID, connectionLifecycleCallback, options) } catch (e: Exception) { e.printStackTrace() }
        } else {
            binding.tvRadarStatus.text = "Escaneando en modo fantasma..."
        }

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        try { client.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions) } catch (e: Exception) { e.printStackTrace() }
    }

    private fun loadCircularAvatar(uriString: String): Bitmap? {
        try {
            val uri = Uri.parse(uriString)
            val file = File(uri.path ?: return null)
            if (!file.exists()) return null

            val original = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val thumb = ThumbnailUtils.extractThumbnail(original, 140, 140)

            val output = Bitmap.createBitmap(thumb.width, thumb.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint().apply { isAntiAlias = true }
            val rect = Rect(0, 0, thumb.width, thumb.height)
            canvas.drawRoundRect(RectF(rect), thumb.width / 2f, thumb.height / 2f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(thumb, rect, rect, paint)
            return output
        } catch (e: Exception) { return null }
    }

    private fun gestionarNuevaNotificacion(notif: GlobalNotifications.NotificationItem) {
        val yaExiste = GlobalNotifications.pendingNotifications.any { it.endpointId == notif.endpointId }
        if (!yaExiste) {
            GlobalNotifications.pendingNotifications.add(notif)
            GlobalNotifications.notifyBadgeUpdate()

            if (binding.cvNotificationsDropdown.visibility == View.VISIBLE) {
                refreshNotificationsDropdown()
            }
        }

        if (notif.isIncoming) {
            if (isActivityVisible) {
                mostrarPopupEmergente(notif)
            } else {
                enviarNotificacionSistema(notif.endpointId, notif.nombre, "entrante")
            }
        }
    }

    private fun mostrarPopupEmergente(notif: GlobalNotifications.NotificationItem) {
        binding.cvIncoming.visibility = View.VISIBLE
        binding.tvPopupName.text = notif.nombre
        binding.tvPopupEmoji.text = notif.emoji

        binding.btnPopupAccept.setOnClickListener {
            binding.cvIncoming.visibility = View.GONE
            if (P2PManager.currentEndpointId == null) {
                P2PManager.currentEndpointId = notif.endpointId
                P2PManager.currentEndpointName = notif.nombre
            }
            Nearby.getConnectionsClient(applicationContext).acceptConnection(notif.endpointId, NexuMeshManager.getMeshPayloadCallback(applicationContext))
            removerNotificacionYPopup(notif)
        }

        binding.btnPopupReject.setOnClickListener {
            binding.cvIncoming.visibility = View.GONE
            Nearby.getConnectionsClient(applicationContext).rejectConnection(notif.endpointId)
            val currentCount = GlobalNotifications.rejectedConnectionsCount[notif.endpointId] ?: 0
            GlobalNotifications.rejectedConnectionsCount[notif.endpointId] = currentCount + 1
            removerNotificacionYPopup(notif)
        }
    }

    private fun enviarNotificacionSistema(endpointId: String, nombre: String, tipo: String) {
        val channelId = "nexu_connections"
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Solicitudes de Radar", android.app.NotificationManager.IMPORTANCE_HIGH)
            notifManager.createNotificationChannel(channel)
        }

        val notifId = endpointId.hashCode()

        if (tipo == "entrante") {
            val acceptIntent = Intent(this, Class.forName("com.offline.nexu.notifications.ConnectionReceiver")).apply {
                action = "ACCEPT_CONNECTION"
                putExtra("ENDPOINT_ID", endpointId)
                putExtra("NOTIF_ID", notifId)
                putExtra("NOMBRE", nombre)
            }
            val acceptPending = android.app.PendingIntent.getBroadcast(this, 0, acceptIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

            val rejectIntent = Intent(this, Class.forName("com.offline.nexu.notifications.ConnectionReceiver")).apply {
                action = "REJECT_CONNECTION"
                putExtra("ENDPOINT_ID", endpointId)
                putExtra("NOTIF_ID", notifId)
                putExtra("NOMBRE", nombre)
            }
            val rejectPending = android.app.PendingIntent.getBroadcast(this, 1, rejectIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)

            val builder = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Nueva conexión 📡")
                .setContentText("¡$nombre quiere conectarse!")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Rechazar", rejectPending)
                .addAction(android.R.drawable.ic_menu_add, "Aceptar", acceptPending)
                .setAutoCancel(true)
                .setTimeoutAfter(60000)

            notifManager.notify(notifId, builder.build())
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val partes = info.endpointName.split("|")
            val emoji = if (partes.isNotEmpty()) partes[0] else "📱"
            val nombre = if (partes.size > 1) partes[1] else info.endpointName
            val deviceId = if (partes.size > 2) partes[2] else ""

            if (deviceId == Prefs(applicationContext).getDeviceId()) return

            val savedAvatar = Prefs(applicationContext).getContactAvatar(nombre)
            val bitmap = if (savedAvatar.startsWith("file://") || savedAvatar.startsWith("/") || savedAvatar.startsWith("content://")) loadCircularAvatar(savedAvatar) else null

            lifecycleScope.launch(Dispatchers.IO) {
                val chatsActivos = database.nexuDao().getRecentChats(myLocalUserId)
                val esConocidoLocal = chatsActivos.any { it.endpointId == nombre }

                withContext(Dispatchers.Main) {
                    val isConnected = NexuMeshManager.connectedEndpoints.contains(endpointId) || P2PManager.connectedNames.containsValue(nombre)

                    if (!isConnected) {
                        binding.radarView.addUser(endpointId, nombre, emoji, bitmap)
                    }

                    if (esConocidoLocal && !isConnected) {
                        val notif = GlobalNotifications.NotificationItem(endpointId, nombre, emoji, false)
                        gestionarNuevaNotificacion(notif)
                    } else if (!isConnected) {
                        if(isVisibleToOthers) {
                            binding.tvRadarStatus.text = "¡Nodo encontrado en el área!"
                        }
                    }
                }
            }
        }
        override fun onEndpointLost(endpointId: String) {
            binding.radarView.removeUser(endpointId)
        }
    }

    private fun preguntarSiConectar(endpointId: String, nombreDestino: String) {
        binding.tvRadarStatus.text = "Enviando solicitud a $nombreDestino..."
        Nearby.getConnectionsClient(applicationContext).disconnectFromEndpoint(endpointId)
        Nearby.getConnectionsClient(applicationContext)
            .requestConnection(miPerfilP2P, endpointId, connectionLifecycleCallback)
            .addOnFailureListener {
                binding.tvRadarStatus.text = "No se pudo enviar la solicitud a $nombreDestino"
            }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val rejectCount = GlobalNotifications.rejectedConnectionsCount[endpointId] ?: 0
            if (rejectCount >= GlobalNotifications.MAX_REJECTS) {
                Nearby.getConnectionsClient(applicationContext).rejectConnection(endpointId)
                return
            }

            val partes = info.endpointName.split("|")
            val nombre = if (partes.size > 1) partes[1] else info.endpointName
            val emoji = if (partes.isNotEmpty()) partes[0] else "📱"

            endpointNamesTemp[endpointId] = nombre

            if (info.isIncomingConnection) {
                NexuMeshManager.payloadCallbackPendiente = NexuMeshManager.getMeshPayloadCallback(applicationContext)
                val notif = GlobalNotifications.NotificationItem(endpointId, nombre, emoji, true)
                gestionarNuevaNotificacion(notif)
            } else {
                if (P2PManager.currentEndpointId == null) {
                    P2PManager.currentEndpointId = endpointId
                    P2PManager.currentEndpointName = nombre
                }
                Nearby.getConnectionsClient(applicationContext).acceptConnection(endpointId, NexuMeshManager.getMeshPayloadCallback(applicationContext))
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val iterator = GlobalNotifications.pendingNotifications.toList()
            for (n in iterator) {
                if (n.endpointId == endpointId) removerNotificacionYPopup(n)
            }

            val nombreDestino = endpointNamesTemp[endpointId] ?: P2PManager.currentEndpointName ?: "Conectado"

            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                NexuMeshManager.connectedEndpoints.add(endpointId)
                P2PManager.connectedNames[endpointId] = nombreDestino

                if (P2PManager.currentEndpointId == null) {
                    P2PManager.currentEndpointId = endpointId
                    P2PManager.currentEndpointName = nombreDestino
                }

                binding.radarView.removeUser(endpointId)
                P2PManager.sendProfileHandshake(endpointId, Prefs(applicationContext).getUserProfile(), applicationContext)

                Toast.makeText(this@HomeActivity, "Conexión establecida con $nombreDestino", Toast.LENGTH_SHORT).show()
                binding.tvRadarStatus.text = if (isVisibleToOthers) "Buscando colmena P2P..." else "Escaneando en modo fantasma..."
            } else {
                Toast.makeText(this@HomeActivity, "Conexión rechazada o fallida", Toast.LENGTH_SHORT).show()
                binding.tvRadarStatus.text = if (isVisibleToOthers) "Buscando colmena P2P..." else "Escaneando en modo fantasma..."
                if (P2PManager.currentEndpointId == endpointId) {
                    P2PManager.currentEndpointId = null
                    P2PManager.currentEndpointName = null
                }
                NexuMeshManager.connectedEndpoints.remove(endpointId)
                P2PManager.connectedNames.remove(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            Toast.makeText(this@HomeActivity, "Desconectado de la red P2P", Toast.LENGTH_SHORT).show()
            if (P2PManager.currentEndpointId == endpointId) {
                P2PManager.currentEndpointId = null
                P2PManager.currentEndpointName = null
            }
            NexuMeshManager.connectedEndpoints.remove(endpointId)
            P2PManager.connectedNames.remove(endpointId)
            endpointNamesTemp.remove(endpointId)

            if (P2PManager.currentEndpointId == null && P2PManager.connectedNames.isNotEmpty()) {
                val first = P2PManager.connectedNames.entries.first()
                P2PManager.currentEndpointId = first.key
                P2PManager.currentEndpointName = first.value
            }

            binding.fabReturnChat.visibility = if (P2PManager.currentEndpointId != null) View.VISIBLE else View.GONE
        }
    }

    private fun irAlChat(endpointId: String, nombreDestino: String) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("TARGET_ENDPOINT", endpointId)
        intent.putExtra("EXTRA_USER_NAME", nombreDestino)
        intent.putExtra("EXTRA_IS_DEBUG", isDebugMode)
        startActivity(intent)
    }
}