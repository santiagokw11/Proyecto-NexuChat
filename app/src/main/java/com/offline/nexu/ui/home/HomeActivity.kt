package com.offline.nexu.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.offline.nexu.databinding.ActivityHomeBinding
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.ui.chat.ChatActivity

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val SERVICE_ID = "com.offline.nexu.MESH_SERVICE"
    private lateinit var prefs: Prefs
    private lateinit var miPerfilP2P: String

    private val PERMISSION_REQUEST_CODE = 1234

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inflamos el diseño visual de la pantalla principal (Home).
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)

        // Traigo de mis preferencias el nombre y el emoji que elegí en mi perfil.
        val miNombre = prefs.getUsername()
        val miEmoji = prefs.getAvatar()
        
        // Junto el emoji y mi nombre separados por una línea vertical (|).
        // Así los enviaré a los demás en la red local.
        miPerfilP2P = "$miEmoji|$miNombre"

        // Si hago click en alguien de mi "Radar" visual, le pregunto al usuario si quiere conectar con él.
        binding.radarView.onUserClick = { endpointId, nombreDestino ->
            preguntarSiConectar(endpointId, nombreDestino)
        }

        // Para poder buscar gente cerca (Bluetooth, WiFi) necesito pedir permisos especiales.
        if (hasPermissions()) {
            iniciarRadarP2P() // Si ya los tengo, enciendo la antena de inmediato.
        } else {
            requestPermissions() // Si no, los pido.
        }
    }

    // Aquí tengo una lista de permisos que mi app necesita dependiendo de qué versión de Android tengas.
    private fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        // A partir de Android 12, Google se puso más estricto con el Bluetooth, así que pido estos tres nuevos.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        // A partir de Android 13, añadieron uno especial para redes WiFi cercanas.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return perms.toTypedArray()
    }

    // Función rápida para verificar si todos mis permisos están "verdes" (aprobados).
    private fun hasPermissions(): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Le lanzo al usuario la típica ventana pidiéndole acceso a ubicación y bluetooth.
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSION_REQUEST_CODE)
    }

    // Esta función reacciona cuando el usuario dice "Aceptar" o "Denegar" en la ventana de permisos.
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Si todos fueron concedidos, ¡arranco mi radar!
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                iniciarRadarP2P()
            } else {
                // Si me negó alguno, le echo la culpa a él en un mensajito 😁.
                Toast.makeText(this, "Faltan permisos. Ve a los ajustes de tu teléfono y dale acceso total.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun iniciarRadarP2P() {
        // Preparo Google Nearby Connections para funcionar como una red de tipo "enjambre" (P2P_CLUSTER).
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        // Uso `applicationContext` para que si roto la pantalla, mi conexión no muera súbitamente.
        // Aquí "anuncio" al mundo mi presencia ("¡Ey, me llamo $miPerfilP2P!").
        Nearby.getConnectionsClient(applicationContext).startAdvertising(miPerfilP2P, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener {
                Toast.makeText(this, "Antena de transmisión encendida 📡", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Fallo al transmitir: ${e.message}", Toast.LENGTH_LONG).show()
            }

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        // Al mismo tiempo que me anuncio, me pongo a "escuchar" si hay otros teléfonos haciendo lo mismo.
        Nearby.getConnectionsClient(applicationContext).startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
            .addOnSuccessListener {
                Toast.makeText(this, "Buscando colmena P2P...", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Fallo al buscar: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // Esto me avisa cuando encontré un teléfono o cuando uno que estaba cerca se apagó/se fue.
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // El otro teléfono manda su perfil. Lo divido para separar su Emoji de su Nombre.
            val partes = info.endpointName.split("|")
            val emoji = if (partes.size > 1) partes[0] else "📱"
            val nombre = if (partes.size > 1) partes[1] else info.endpointName
            // Lo dibujo en el radar visual de la pantalla.
            binding.radarView.addUser(endpointId, nombre, emoji)
        }
        override fun onEndpointLost(endpointId: String) {
            // Si el otro teléfono desaparece, lo borro del radar.
            binding.radarView.removeUser(endpointId)
        }
    }

    private fun preguntarSiConectar(endpointId: String, nombreDestino: String) {
        // Le tiro una confirmación para que no empiece a chatear por accidente.
        AlertDialog.Builder(this)
            .setTitle("¡Usuario detectado!")
            .setMessage("¿Quieres iniciar un chat P2P con $nombreDestino?")
            .setPositiveButton("Conectar") { _, _ ->
                // Si dice que sí, dejo de buscar a más gente temporalmente para enfocarme en conectar.
                Nearby.getConnectionsClient(applicationContext).stopDiscovery()

                // Abro la pantalla de chat pasándole el ID de la conexión y el nombre del chavo/a.
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("TARGET_ENDPOINT", endpointId)
                intent.putExtra("TARGET_NAME", nombreDestino)
                startActivity(intent)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Esto maneja las llamadas "entrantes", cuando otro teléfono me dice que quiere conectar conmigo.
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Igual que antes, separo su nombre del resto.
            val partes = info.endpointName.split("|")
            val nombre = if (partes.size > 1) partes[1] else info.endpointName

            // Le pregunto a mi usuario si quiere contestar a esta petición.
            AlertDialog.Builder(this@HomeActivity)
                .setTitle("¡Conexión entrante!")
                .setMessage("$nombre quiere chatear contigo. ¿Aceptas?")
                .setPositiveButton("Aceptar") { _, _ ->
                    // Si acepto, dejo de buscar más cosas.
                    Nearby.getConnectionsClient(applicationContext).stopDiscovery()

                    // Me voy a la pantalla de chat en modo "Yo soy el receptor".
                    val intent = Intent(this@HomeActivity, ChatActivity::class.java)
                    intent.putExtra("INCOMING_ENDPOINT", endpointId)
                    intent.putExtra("INCOMING_NAME", nombre)
                    startActivity(intent)
                }
                .setNegativeButton("Rechazar") { _, _ ->
                    // Si no me interesa, rechazo amablemente la conexión.
                    Nearby.getConnectionsClient(applicationContext).rejectConnection(endpointId)
                }
                .setCancelable(false) // No lo dejo esquivar tocando afuera de la ventana.
                .show()
        }

        // Estas no hacen mucho aquí porque la lógica pesada del chat vive en `ChatActivity`.
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {}
        override fun onDisconnected(endpointId: String) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        // Si apago mi pantalla de inicio, corto el radar y dejo de emitir para ahorrar batería.
        Nearby.getConnectionsClient(applicationContext).stopAdvertising()
        Nearby.getConnectionsClient(applicationContext).stopDiscovery()
    }
}