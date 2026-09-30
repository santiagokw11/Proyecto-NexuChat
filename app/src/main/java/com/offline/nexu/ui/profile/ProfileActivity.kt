package com.offline.nexu.ui.profile

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.model.UserProfile
import com.offline.nexu.ui.home.HomeActivity
import java.io.File
import java.io.FileOutputStream
import java.util.*

class ProfileActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var avatarImageView: ImageView
    private var selectedAvatarUri: Uri? = null
    private var currentColorHex: String = "#FF6200EE"

    private lateinit var viewPurple: View
    private lateinit var viewBlue: View
    private lateinit var viewGreen: View
    private lateinit var viewOrange: View
    private lateinit var viewRed: View

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

        avatarImageView = findViewById(R.id.etAvatar)
        val etName = findViewById<EditText>(R.id.etName)
        val etBio = findViewById<EditText>(R.id.etBio)
        val etRole = findViewById<EditText>(R.id.etRole)
        val btnSave = findViewById<Button>(R.id.btnSaveProfile)
        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottom_navigation)

        viewPurple = findViewById(R.id.colorPurple)
        viewBlue = findViewById(R.id.colorBlue)
        viewGreen = findViewById(R.id.colorGreen)
        viewOrange = findViewById(R.id.colorOrange)
        viewRed = findViewById(R.id.colorRed)

        etName.setText(currentProfile.name)
        etBio.setText(currentProfile.customAttributes["bio"] ?: "")
        etRole.setText(currentProfile.customAttributes["rol"] ?: "")

        seleccionarColorPorHex(currentColorHex)

        viewPurple.setOnClickListener { actualizarColorVisual("#FF6200EE", viewPurple) }
        viewBlue.setOnClickListener { actualizarColorVisual("#2196F3", viewBlue) }
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
            Toast.makeText(this, "Identidad y aura actualizadas localmente", Toast.LENGTH_SHORT).show()
        }

        bottomNavigation.selectedItemId = R.id.nav_profile
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_radar -> {
                    val intent = Intent(this, HomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_chats -> {
                    val intent = Intent(this, com.offline.nexu.ui.chat.ChatsListActivity::class.java)
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    finish()
                    true
                }
                R.id.nav_profile -> true
                else -> false
            }
        }
    }

    private fun actualizarColorVisual(hexCode: String, selectedView: View) {
        currentColorHex = hexCode
        val todasLasVistas = listOf(viewPurple, viewBlue, viewGreen, viewOrange, viewRed)
        for (vista in todasLasVistas) {
            vista.alpha = if (vista == selectedView) 1.0f else 0.3f
        }

        try {
            val colorInt = Color.parseColor(hexCode)
            avatarImageView.backgroundTintList = ColorStateList.valueOf(colorInt)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun seleccionarColorPorHex(hexCode: String) {
        when (hexCode) {
            "#2196F3" -> actualizarColorVisual(hexCode, viewBlue)
            "#4CAF50" -> actualizarColorVisual(hexCode, viewGreen)
            "#FF9800" -> actualizarColorVisual(hexCode, viewOrange)
            "#F44336" -> actualizarColorVisual(hexCode, viewRed)
            else -> actualizarColorVisual("#FF6200EE", viewPurple)
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