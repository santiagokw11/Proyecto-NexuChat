package com.offline.nexu.ui.home

import android.Manifest
import android.content.Intent
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
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.databinding.ActivityHomeBinding
import com.offline.nexu.ui.chat.ChatActivity
import com.offline.nexu.ui.profile.ProfileActivity
import com.offline.nexu.utils.ThemeUtils
import java.io.File

// Activity principal del radar. Acá escaneamos la zona a ver quién anda cerca con la app abierta. 👀
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val SERVICE_ID = "com.offline.nexu.MESH_SERVICE"
    private lateinit var miPerfilP2P: String

    // Código ultra secreto para pedir permisos
    private val PERMISSION_REQUEST_CODE = 1234
    private var isDebugMode = false
    private var tapCount = 0
    private val tapHandler = Handler(Looper.getMainLooper())
    private val resetTapRunnable = Runnable { tapCount = 0 }

    private val contactosConocidos = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cargarPerfilUsuario()

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
            if (isDebugMode) {
                irAlChat(endpointId, nombreDestino)
            } else {
                preguntarSiConectar(endpointId, nombreDestino)
            }
        }

        binding.bottomNavigation.selectedItemId = R.id.nav_radar
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_radar -> true
                R.id.nav_chats -> {
                    val intent = Intent(this, com.offline.nexu.ui.chat.ChatsListActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_profile -> {
                    val intent = Intent(this, ProfileActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                else -> false
            }
        }

        setupEasterEgg()
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

            val fileUri = android.net.Uri.fromFile(File(avatarStr))
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
        cargarPerfilUsuario()
        binding.bottomNavigation.selectedItemId = R.id.nav_radar

        if (P2PManager.currentEndpointId != null) {
            binding.fabReturnChat.visibility = View.VISIBLE
        } else {
            binding.fabReturnChat.visibility = View.GONE
        }

        if (hasPermissions()) {
            if (!isDebugMode) {
                Nearby.getConnectionsClient(applicationContext).stopDiscovery()
                Nearby.getConnectionsClient(applicationContext).stopAdvertising()
                binding.radarView.clearUsers()
                iniciarRadarP2P()
            }
        } else {
            requestPermissions()
        }
    }

    private fun setupEasterEgg() {
        // Easter Egg: Toca 5 veces y activas el modo Debug 🕵️‍♂️ (útil cuando no hay nadie cerca para probar)
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
                    binding.radarView.addUser("debug_endpoint", "Robot", "🤖")
                } else {
                    binding.radarView.clearUsers()
                    iniciarRadarP2P()
                }
                tapCount = 0
            } else {
                tapHandler.postDelayed(resetTapRunnable, 1000)
            }
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
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
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            iniciarRadarP2P()
        }
    }

    private fun iniciarRadarP2P() {
        if (isDebugMode) return

        binding.tvRadarStatus.text = "Buscando colmena P2P..."
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        Nearby.getConnectionsClient(applicationContext).startAdvertising(miPerfilP2P, SERVICE_ID, connectionLifecycleCallback, options)

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        Nearby.getConnectionsClient(applicationContext).startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
    }

    // ARREGLO EXTREMO: Accede directamente al archivo físico saltándose el ContentResolver
    // Lo tuvimos que hacer porque Android se pone sus moños con los permisos de almacenamiento en Android 13+.
    private fun loadCircularAvatar(uriString: String): Bitmap? {
        try {
            val uri = Uri.parse(uriString)
            val path = uri.path ?: return null
            val file = File(path)
            if (!file.exists()) return null

            val original = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val thumb = ThumbnailUtils.extractThumbnail(original, 140, 140)

            val output = Bitmap.createBitmap(thumb.width, thumb.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint().apply { isAntiAlias = true }
            val rect = Rect(0, 0, thumb.width, thumb.height)
            val rectF = RectF(rect)

            canvas.drawRoundRect(rectF, thumb.width / 2f, thumb.height / 2f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            canvas.drawBitmap(thumb, rect, rect, paint)
            return output
        } catch (e: Exception) {
            e.printStackTrace()
            return null
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

            // Verificación para asegurar que pasamos a buscar el archivo
            val bitmap = if (savedAvatar.startsWith("file://") || savedAvatar.startsWith("/") || savedAvatar.startsWith("content://")) {
                loadCircularAvatar(savedAvatar)
            } else null

            if (contactosConocidos.contains(endpointId)) {
                sugerirReconexion(endpointId, nombre, emoji, bitmap)
            } else {
                binding.radarView.addUser(endpointId, nombre, emoji, bitmap)
                binding.tvRadarStatus.text = "¡Nodo encontrado en el área!"
            }
        }
        override fun onEndpointLost(endpointId: String) {
            binding.radarView.removeUser(endpointId)
        }
    }

    private fun sugerirReconexion(endpointId: String, nombre: String, emoji: String, bitmap: Bitmap?) {
        AlertDialog.Builder(this)
            .setTitle("Reconexión sugerida")
            .setMessage("Se detectó a $nombre. ¿Deseas restablecer el chat?")
            .setPositiveButton("Sí") { _, _ -> preguntarSiConectar(endpointId, nombre) }
            .setNegativeButton("No") { _, _ -> binding.radarView.addUser(endpointId, nombre, emoji, bitmap) }
            .show()
    }

    private fun preguntarSiConectar(endpointId: String, nombreDestino: String) {
        binding.tvRadarStatus.text = "Enviando solicitud a $nombreDestino..."
        Nearby.getConnectionsClient(applicationContext)
            .requestConnection(miPerfilP2P, endpointId, connectionLifecycleCallback)
            .addOnFailureListener {
                binding.tvRadarStatus.text = "No se pudo enviar la solicitud a $nombreDestino"
            }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val partes = info.endpointName.split("|")
            val nombre = if (partes.size > 1) partes[1] else info.endpointName

            if (info.isIncomingConnection) {
                AlertDialog.Builder(this@HomeActivity)
                    .setTitle("¡Conexión entrante!")
                    .setMessage("$nombre quiere conectarse contigo. ¿Aceptas?")
                    .setPositiveButton("Aceptar") { _, _ ->
                        P2PManager.currentEndpointId = endpointId
                        P2PManager.currentEndpointName = nombre
                        Nearby.getConnectionsClient(applicationContext).acceptConnection(endpointId, P2PManager.payloadCallback)
                    }
                    .setNegativeButton("Rechazar") { _, _ ->
                        Nearby.getConnectionsClient(applicationContext).rejectConnection(endpointId)
                    }
                    .setCancelable(false)
                    .show()
            } else {
                P2PManager.currentEndpointId = endpointId
                P2PManager.currentEndpointName = nombre
                Nearby.getConnectionsClient(applicationContext).acceptConnection(endpointId, P2PManager.payloadCallback)
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                Nearby.getConnectionsClient(applicationContext).stopDiscovery()
                Nearby.getConnectionsClient(applicationContext).stopAdvertising()

                if(!contactosConocidos.contains(endpointId)) contactosConocidos.add(endpointId)

                P2PManager.sendProfileHandshake(endpointId, Prefs(applicationContext).getUserProfile(), applicationContext)

                irAlChat(endpointId, P2PManager.currentEndpointName ?: "Conectado")
            } else {
                Toast.makeText(this@HomeActivity, "Conexión rechazada o cancelada", Toast.LENGTH_SHORT).show()
                binding.tvRadarStatus.text = "Buscando colmena P2P..."
                P2PManager.currentEndpointId = null
                P2PManager.currentEndpointName = null
            }
        }

        override fun onDisconnected(endpointId: String) {
            Toast.makeText(this@HomeActivity, "Desconectado de la red P2P", Toast.LENGTH_SHORT).show()
            P2PManager.currentEndpointId = null
            P2PManager.currentEndpointName = null
            binding.fabReturnChat.visibility = View.GONE
        }
    }

    private fun irAlChat(endpointId: String, nombreDestino: String) {
        val intent = Intent(this, ChatActivity::class.java)
        intent.putExtra("TARGET_ENDPOINT", endpointId)
        intent.putExtra("EXTRA_USER_NAME", nombreDestino)
        intent.putExtra("EXTRA_IS_DEBUG", isDebugMode)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}