package com.offline.nexu

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.offline.nexu.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    // El ID de mi servicio para que el radar sepa qué buscar. 
    // Tiene que coincidir siempre si no no se encuentran ni a palos.
    private val SERVICE_ID = "com.offline.nexu.MESH_SERVICE"
    private var opponentEndpointId: String? = null
    
    // Le clavo el modelo del celu (tipo 'Samsung Galaxy S22') como nombre para testear rápido.
    private val USER_NAME = Build.MODEL 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // El clásico ViewBinding para no andar lidiando con findViewById.
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Me aseguro de tener los permisos o la app va a volar por los aires.
        checkPermissions()

        // Botón gordo del medio para empezar a gritar por Bluetooth y buscar gente.
        binding.fab.setOnClickListener {
            startNearbyServices()
        }
    }

    private fun startNearbyServices() {
        // Arranco los dos procesos a la vez: anuncio que existo y busco a otros.
        startAdvertising()
        startDiscovery()
        Toast.makeText(this, "Nexu Mesh Activo", Toast.LENGTH_SHORT).show()
    }

    private fun startAdvertising() {
        // P2P_CLUSTER es clave acá porque es para grupos chicos que están re cerca.
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        Nearby.getConnectionsClient(this).startAdvertising(USER_NAME, SERVICE_ID, connectionLifecycleCallback, options)
    }

    private fun startDiscovery() {
        // Mismo mambo pero para buscar.
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        Nearby.getConnectionsClient(this).startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Apenas encuentro a alguien, le mando solicitud de una. Nada de preguntar. 
            // Re violento pero sirve para probar rápido.
            Nearby.getConnectionsClient(this@MainActivity).requestConnection(USER_NAME, endpointId, connectionLifecycleCallback)
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Acepto a ciegas. Si total estoy probando jaja.
            Nearby.getConnectionsClient(this@MainActivity).acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            // Si enganchó bien, tiro un Toast facherito para avisar que entramos a la colmena.
            if (result.status.isSuccess) {
                opponentEndpointId = endpointId
                Toast.makeText(this@MainActivity, "Conectado a la Colmena", Toast.LENGTH_SHORT).show()
            }
        }
        override fun onDisconnected(endpointId: String) {
            // Uy, se cortó. Borro el ID para no mandarle mensajes al vacío.
            opponentEndpointId = null
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Acá recibo los paquetes (Payloads).
            if (payload.type == Payload.Type.BYTES) {
                // Decodifico los bytes a texto UTF-8. 
                val message = String(payload.asBytes()!!, Charsets.UTF_8)
                // Por ahora solo lo pinto en un Toast porque no armé la UI del chat en esta pantalla vieja.
                Toast.makeText(this@MainActivity, "Mensaje: $message", Toast.LENGTH_LONG).show()
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun checkPermissions() {
        // Un bardo de permisos para que Google me deje usar Bluetooth y WiFi Direct. 
        // Cambia dependiendo de si el Android es viejo o nuevo (SDK 31+).
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Si me falta alguno, saco el cartel del sistema para pedirlos.
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions, 100)
        }
    }
}