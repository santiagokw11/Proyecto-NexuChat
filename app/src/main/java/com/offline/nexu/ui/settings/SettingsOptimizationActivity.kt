package com.offline.nexu.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs

class SettingsOptimizationActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var switchPowerSave: Switch
    private lateinit var switchStealthMode: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_optimization)

        // Inicializar preferencias
        prefs = Prefs(this)

        // Vincular vistas
        switchPowerSave = findViewById(R.id.switchPowerSave)
        switchStealthMode = findViewById(R.id.switchStealthMode)
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val btnSaveOptimization = findViewById<MaterialButton>(R.id.btnSaveOptimization)

        /* * AQUÍ DEBES CARGAR TUS VALORES GUARDADOS
         * switchPowerSave.isChecked = prefs.isPowerSaveEnabled()
         * switchStealthMode.isChecked = prefs.isStealthModeEnabled()
         */

        // Botón de retroceso
        btnBack.setOnClickListener {
            finish()
        }

        // Botón de guardar
        btnSaveOptimization.setOnClickListener {
            /* * AQUÍ DEBES GUARDAR LOS VALORES
             * prefs.setPowerSaveEnabled(switchPowerSave.isChecked)
             * prefs.setStealthModeEnabled(switchStealthMode.isChecked)
             */
            Toast.makeText(this, "Ajustes de optimización guardados", Toast.LENGTH_SHORT).show()
            finish()
        }

        // Aplicar el tema dinámico
        applyAuraTheme()
    }

    private fun applyAuraTheme() {
        try {
            val profile = prefs.getUserProfile()
            val colorInt = Color.parseColor(profile.colorHex)
            val colorStateList = ColorStateList.valueOf(colorInt)

            // Colorear Textos e Íconos (Mantiene la estética)
            findViewById<TextView>(R.id.tvOptimizationTitle)?.setTextColor(colorInt)
            findViewById<ImageView>(R.id.btnBack)?.setColorFilter(colorInt)
            findViewById<ImageView>(R.id.iconBattery)?.setColorFilter(colorInt)
            findViewById<ImageView>(R.id.iconRadar)?.setColorFilter(colorInt)

            // Los Switches ya no se tiñen para evitar saturación visual

            // Colorear Botón Principal de Guardar
            findViewById<MaterialButton>(R.id.btnSaveOptimization)?.backgroundTintList = colorStateList

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}