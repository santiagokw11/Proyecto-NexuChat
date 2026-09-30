package com.offline.nexu.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import com.offline.nexu.databinding.ActivityHomeBinding
import com.offline.nexu.ui.chat.ChatActivity

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    // A ver, defino el ID de mi servicio para que Nearby sepa con quién carajo tiene que hablar.
    private val SERVICE_ID = "com.offline.nexu.MESH_SERVICE"
    
    // Me guardo mi nombre y mi emoji para armar el 'perfil' que voy a transmitir por Bluetooth/WiFi.
    private lateinit var miPerfilP2P: String
    private lateinit var myName: String
    private lateinit var myEmoji: String

    // Permisos... qué dolor de huevos son los permisos en Android nuevo. Pongo un código cualquiera.
    private val PERMISSION_REQUEST_CODE = 1234
    
    // Puse esto del tapCount para hacer un easter egg y entrar a modo debug si tocan 5 veces la pantalla.
    private var isDebugMode = false
    private var tapCount = 0
    private val tapHandler = Handler(Looper.getMainLooper())
    private val resetTapRunnable = Runnable { tapCount = 0 }

    // Esta lista la guardo para saber con quién hablé y no andar pidiendo permiso de nuevo a los mismos vagos.
    private val contactosConocidos = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inflo la vista, clásico, ya ni lo pienso esto.
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Me traigo mis datos desde el Intent del Login. Si por alguna razón explota y viene vacío, le clavo Astronauta y un alien.
        myName = intent.getStringExtra("EXTRA_USER_NAME") ?: "Astronauta"
        myEmoji = intent.getStringExtra("EXTRA_EMOJI") ?: "👽"
        
        // Armo mi perfil separando el emoji y el nombre con un palito '|', re rústico pero me re sirve.
        miPerfilP2P = "$myEmoji|$myName"

        // Actualizo la UI para que diga quién soy.
        binding.tvMyName.text = myName
        binding.tvMyEmoji.text = myEmoji

        // El FAB (botón flotante) es por si ya estoy conectado con alguien y salí a esta pantalla sin querer.
        binding.fabReturnChat.setOnClickListener {
            val endpointActivo = P2PManager.currentEndpointId
            val nombreActivo = P2PManager.currentEndpointName ?: "Desconocido"
            if (endpointActivo != null) {
                // Si sigo conectado, lo mando al chat de una.
                irAlChat(endpointActivo, nombreActivo)
            } else {
                // Si se cortó, aviso y escondo el botón.
                Toast.makeText(this, "La conexión se ha perdido", Toast.LENGTH_SHORT).show()
                binding.fabReturnChat.visibility = View.GONE
            }
        }

        // Qué pasa cuando el usuario toca un puntito (usuario) en el radar...
        binding.radarView.onUserClick = { endpointId, nombreDestino ->
            if (isDebugMode) {
                // Si estoy haciendo pruebas, lo meto de una para no perder tiempo.
                irAlChat(endpointId, nombreDestino)
            } else {
                // Si es real, le mando la solicitud a Nearby.
                preguntarSiConectar(endpointId, nombreDestino)
            }
        }

        // Barra de abajo genérica.
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            Toast.makeText(this, "Herramienta seleccionada", Toast.LENGTH_SHORT).show()
            true
        }

        // Configuro el truquito de tocar 5 veces para debuggear.
        setupEasterEgg()
        iniciarServicioConexion()
    }

    override fun onResume() {
        super.onResume()
        // Si vuelvo a la pantalla y sigo conectado a alguien, muestro el botón flotante para volver a su chat.
        if (P2PManager.currentEndpointId != null) {
            binding.fabReturnChat.visibility = View.VISIBLE
        } else {
            binding.fabReturnChat.visibility = View.GONE
        }

        // Chequeo permisos. Si los tengo, apago cualquier búsqueda vieja y arranco de cero. Si no, los pido.
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

    private fun iniciarServicioConexion() {}

    private fun setupEasterEgg() {
        // El easter egg para el modo admin/debug, me sentí re hacker haciendo esto jaja.
        binding.viewEasterEgg.setOnClickListener {
            tapCount++
            tapHandler.removeCallbacks(resetTapRunnable)

            if (tapCount >= 5) {
                isDebugMode = !isDebugMode
                val modo = if (isDebugMode) "🛠 MODO DEBUG" else "🌐 MODO REAL"
                Toast.makeText(this, modo, Toast.LENGTH_LONG).show()
                binding.tvModoText.text = if (isDebugMode) "Tu nodo actual (DEBUG)" else "Tu nodo actual (Modo P2P)"

                // Apago todo lo de Nearby real para que no joda.
                Nearby.getConnectionsClient(applicationContext).stopAdvertising()
                Nearby.getConnectionsClient(applicationContext).stopDiscovery()

                if (isDebugMode) {
                    // Pinto un robot falso en el radar.
                    binding.tvRadarStatus.text = "Simulando usuarios..."
                    binding.radarView.clearUsers()
                    binding.radarView.addUser("debug_endpoint", "Robot", "🤖")
                } else {
                    // Vuelvo a la normalidad.
                    binding.radarView.clearUsers()
                    iniciarRadarP2P()
                }
                tapCount = 0
            } else {
                // Si tarda más de un segundo entre toques, le reseteo el contador.
                tapHandler.postDelayed(resetTapRunnable, 1000)
            }
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        // Todos los permisos que me pide Nearby dependiendo de la versión de Android. 
        // Es un dolor de cabeza pero hay que ponerlos todos.
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
        // Nada, recorro la lista de arriba y veo si el usuario me dio bola con los permisos.
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Si me los aceptó todos, arranco el radar. Si no, que se joda.
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            iniciarRadarP2P()
        }
    }

    private fun iniciarRadarP2P() {
        if (isDebugMode) return

        // Arranco el radar posta. Acá arranco a 'gritar' quién soy y a escuchar quién está cerca.
        binding.tvRadarStatus.text = "Buscando colmena P2P..."
        
        // Uso P2P_CLUSTER porque la documentación dice que es mejor para grupos de gente cerca.
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        Nearby.getConnectionsClient(applicationContext).startAdvertising(miPerfilP2P, SERVICE_ID, connectionLifecycleCallback, options)

        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        Nearby.getConnectionsClient(applicationContext).startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Parto el nombre que me llega con el '|' que puse antes para separar emoji y texto.
            val partes = info.endpointName.split("|")
            val emoji = if (partes.size > 1) partes[0] else "📱"
            val nombre = if (partes.size > 1) partes[1] else info.endpointName

            // Si es alguien conocido, le tiro un cartelito para reconectarse rápido.
            if (contactosConocidos.contains(endpointId)) {
                sugerirReconexion(endpointId, nombre)
            } else {
                // Si es nuevo, lo agrego a mi vista del radar para que el usuario le haga click.
                binding.radarView.addUser(endpointId, nombre, emoji)
                binding.tvRadarStatus.text = "¡Nodo encontrado en el área!"
            }
        }
        
        override fun onEndpointLost(endpointId: String) {
            // Uy, se fue o apagó el Bluetooth. Lo borro del radar.
            binding.radarView.removeUser(endpointId)
        }
    }

    private fun sugerirReconexion(endpointId: String, nombre: String) {
        // Ventanita clásica para que sea más fácil volver a hablar con alguien que se le cortó el WiFi.
        AlertDialog.Builder(this)
            .setTitle("Reconexión sugerida")
            .setMessage("Se detectó a $nombre. ¿Deseas restablecer el chat?")
            .setPositiveButton("Sí") { _, _ -> preguntarSiConectar(endpointId, nombre) }
            .setNegativeButton("No") { _, _ -> binding.radarView.addUser(endpointId, nombre, "👤") }
            .show()
    }

    private fun preguntarSiConectar(endpointId: String, nombreDestino: String) {
        // Le aviso a la API de Google que me quiero conectar con el pibe este.
        Nearby.getConnectionsClient(applicationContext)
            .requestConnection(miPerfilP2P, endpointId, connectionLifecycleCallback)
            .addOnSuccessListener { binding.tvRadarStatus.text = "Enviando solicitud a $nombreDestino..." }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Qué pasa cuando alguien se quiere conectar conmigo...
            val partes = info.endpointName.split("|")
            val nombre = if (partes.size > 1) partes[1] else info.endpointName

            // Metí un delay de un segundo y medio porque a veces la UI de Nearby es muy rápida y rompe mis carteles.
            Handler(Looper.getMainLooper()).postDelayed({
                AlertDialog.Builder(this@HomeActivity)
                    .setTitle("¡Conexión entrante!")
                    .setMessage("¿Conectar P2P con $nombre?")
                    .setPositiveButton("Aceptar") { _, _ ->
                        // Guardo su ID y nombre en mi P2PManager (Singleton) así no se pierde cuando cambio de pantalla.
                        P2PManager.currentEndpointId = endpointId
                        P2PManager.currentEndpointName = nombre
                        Nearby.getConnectionsClient(applicationContext).acceptConnection(endpointId, P2PManager.payloadCallback)
                    }
                    .setNegativeButton("Rechazar") { _, _ ->
                        Nearby.getConnectionsClient(applicationContext).rejectConnection(endpointId)
                    }
                    .setCancelable(false)
                    .show()
            }, 1500)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            // Chequeo si la conexión fue un éxito o si el otro vago me rechazó.
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                // Si aceptó, apago el radar porque ya conseguí compañero y no quiero gastar batería.
                Nearby.getConnectionsClient(applicationContext).stopDiscovery()
                Nearby.getConnectionsClient(applicationContext).stopAdvertising()

                // Lo guardo en la lista de amiguitos conocidos.
                if(!contactosConocidos.contains(endpointId)) contactosConocidos.add(endpointId)

                // Abro el chat posta pasándole el nombre que guardé antes.
                irAlChat(endpointId, P2PManager.currentEndpointName ?: "Conectado")
            } else {
                Toast.makeText(this@HomeActivity, "Conexión rechazada o fallida", Toast.LENGTH_SHORT).show()
                binding.tvRadarStatus.text = "Buscando colmena P2P..."
            }
        }

        override fun onDisconnected(endpointId: String) {
            // Uy, se cortó la luz o se fue lejos. Limpio las variables globales del Singleton para no causar bugs después.
            Toast.makeText(this@HomeActivity, "Desconectado de la red P2P", Toast.LENGTH_SHORT).show()
            P2PManager.currentEndpointId = null
            P2PManager.currentEndpointName = null
            binding.fabReturnChat.visibility = View.GONE
        }
    }

    private fun irAlChat(endpointId: String, nombreDestino: String) {
        // Nada, el típico intent para saltar a otra Activity mandándole la info por putExtra.
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