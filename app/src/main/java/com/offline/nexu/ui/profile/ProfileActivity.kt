package com.offline.nexu.ui.profile

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.nearby.Nearby
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.model.UserProfile
import com.offline.nexu.ui.home.HomeActivity
import com.offline.nexu.ui.home.P2PManager
import com.offline.nexu.ui.login.LoginActivity
import com.offline.nexu.utils.ThemeUtils
import java.io.File
import java.io.FileOutputStream
import java.util.*

class ProfileActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var avatarImageView: ImageView
    private var selectedAvatarUri: Uri? = null
    private var currentColorHex: String = "#00BCD4"

    private lateinit var tvProfileTitle: TextView
    private lateinit var viewPurple: View
    private lateinit var viewBlue: View
    private lateinit var viewCyan: View
    private lateinit var viewGreen: View
    private lateinit var viewOrange: View
    private lateinit var viewRed: View

    private lateinit var btnSave: Button
    private lateinit var bottomNavigation: BottomNavigationView

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedAvatarUri = uri
            avatarImageView.setImageURI(uri)
            avatarImageView.clipToOutline = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        prefs = Prefs(this)
        val currentProfile = prefs.getUserProfile()
        currentColorHex = currentProfile.colorHex

        tvProfileTitle = findViewById(R.id.tvProfileTitle)
        avatarImageView = findViewById(R.id.etAvatar)
        val etName = findViewById<EditText>(R.id.etName)
        val etBio = findViewById<EditText>(R.id.etBio)
        val etRole = findViewById<EditText>(R.id.etRole)
        btnSave = findViewById(R.id.btnSaveProfile)
        bottomNavigation = findViewById(R.id.bottom_navigation)

        viewPurple = findViewById(R.id.colorPurple)
        viewBlue = findViewById(R.id.colorBlue)
        viewCyan = findViewById(R.id.colorCyan)
        viewGreen = findViewById(R.id.colorGreen)
        viewOrange = findViewById(R.id.colorOrange)
        viewRed = findViewById(R.id.colorRed)

        etName.setText(currentProfile.name)
        etBio.setText(currentProfile.customAttributes["bio"] ?: "")
        etRole.setText(currentProfile.customAttributes["rol"] ?: "")

        seleccionarColorPorHex(currentColorHex)

        viewPurple.setOnClickListener { actualizarColorVisual("#FF6200EE", viewPurple) }
        viewBlue.setOnClickListener { actualizarColorVisual("#2196F3", viewBlue) }
        viewCyan.setOnClickListener { actualizarColorVisual("#00BCD4", viewCyan) }
        viewGreen.setOnClickListener { actualizarColorVisual("#4CAF50", viewGreen) }
        viewOrange.setOnClickListener { actualizarColorVisual("#FF9800", viewOrange) }
        viewRed.setOnClickListener { actualizarColorVisual("#F44336", viewRed) }

        if (currentProfile.avatar.isNotEmpty() && currentProfile.avatar.startsWith("/")) {
            val avatarFile = File(currentProfile.avatar)
            if (avatarFile.exists()) {
                avatarImageView.setImageURI(Uri.fromFile(avatarFile))
                avatarImageView.clipToOutline = true
            }
        }

        avatarImageView.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        btnSave.setOnClickListener {
            var finalAvatarPath = currentProfile.avatar
            selectedAvatarUri?.let { uri ->
                val savedPath = saveAvatarLocally(uri)
                if (savedPath != null) {
                    finalAvatarPath = savedPath
                } else {
                    Toast.makeText(this, "Error al guardar el avatar", Toast.LENGTH_SHORT).show()
                }
            }

            val nuevosAtributos = mapOf(
                "bio" to etBio.text.toString().trim(),
                "rol" to etRole.text.toString().trim()
            )

            val newProfile = UserProfile(
                id = currentProfile.id,
                name = etName.text.toString().trim(),
                avatar = finalAvatarPath,
                colorHex = currentColorHex,
                customAttributes = nuevosAtributos
            )

            prefs.saveUserProfile(newProfile)
            Toast.makeText(this, "Identidad y aura actualizadas", Toast.LENGTH_SHORT).show()
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

        findViewById<Button>(R.id.btnChangePassword)?.setOnClickListener {
            val input = EditText(this)
            input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            input.hint = "Nueva Contraseña"
            input.setPadding(40,40,40,40)

            AlertDialog.Builder(this)
                .setTitle("Cambiar Contraseña")
                .setView(input)
                .setPositiveButton("Guardar") { _, _ ->
                    val newPass = input.text.toString()
                    if (newPass.isNotEmpty()) {
                        prefs.updatePassword(newPass)
                        Toast.makeText(this, "Contraseña actualizada", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_radar -> {
                    val intent = Intent(this, HomeActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_chats -> {
                    val intent = Intent(this, com.offline.nexu.ui.chat.ChatsListActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_profile -> true
                else -> false
            }
        }
    }

    // ¡NUEVO!: Garantiza que al regresar a esta pantalla, recupere el Aura seleccionada y fije el icono correcto
    override fun onResume() {
        super.onResume()
        bottomNavigation.selectedItemId = R.id.nav_profile
        val currentProfile = prefs.getUserProfile()
        seleccionarColorPorHex(currentProfile.colorHex)
    }

    // ¡NUEVO!: Mata cualquier animación del sistema al cambiar de pestaña
    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
    }

    private fun actualizarColorVisual(hexCode: String, selectedView: View) {
        currentColorHex = hexCode
        val todasLasVistas = listOf(viewPurple, viewBlue, viewCyan, viewGreen, viewOrange, viewRed)
        for (vista in todasLasVistas) {
            vista.alpha = if (vista == selectedView) 1.0f else 0.3f
        }

        try {
            val colorInt = Color.parseColor(hexCode)
            tvProfileTitle.setTextColor(colorInt)
            avatarImageView.backgroundTintList = ColorStateList.valueOf(colorInt)
            btnSave.backgroundTintList = ColorStateList.valueOf(colorInt)

            val navColors = ThemeUtils.getBottomNavColorStateList(colorInt)
            bottomNavigation.itemIconTintList = navColors
            bottomNavigation.itemTextColor = navColors

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun seleccionarColorPorHex(hexCode: String) {
        when (hexCode) {
            "#2196F3" -> actualizarColorVisual(hexCode, viewBlue)
            "#00BCD4" -> actualizarColorVisual(hexCode, viewCyan)
            "#4CAF50" -> actualizarColorVisual(hexCode, viewGreen)
            "#FF9800" -> actualizarColorVisual(hexCode, viewOrange)
            "#F44336" -> actualizarColorVisual(hexCode, viewRed)
            "#FF6200EE" -> actualizarColorVisual(hexCode, viewPurple)
            else -> actualizarColorVisual("#00BCD4", viewCyan)
        }
    }

    private fun saveAvatarLocally(uri: Uri): String? {
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val uniqueFileName = "avatar_${UUID.randomUUID()}.jpg"

        val file = File(filesDir, uniqueFileName)
        val outputStream = FileOutputStream(file)

        return try {
            inputStream.copyTo(outputStream)
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            inputStream.close()
            outputStream.close()
        }
    }
}