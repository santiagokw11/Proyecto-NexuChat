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
import com.offline.nexu.ui.chat.ChatsListActivity
import com.offline.nexu.ui.home.HomeActivity
import java.io.File
import java.io.FileOutputStream
import java.util.*

class ProfileActivity : AppCompatActivity() {

    // Me guardo mis SharedPreferences acá para sacar los datos guardados del usuario.
    private lateinit var prefs: Prefs
    
    // Todo esto es para la foto y el color de perfil.
    private lateinit var avatarImageView: ImageView
    private var selectedAvatarUri: Uri? = null
    // Pongo este morado por defecto por si algo falla.
    private var currentColorHex: String = "#FF6200EE"

    // Las vistas de los circulitos de colores para elegir el "aura"
    private lateinit var viewPurple: View
    private lateinit var viewBlue: View
    private lateinit var viewGreen: View
    private lateinit var viewOrange: View
    private lateinit var viewRed: View

    // Esto es magia pura. Es el nuevo selector de fotos de Android. 
    // Cuando el usuario elige una foto de la galería, me la devuelve acá y la pongo en el ImageView.
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            selectedAvatarUri = uri
            avatarImageView.setImageURI(uri)
            avatarImageView.clipToOutline = true // Para que respete el borde redondo.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        // Traigo los datos que ya están guardados en el celu.
        prefs = Prefs(this)
        val currentProfile = prefs.getUserProfile()
        currentColorHex = currentProfile.colorHex

        // Hago todos los findViewById de la vieja escuela. 
        // Sí, ya sé que podría usar ViewBinding como en las otras pantallas, pero bueno, quedó así jaja.
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

        // Le clavo los datos actuales a los EditText para que no arranquen vacíos.
        etName.setText(currentProfile.name)
        etBio.setText(currentProfile.customAttributes["bio"] ?: "")
        etRole.setText(currentProfile.customAttributes["rol"] ?: "")

        // Marco el circulito del color que tiene guardado.
        seleccionarColorPorHex(currentColorHex)

        // Qué quilombo tantos clicks, pero básicamente le cambio el color cuando tocan un circulito.
        viewPurple.setOnClickListener { actualizarColorVisual("#FF6200EE", viewPurple) }
        viewBlue.setOnClickListener { actualizarColorVisual("#2196F3", viewBlue) }
        viewGreen.setOnClickListener { actualizarColorVisual("#4CAF50", viewGreen) }
        viewOrange.setOnClickListener { actualizarColorVisual("#FF9800", viewOrange) }
        viewRed.setOnClickListener { actualizarColorVisual("#F44336", viewRed) }

        // Si el avatar guardado es una ruta de archivo posta (empieza con /), intento cargarlo desde la memoria.
        if (currentProfile.avatar.isNotEmpty() && currentProfile.avatar.startsWith("/")) {
            val avatarFile = File(currentProfile.avatar)
            if (avatarFile.exists()) {
                avatarImageView.setImageURI(Uri.fromFile(avatarFile))
                avatarImageView.clipToOutline = true
            }
        }

        // Si tocan la foto de perfil, abro la galería con esto que preparé arriba.
        avatarImageView.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        // Acá viene lo picante. Botón de guardar cambios.
        btnSave.setOnClickListener {
            var finalAvatarPath = currentProfile.avatar
            
            // Si el chabón eligió una foto nueva, la guardo en una carpeta privada mía y me quedo con la ruta.
            selectedAvatarUri?.let { uri ->
                val savedPath = saveAvatarLocally(uri)
                if (savedPath != null) {
                    finalAvatarPath = savedPath
                } else {
                    Toast.makeText(this, "Error al guardar el avatar", Toast.LENGTH_SHORT).show()
                }
            }

            // Armo un mapa con los atributos falopa (bio y rol).
            val nuevosAtributos = mapOf(
                "bio" to etBio.text.toString().trim(),
                "rol" to etRole.text.toString().trim()
            )

            // Creo el nuevo perfil con todo actualizado.
            val newProfile = UserProfile(
                id = currentProfile.id,
                name = etName.text.toString().trim(),
                avatar = finalAvatarPath,
                colorHex = currentColorHex,
                customAttributes = nuevosAtributos
            )

            // Y lo mando a guardar en mis preferencias. Tiro un Toast medio místico para festejar.
            prefs.saveUserProfile(newProfile)
            Toast.makeText(this, "Identidad y aura actualizadas localmente", Toast.LENGTH_SHORT).show()
        }

        // La típica barra de navegación de abajo. 
        // Si tocan otro ítem, abro esa actividad y mato las demás para no acumular pantallas a lo loco.
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
                    val intent = Intent(this, ChatsListActivity::class.java)
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
        // Acá guardo el color nuevo.
        currentColorHex = hexCode
        
        // Busco todas las vistas de colores y le bajo la opacidad a las que no están seleccionadas.
        // Tipo, si toco el azul, el azul queda en 1.0 (bien fuerte) y el resto en 0.3 (medio transparentes).
        val todasLasVistas = listOf(viewPurple, viewBlue, viewGreen, viewOrange, viewRed)
        for (vista in todasLasVistas) {
            vista.alpha = if (vista == selectedView) 1.0f else 0.3f
        }

        // Trato de pintarle el fondo a la foto de perfil con el color que eligió.
        try {
            val colorInt = Color.parseColor(hexCode)
            avatarImageView.backgroundTintList = ColorStateList.valueOf(colorInt)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun seleccionarColorPorHex(hexCode: String) {
        // Nada, una simple función para saber qué vista tengo que marcar cuando arranca la pantalla.
        when (hexCode) {
            "#2196F3" -> actualizarColorVisual(hexCode, viewBlue)
            "#4CAF50" -> actualizarColorVisual(hexCode, viewGreen)
            "#FF9800" -> actualizarColorVisual(hexCode, viewOrange)
            "#F44336" -> actualizarColorVisual(hexCode, viewRed)
            else -> actualizarColorVisual("#FF6200EE", viewPurple)
        }
    }

    private fun saveAvatarLocally(uri: Uri): String? {
        // Esta función me salvó la vida. Agarra la foto de la galería y la copia a los archivos privados de la app.
        // Si no hacía esto, Android me cortaba el acceso a la foto apenas cerraba la app.
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        
        // Le mando un UUID random para que no se sobreescriban si cambia mucho de foto.
        val uniqueFileName = "avatar_${UUID.randomUUID()}.jpg"

        val file = File(filesDir, uniqueFileName)
        val outputStream = FileOutputStream(file)

        return try {
            // Copio los bytes tal cual.
            inputStream.copyTo(outputStream)
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            // Cierro los streams porque sino me como toda la RAM.
            inputStream.close()
            outputStream.close()
        }
    }
}