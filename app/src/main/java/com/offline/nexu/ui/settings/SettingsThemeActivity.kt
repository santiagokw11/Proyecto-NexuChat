package com.offline.nexu.ui.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs

class SettingsThemeActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var switchDarkMode: Switch
    private lateinit var iconTheme: ImageView
    private lateinit var tvThemeDesc: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_theme)

        prefs = Prefs(this)
        val sharedPreferences = getSharedPreferences("NexuSettings", Context.MODE_PRIVATE)

        switchDarkMode = findViewById(R.id.switchDarkMode)
        iconTheme = findViewById(R.id.iconTheme)
        tvThemeDesc = findViewById(R.id.tvThemeDesc)
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val btnSaveTheme = findViewById<MaterialButton>(R.id.btnSaveTheme)

        // Cargar estado inicial
        val isDark = sharedPreferences.getBoolean("dark_mode", false)
        switchDarkMode.isChecked = isDark
        actualizarIconoTextos(isDark)

        // Evento del Switch en tiempo real
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            // 1. Guardar INMEDIATAMENTE para que al recargar la actividad no se pierda el estado
            sharedPreferences.edit().putBoolean("dark_mode", isChecked).apply()

            // 2. Actualizar visuales
            actualizarIconoTextos(isChecked)

            // 3. Aplicar el tema inmediatamente (Esto recarga la pantalla por debajo)
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        // Botón volver
        btnBack.setOnClickListener { finish() }

        // Guardar permanentemente (Aunque ya se guarda automático, dejamos el botón para cerrar)
        btnSaveTheme.setOnClickListener {
            Toast.makeText(this, "Tema visual guardado", Toast.LENGTH_SHORT).show()
            finish()
        }

        applyAuraTheme()
    }

    private fun actualizarIconoTextos(isDark: Boolean) {
        if (isDark) {
            iconTheme.setImageResource(R.drawable.ic_moon_dark)
            tvThemeDesc.text = "El modo oscuro está activado."
        } else {
            iconTheme.setImageResource(R.drawable.ic_sun_ligth)
            tvThemeDesc.text = "El modo claro está activado."
        }

        // Volver a aplicar el color del aura al cambiar el Drawable
        try {
            val colorInt = Color.parseColor(prefs.getUserProfile().colorHex)
            iconTheme.setColorFilter(colorInt)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyAuraTheme() {
        try {
            val profile = prefs.getUserProfile()
            val colorInt = Color.parseColor(profile.colorHex)

            findViewById<TextView>(R.id.tvThemeTitle)?.setTextColor(colorInt)
            findViewById<ImageView>(R.id.btnBack)?.setColorFilter(colorInt)
            iconTheme.setColorFilter(colorInt)

            val switchStates = arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            )
            val thumbColors = intArrayOf(colorInt, Color.LTGRAY)
            val trackColors = intArrayOf(
                ColorUtils.setAlphaComponent(colorInt, 76),
                Color.parseColor("#E0E0E0")
            )

            switchDarkMode.thumbTintList = ColorStateList(switchStates, thumbColors)
            switchDarkMode.trackTintList = ColorStateList(switchStates, trackColors)

            findViewById<MaterialButton>(R.id.btnSaveTheme)?.backgroundTintList = ColorStateList.valueOf(colorInt)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}