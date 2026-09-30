package com.offline.nexu.ui.login

import android.Manifest
import android.R
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.databinding.ActivityLoginBinding
import com.offline.nexu.ui.home.HomeActivity

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: Prefs
    
    // Emoji por defecto si el usuario es un aburrido que no elige nada.
    private var currentEmoji = "👤"

    // La listita falopa de emojis para que la gente elija. Se podría hacer mejor con un RecyclerView, pero esto es rápido.
    private val avatarOptions = listOf("👤", "🐱", "🐶", "🦊", "🤖", "👾", "😎", "🚀", "🌈", "🔥")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Instancio mis SharedPreferences re piolas para guardar cosas básicas.
        prefs = Prefs(this)

        // Me fijo si el usuario ya se había registrado antes. Si es así, lo pateo al Home directamente.
        if (prefs.isRegistered()) {
            // PERO OJO. Nearby Connections (la API de Google) no anda si el GPS está apagado. 
            // Así que si está apagado, le tiro una alerta molesta antes de dejarlo pasar.
            if (isLocationEnabled()) {
                goToHome()
            } else {
                showLocationWarning()
            }
            return // Corto el onCreate acá nomás para que no cargue la pantalla de login al pedo.
        }

        // Si llegó hasta acá es porque es un usuario nuevo. Inflo la pantalla de login.
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Le tiro la lluvia de permisos que necesita Android para Bluetooth y Wi-Fi de una.
        checkAndRequestPermissions()

        // Si tocan el botón del emoji, les muestro una lista fea nativa de Android para que elijan.
        binding.btnChangeEmoji.setOnClickListener {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Selecciona tu estilo")
            val adapter = ArrayAdapter(this, R.layout.simple_list_item_1, avatarOptions)
            builder.setAdapter(adapter) { _, which ->
                currentEmoji = avatarOptions[which]
                binding.tvSelectedEmoji.text = currentEmoji
            }
            builder.show()
        }

        // Botón de Registrarse / Entrar.
        binding.btnRegister.setOnClickListener {
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            // Último chequeo de GPS por si el vivo me apagó la ubicación.
            if (!isLocationEnabled()) {
                showLocationWarning()
                return@setOnClickListener // Lo freno en seco.
            }

            // Si puso algo en usuario y contraseña (no pido contraseñas seguras, me da igual jaja)...
            if (user.isNotEmpty() && pass.isNotEmpty()) {
                prefs.saveUser(user, pass, currentEmoji)
                goToHome()
            } else {
                Toast.makeText(this, "Completa tus credenciales estelares", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isLocationEnabled(): Boolean {
        // Me fijo en el sistema si el GPS está prendido. Es re molesto pedirle esto al usuario, 
        // pero sin esto Google Nearby no te deja descubrir a otros teléfonos. Cosas de privacidad.
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun showLocationWarning() {
        // Cartelazo feo que te manda directo a los ajustes del celu para prender la ubicación.
        AlertDialog.Builder(this)
            .setTitle("Ubicación Desactivada")
            .setMessage("ADVERTENCIA: Debes encender la ubicación del dispositivo para poder usar Nexu y detectar otros nodos en la colmena.")
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setCancelable(false) // No te dejo escapar.
            .show()
    }

    private fun checkAndRequestPermissions() {
        // Armo una lista de todos los permisos falopa que me pide el Manifiesto.
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // A partir de Android 12 separaron el Bluetooth.
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION) // Este es obligatorio para todos.

        // Filtro los que me faltan pedir.
        val missing = list.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun goToHome() {
        // Saltito clásico al Home matando esta actividad para que no puedan volver atrás.
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        // Si el chabón volvió de los ajustes (después de prender el GPS) y ya estaba logueado, lo meto directo.
        if (prefs.isRegistered() && isLocationEnabled()) {
            goToHome()
        }
    }
}