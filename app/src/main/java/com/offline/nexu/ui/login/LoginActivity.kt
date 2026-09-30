package com.offline.nexu.ui.login

import android.Manifest
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
import com.offline.nexu.data.model.UserProfile
import com.offline.nexu.databinding.ActivityLoginBinding
import com.offline.nexu.ui.home.HomeActivity

// El activity de Login. Acá pedimos el nombre y el emoji para no ser un "Desconocido" más en la red.
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: Prefs
    private var currentEmoji = "👤"

    private val avatarOptions = listOf("👤", "🐱", "🐶", "🦊", "🤖", "👾", "😎", "🚀", "🌈", "🔥")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)

        if (prefs.isRegistered()) {
            // Checamos si ya se registró. Si sí, vámonos directo al Home. 
            // Y si no hay GPS prendido, le echamos la bronca al usuario porque Nearby lo necesita sí o sí.
            if (isLocationEnabled()) {
                goToHome()
            } else {
                showLocationWarning()
            }
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()

        binding.btnChangeEmoji.setOnClickListener {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("Selecciona tu estilo")
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, avatarOptions)
            builder.setAdapter(adapter) { _, which ->
                currentEmoji = avatarOptions[which]
                binding.tvSelectedEmoji.text = currentEmoji
            }
            builder.show()
        }

        binding.btnRegister.setOnClickListener {
            val user = binding.etUsername.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (!isLocationEnabled()) {
                showLocationWarning()
                return@setOnClickListener
            }

            if (user.isNotEmpty() && pass.isNotEmpty()) {
                val newProfile = UserProfile(
                    name = user,
                    avatar = currentEmoji
                )
                prefs.saveUserProfile(newProfile)

                getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("password", pass)
                    .apply()

                goToHome()
            } else {
                Toast.makeText(this, "Completa tus credenciales estelares", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun showLocationWarning() {
        // Android no te deja usar Nearby Connections sin ubicación, por más que le llores. 
        // Así que aquí la exigimos con un diálogo.
        AlertDialog.Builder(this)
            .setTitle("Ubicación Desactivada")
            .setMessage("ADVERTENCIA: Debes encender la ubicación del dispositivo para poder usar Nexu y detectar otros nodos en la colmena.")
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setCancelable(false)
            .show()
    }

    private fun checkAndRequestPermissions() {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val missing = list.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun goToHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.isRegistered() && isLocationEnabled()) {
            goToHome()
        }
    }
}