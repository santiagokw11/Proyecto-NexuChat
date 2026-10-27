package com.offline.nexu.ui.settings

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.nearby.Nearby
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.ui.chat.ChatsListActivity
import com.offline.nexu.ui.home.HomeActivity
import com.offline.nexu.ui.home.P2PManager
import com.offline.nexu.ui.login.LoginActivity
import com.offline.nexu.ui.profile.ProfileActivity
import com.offline.nexu.utils.ThemeUtils

class SettingsActivity : AppCompatActivity() {

    private lateinit var bottomNavigation: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        setContentView(R.layout.activity_settings)

        val prefs = Prefs(this)
        bottomNavigation = findViewById(R.id.bottom_navigation)

        // Aplicamos los colores del aura a todos los elementos visuales iniciales
        applyAuraTheme()

        findViewById<LinearLayout>(R.id.btnMenuPrivacy).setOnClickListener {
            startActivity(Intent(this, SettingsPrivacyActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.btnMenuCustomization).setOnClickListener {
            startActivity(Intent(this, SettingsCustomizationActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.btnMenuTheme).setOnClickListener {
            startActivity(Intent(this, SettingsThemeActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.btnMenuOptimization).setOnClickListener {
            startActivity(Intent(this, SettingsOptimizationActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.btnMenuMedia).setOnClickListener {
            startActivity(Intent(this, SettingsMediaActivity::class.java))
        }

        // AHORA USAMOS TU LAYOUT PERSONALIZADO: dialog_change_password.xml
        findViewById<Button>(R.id.btnChangePassword)?.setOnClickListener {
            val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_change_password, null)
            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .create()

            // Fondo transparente para que se vean los bordes curvos del CardView
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            val etNewPassword = dialogView.findViewById<EditText>(R.id.etNewPassword)
            val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelPassword)
            val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmPassword)

            // Pintar el botón de Confirmar con el Aura del usuario
            try {
                val colorInt = Color.parseColor(prefs.getUserProfile().colorHex)
                btnConfirm.backgroundTintList = ColorStateList.valueOf(colorInt)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            btnConfirm.setOnClickListener {
                val newPass = etNewPassword.text.toString()
                if (newPass.isNotEmpty()) {
                    prefs.updatePassword(newPass)
                    Toast.makeText(this, "Contraseña actualizada con éxito", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "La contraseña no puede estar vacía", Toast.LENGTH_SHORT).show()
                }
            }

            dialog.show()
        }

        findViewById<Button>(R.id.btnLogout)?.setOnClickListener {
            Nearby.getConnectionsClient(applicationContext).stopAdvertising()
            Nearby.getConnectionsClient(applicationContext).stopDiscovery()
            Nearby.getConnectionsClient(applicationContext).stopAllEndpoints()

            P2PManager.currentEndpointId = null
            P2PManager.currentEndpointName = null

            prefs.logout()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_radar -> navigateTo(HomeActivity::class.java)
                R.id.nav_chats -> navigateTo(ChatsListActivity::class.java)
                R.id.nav_profile -> navigateTo(ProfileActivity::class.java)
                R.id.nav_settings -> true
                else -> false
            }
        }
    }

    private fun navigateTo(clazz: Class<*>): Boolean {
        val intent = Intent(this, clazz)
        intent.flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION
        startActivity(intent)
        overridePendingTransition(0, 0)
        return true
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
        bottomNavigation.selectedItemId = R.id.nav_settings

        // Actualizar el icono de tema (Sol/Luna) dinámicamente al regresar
        actualizarIconoTema()

        // Recargar color del Aura
        applyAuraTheme()
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
    }

    private fun actualizarIconoTema() {
        val isDarkMode = getSharedPreferences("NexuSettings", Context.MODE_PRIVATE).getBoolean("dark_mode", false)
        val iconTheme = findViewById<ImageView>(R.id.iconTheme)
        val tvThemeDesc = findViewById<TextView>(R.id.tvThemeDesc)

        if (isDarkMode) {
            iconTheme?.setImageResource(R.drawable.ic_moon_dark)
            tvThemeDesc?.text = "Modo oscuro activado"
        } else {
            iconTheme?.setImageResource(R.drawable.ic_sun_ligth)
            tvThemeDesc?.text = "Modo claro activado"
        }
    }

    private fun applyAuraTheme() {
        val profile = Prefs(this).getUserProfile()
        try {
            val colorInt = Color.parseColor(profile.colorHex)

            findViewById<TextView>(R.id.tvSettingsTitle).setTextColor(colorInt)
            findViewById<Button>(R.id.btnChangePassword)?.setTextColor(colorInt)

            val navColors = ThemeUtils.getBottomNavColorStateList(colorInt)
            bottomNavigation.itemIconTintList = navColors
            bottomNavigation.itemTextColor = navColors

            val menus = listOf(
                R.id.btnMenuPrivacy,
                R.id.btnMenuCustomization,
                R.id.btnMenuTheme,
                R.id.btnMenuOptimization,
                R.id.btnMenuMedia
            )

            for (menuId in menus) {
                val menuLayout = findViewById<LinearLayout>(menuId)
                if (menuLayout != null && menuLayout.childCount > 0) {
                    (menuLayout.getChildAt(0) as? ImageView)?.setColorFilter(colorInt)
                }
            }

            findViewById<ImageView>(R.id.iconTheme)?.setColorFilter(colorInt)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}