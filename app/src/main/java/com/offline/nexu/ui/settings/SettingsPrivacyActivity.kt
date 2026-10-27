package com.offline.nexu.ui.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.network.NexuMeshManager

class SettingsPrivacyActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var switchBiometric: Switch
    private lateinit var switchIncognitoKeyboard: Switch
    private lateinit var switchRadarVisibility: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_privacy)

        prefs = Prefs(this)
        val sharedPreferences = getSharedPreferences("NexuSettings", Context.MODE_PRIVATE)

        // 1. Vincular Vistas
        switchBiometric = findViewById(R.id.switchBiometric)
        switchIncognitoKeyboard = findViewById(R.id.switchIncognitoKeyboard)
        switchRadarVisibility = findViewById(R.id.switchRadarVisibility)

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val btnBlockedNodes = findViewById<LinearLayout>(R.id.btnBlockedNodes)
        val btnDeleteChatHistory = findViewById<MaterialButton>(R.id.btnDeleteChatHistory)
        val btnSavePrivacy = findViewById<MaterialButton>(R.id.btnSavePrivacy)

        // 2. Cargar estados guardados
        switchBiometric.isChecked = sharedPreferences.getBoolean("db_encryption", false)
        switchIncognitoKeyboard.isChecked = sharedPreferences.getBoolean("incognito_keyboard", true)
        switchRadarVisibility.isChecked = sharedPreferences.getBoolean("radar_hidden", false)

        // Eventos visuales y SINCRONIZACIÓN INMEDIATA
        switchBiometric.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                Toast.makeText(this, "Biometría requerida al iniciar.", Toast.LENGTH_SHORT).show()
            }
        }

        switchRadarVisibility.setOnCheckedChangeListener { _, isChecked ->
            val isVisible = !isChecked

            // Avisarle a la red Mesh
            NexuMeshManager(this, prefs.getUserProfile().name).setVisibilidadRadar(isVisible)

            // ESTA ES LA LÍNEA CLAVE PARA QUE SE SINCRONICE CON HOMEACTIVITY AL INSTANTE:
            sharedPreferences.edit().putBoolean("radar_hidden", isChecked).apply()

            if (isChecked) {
                Toast.makeText(this, "Modo fantasma activado. Eres invisible en el radar.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Ahora eres visible en el radar.", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Botón Volver
        btnBack.setOnClickListener { finish() }

        // 4. Abrir Gestión de Nodos Bloqueados
        btnBlockedNodes.setOnClickListener {
            startActivity(Intent(this, BlockedNodesActivity::class.java))
        }

        // 5. Botón de Acción Destructiva (Borrar Historial)
        btnDeleteChatHistory.setOnClickListener {
            mostrarDialogoBorrarHistorial()
        }

        // 6. Botón Guardar Cambios (Ya no necesitamos guardar radar_hidden aquí porque lo hicimos arriba)
        btnSavePrivacy.setOnClickListener {
            sharedPreferences.edit()
                .putBoolean("db_encryption", switchBiometric.isChecked)
                .putBoolean("incognito_keyboard", switchIncognitoKeyboard.isChecked)
                .apply()

            Toast.makeText(this, "Ajustes de privacidad guardados", Toast.LENGTH_SHORT).show()
            finish()
        }

        // 7. Aplicar el Aura del usuario
        applyAuraTheme()
    }

    private fun mostrarDialogoBorrarHistorial() {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar historial local?")
            .setMessage("Esta acción eliminará todos los mensajes y archivos guardados en tu dispositivo. No afectará a los dispositivos de tus contactos. Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar Todo") { _, _ ->
                Toast.makeText(this, "Historial local eliminado", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun applyAuraTheme() {
        try {
            val profile = prefs.getUserProfile()
            val colorInt = Color.parseColor(profile.colorHex)

            findViewById<TextView>(R.id.tvPrivacyTitle)?.setTextColor(colorInt)
            findViewById<ImageView>(R.id.btnBack)?.setColorFilter(colorInt)

            findViewById<ImageView>(R.id.iconFingerprint)?.setColorFilter(colorInt)
            findViewById<ImageView>(R.id.iconKeyboard)?.setColorFilter(colorInt)
            findViewById<ImageView>(R.id.iconBlocked)?.setColorFilter(colorInt)
            findViewById<ImageView>(R.id.iconVisibility)?.setColorFilter(colorInt)

            val switchStates = arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            )
            val thumbColors = intArrayOf(colorInt, Color.LTGRAY)
            val trackColors = intArrayOf(
                ColorUtils.setAlphaComponent(colorInt, 76),
                Color.parseColor("#E0E0E0")
            )

            val thumbStateList = ColorStateList(switchStates, thumbColors)
            val trackStateList = ColorStateList(switchStates, trackColors)

            switchBiometric.thumbTintList = thumbStateList
            switchBiometric.trackTintList = trackStateList
            switchIncognitoKeyboard.thumbTintList = thumbStateList
            switchIncognitoKeyboard.trackTintList = trackStateList
            switchRadarVisibility.thumbTintList = thumbStateList
            switchRadarVisibility.trackTintList = trackStateList

            findViewById<MaterialButton>(R.id.btnSavePrivacy)?.backgroundTintList =
                ColorStateList.valueOf(colorInt)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}