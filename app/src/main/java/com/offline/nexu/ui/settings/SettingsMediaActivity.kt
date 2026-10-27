package com.offline.nexu.ui.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.ui.chat.MediaVaultActivity

class SettingsMediaActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var switchAutoDownload: Switch
    private lateinit var switchAutoClean: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_media)

        prefs = Prefs(this)
        val sharedPreferences = getSharedPreferences("NexuSettings", Context.MODE_PRIVATE)

        switchAutoDownload = findViewById(R.id.switchAutoDownload)
        switchAutoClean = findViewById(R.id.switchAutoClean)
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val btnOpenVault = findViewById<LinearLayout>(R.id.btnOpenVault)
        val btnOpenDocs = findViewById<LinearLayout>(R.id.btnOpenDocs)
        val btnSaveMediaSettings = findViewById<MaterialButton>(R.id.btnSaveMediaSettings)

        // Restaurado: El botón de documentos ahora sí está visible
        btnOpenDocs.visibility = View.VISIBLE

        switchAutoDownload.isChecked = sharedPreferences.getBoolean("auto_download_media", true)
        switchAutoClean.isChecked = sharedPreferences.getBoolean("auto_clean_media", false)

        btnBack.setOnClickListener { finish() }

        // Botón de Galería: Abre la bóveda en modo SOLO GALERÍA (Sin pestañas)
        btnOpenVault.setOnClickListener {
            val intent = Intent(this, MediaVaultActivity::class.java).apply {
                putExtra("EXTRA_GLOBAL", true)
                putExtra("EXTRA_MODE", "GALLERY_ONLY")
            }
            startActivity(intent)
        }

        // Botón de Documentos: Abre la bóveda en modo SOLO DOCS Y LINKS
        btnOpenDocs.setOnClickListener {
            val intent = Intent(this, MediaVaultActivity::class.java).apply {
                putExtra("EXTRA_GLOBAL", true)
                putExtra("EXTRA_MODE", "DOCS_ONLY")
            }
            startActivity(intent)
        }

        btnSaveMediaSettings.setOnClickListener {
            sharedPreferences.edit()
                .putBoolean("auto_download_media", switchAutoDownload.isChecked)
                .putBoolean("auto_clean_media", switchAutoClean.isChecked)
                .apply()

            Toast.makeText(this, "Ajustes de archivos guardados", Toast.LENGTH_SHORT).show()
            finish()
        }

        applyAuraTheme(btnSaveMediaSettings)
    }

    private fun applyAuraTheme(saveButton: MaterialButton) {
        try {
            val profile = prefs.getUserProfile()
            val colorInt = Color.parseColor(profile.colorHex)
            val colorStateList = ColorStateList.valueOf(colorInt)

            findViewById<TextView>(R.id.tvMediaTitle)?.setTextColor(colorInt)
            findViewById<ImageView>(R.id.btnBack)?.setColorFilter(colorInt)
            findViewById<ImageView>(R.id.iconGallery)?.setColorFilter(colorInt)
            findViewById<ImageView>(R.id.iconDocs)?.setColorFilter(colorInt)

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

            switchAutoDownload.thumbTintList = thumbStateList
            switchAutoDownload.trackTintList = trackStateList

            switchAutoClean.thumbTintList = thumbStateList
            switchAutoClean.trackTintList = trackStateList

            saveButton.backgroundTintList = colorStateList

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}