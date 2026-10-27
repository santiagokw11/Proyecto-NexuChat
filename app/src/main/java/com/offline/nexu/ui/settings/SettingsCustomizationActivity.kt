package com.offline.nexu.ui.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs

class SettingsCustomizationActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var selectedColorHex: String = "#2196F3"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings_customization)

        prefs = Prefs(this)

        // Evitar que los círculos se recorten al hacerse grandes
        disableClippingForColors()

        // 1. Cargar el perfil y el color
        val profile = prefs.getUserProfile()
        selectedColorHex = profile.colorHex

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // 2. Cargar Radar (usando un índice seguro 0, 1, 2, 3) y Proximidad guardados
        val sharedPreferences = getSharedPreferences("NexuSettings", Context.MODE_PRIVATE)

        val savedRadarIndex = sharedPreferences.getInt("radar_style_index", 0)
        val radioGroup = findViewById<RadioGroup>(R.id.rgRadarStyle)

        // Seleccionar el botón correcto según el índice guardado
        val rbIdToSelect = when (savedRadarIndex) {
            1 -> R.id.rbStyle1
            2 -> R.id.rbStyle2
            3 -> R.id.rbStyle3
            else -> R.id.rbStyle0
        }
        radioGroup.clearCheck()
        radioGroup.check(rbIdToSelect)

        // Cargar Proximidad
        val savedProximity = sharedPreferences.getBoolean("proximity_alert", false)
        findViewById<Switch>(R.id.switchProximity).isChecked = savedProximity

        // 3. Mapear colores y aplicar el tema inicial
        setupColorClickListeners()
        applyAuraTheme(Color.parseColor(selectedColorHex))
        updateColorSelectionUI(selectedColorHex)

        // 4. Guardar los cambios
        findViewById<MaterialButton>(R.id.btnSaveCustomization).setOnClickListener {

            // Guardar color
            profile.colorHex = selectedColorHex
            prefs.saveUserProfile(profile)

            // Convertir el botón seleccionado a un índice seguro para guardar
            val checkedRadarId = radioGroup.checkedRadioButtonId
            val indexToSave = when (checkedRadarId) {
                R.id.rbStyle1 -> 1
                R.id.rbStyle2 -> 2
                R.id.rbStyle3 -> 3
                else -> 0
            }

            val isProximityChecked = findViewById<Switch>(R.id.switchProximity).isChecked

            sharedPreferences.edit()
                .putInt("radar_style_index", indexToSave)
                .putBoolean("proximity_alert", isProximityChecked)
                .apply()

            Toast.makeText(this, "Ajustes de personalización guardados", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * Truco de UI: Le dice a las filas contenedoras de los colores que NO recorten
     * a sus hijos cuando estos aumenten su tamaño.
     */
    private fun disableClippingForColors() {
        val rootLayout = findViewById<View>(android.R.id.content).parent as? ViewGroup
        rootLayout?.clipChildren = false
        rootLayout?.clipToPadding = false

        // Fila 1 y Fila 2
        val row1 = findViewById<View>(R.id.colorPurple)?.parent?.parent as? ViewGroup
        val row2 = findViewById<View>(R.id.colorGreen)?.parent?.parent as? ViewGroup

        row1?.clipChildren = false
        row1?.clipToPadding = false
        row2?.clipChildren = false
        row2?.clipToPadding = false

        val parentContainer = row1?.parent as? ViewGroup
        parentContainer?.clipChildren = false
        parentContainer?.clipToPadding = false
    }

    private fun setupColorClickListeners() {
        val colorMap = mapOf(
            R.id.colorPurple to "#FF6200EE",
            R.id.colorIndigo to "#3F51B5",
            R.id.colorBlue to "#2196F3",
            R.id.colorCyan to "#00BCD4",
            R.id.colorTeal to "#009688",
            R.id.colorGreen to "#4CAF50",
            R.id.colorAmber to "#FFC107",
            R.id.colorOrange to "#FF9800",
            R.id.colorRed to "#F44336",
            R.id.colorPink to "#E91E63"
        )

        for ((viewId, hexColor) in colorMap) {
            findViewById<View>(viewId)?.setOnClickListener {
                selectedColorHex = hexColor
                applyAuraTheme(Color.parseColor(hexColor))
                updateColorSelectionUI(hexColor)
            }
        }
    }

    private fun updateColorSelectionUI(selectedHex: String) {
        val colorMap = mapOf(
            R.id.colorPurple to "#FF6200EE",
            R.id.colorIndigo to "#3F51B5",
            R.id.colorBlue to "#2196F3",
            R.id.colorCyan to "#00BCD4",
            R.id.colorTeal to "#009688",
            R.id.colorGreen to "#4CAF50",
            R.id.colorAmber to "#FFC107",
            R.id.colorOrange to "#FF9800",
            R.id.colorRed to "#F44336",
            R.id.colorPink to "#E91E63"
        )

        for ((viewId, hexColor) in colorMap) {
            val view = findViewById<View>(viewId)

            if (hexColor.equals(selectedHex, ignoreCase = true)) {
                // Seleccionado: Más grande, escala ajustada a 1.25 y sin opacidad
                view?.alpha = 1.0f
                view?.scaleX = 1.25f
                view?.scaleY = 1.25f
                view?.elevation = 8f
            } else {
                // No seleccionado: Pequeño y opaco
                view?.alpha = 0.3f
                view?.scaleX = 1.0f
                view?.scaleY = 1.0f
                view?.elevation = 0f
            }
        }
    }

    private fun applyAuraTheme(colorInt: Int) {
        try {
            // Textos e íconos superiores
            findViewById<TextView>(R.id.tvCustomizationTitle)?.setTextColor(colorInt)
            findViewById<ImageView>(R.id.btnBack)?.setColorFilter(colorInt)

            // 1. ESTADO PARA LOS RADIO BUTTONS (Aura si están marcados, Gris si no)
            val rbStates = arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            )
            val rbColors = intArrayOf(colorInt, Color.GRAY)
            val rbColorStateList = ColorStateList(rbStates, rbColors)

            findViewById<RadioButton>(R.id.rbStyle0)?.buttonTintList = rbColorStateList
            findViewById<RadioButton>(R.id.rbStyle1)?.buttonTintList = rbColorStateList
            findViewById<RadioButton>(R.id.rbStyle2)?.buttonTintList = rbColorStateList
            findViewById<RadioButton>(R.id.rbStyle3)?.buttonTintList = rbColorStateList

            // 2. ESTADO PARA EL SWITCH DE PROXIMIDAD (Aura encendido, Gris apagado)
            val switchStates = arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            )
            // Bolita del switch (Thumb)
            val thumbColors = intArrayOf(colorInt, Color.LTGRAY)
            // Fondo del switch (Track - translúcido si está encendido)
            val trackColors = intArrayOf(
                ColorUtils.setAlphaComponent(colorInt, 76),
                Color.parseColor("#E0E0E0")
            )

            val switchProximity = findViewById<Switch>(R.id.switchProximity)
            switchProximity?.thumbTintList = ColorStateList(switchStates, thumbColors)
            switchProximity?.trackTintList = ColorStateList(switchStates, trackColors)

            // 3. Botón de Guardar
            val buttonColorStateList = ColorStateList.valueOf(colorInt)
            findViewById<MaterialButton>(R.id.btnSaveCustomization)?.backgroundTintList = buttonColorStateList

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}