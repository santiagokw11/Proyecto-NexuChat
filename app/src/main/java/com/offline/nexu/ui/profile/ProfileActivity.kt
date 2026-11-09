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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.ui.chat.ChatsListActivity
import com.offline.nexu.ui.chat.helpers.AttachmentHelper
import com.offline.nexu.ui.chat.helpers.EmojiHelper
import com.offline.nexu.ui.home.HomeActivity
import com.offline.nexu.ui.settings.SettingsActivity
import com.offline.nexu.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.concurrent.TimeUnit

class ProfileActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var avatarImageView: ImageView
    private var selectedAvatarUri: Uri? = null

    private lateinit var tvProfileTitle: TextView
    private lateinit var tvTimeUsingApp: TextView
    private lateinit var tvMessagesProcessed: TextView
    private lateinit var btnSave: Button
    private lateinit var bottomNavigation: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        prefs = Prefs(this)
        val currentProfile = prefs.getUserProfile()

        tvProfileTitle = findViewById(R.id.tvProfileTitle)
        avatarImageView = findViewById(R.id.etAvatar)
        val etName = findViewById<EditText>(R.id.etName)
        val etStatusMessage = findViewById<EditText>(R.id.etStatusMessage)

        // Usamos null safety por si llegaste a eliminar el campo de emoji del XML
        val etMoodEmoji = findViewById<EditText?>(R.id.etMoodEmoji)

        etMoodEmoji?.isFocusable = false
        etMoodEmoji?.isClickable = true
        etMoodEmoji?.setOnClickListener {
            mostrarDialogoEmojisMood(etMoodEmoji)
        }

        tvTimeUsingApp = findViewById(R.id.tvTimeUsingApp)
        tvMessagesProcessed = findViewById(R.id.tvMessagesProcessed)

        btnSave = findViewById(R.id.btnSaveProfile)
        bottomNavigation = findViewById(R.id.bottom_navigation)

        etName.setText(currentProfile.name)
        etStatusMessage.setText(currentProfile.customAttributes["status_message"] ?: "")
        etMoodEmoji?.setText(currentProfile.customAttributes["mood_emoji"] ?: "")

        val createdAt = prefs.getAccountCreatedAt(currentProfile.id)
        tvTimeUsingApp.text = "Tiempo en la app: ${calculateTimeUsingApp(createdAt)}"

        // LÓGICA DEL DESPLEGABLE DE ESTADÍSTICAS
        val btnToggleStats = findViewById<LinearLayout>(R.id.btnToggleStats)
        val layoutStatsContent = findViewById<LinearLayout>(R.id.layoutStatsContent)
        val ivStatsArrow = findViewById<ImageView>(R.id.ivStatsArrow)

        btnToggleStats.setOnClickListener {
            if (layoutStatsContent.visibility == View.GONE) {
                // Abrir
                layoutStatsContent.visibility = View.VISIBLE
                ivStatsArrow.animate().rotation(180f).setDuration(250).start()
            } else {
                // Cerrar
                layoutStatsContent.visibility = View.GONE
                ivStatsArrow.animate().rotation(0f).setDuration(250).start()
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val msgCount = db.nexuDao().getTotalMessagesCount(currentProfile.id)
            withContext(Dispatchers.Main) {
                tvMessagesProcessed.text = "Mensajes procesados: $msgCount"
            }
        }

        aplicarColorGlobal(currentProfile.colorHex)

        if (currentProfile.avatar.isNotEmpty() && currentProfile.avatar.startsWith("/")) {
            val avatarFile = File(currentProfile.avatar)
            if (avatarFile.exists()) {
                avatarImageView.setImageURI(Uri.fromFile(avatarFile))
                avatarImageView.clipToOutline = true
            }
        }

        // Abre tu galería personalizada (Custom Gallery)
        avatarImageView.setOnClickListener {
            AttachmentHelper.openCustomGallery(this, layoutInflater) { uri ->
                selectedAvatarUri = uri
                avatarImageView.setImageURI(uri)
                avatarImageView.clipToOutline = true
            }
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

            val nuevosAtributos = currentProfile.customAttributes.toMutableMap()
            nuevosAtributos["status_message"] = etStatusMessage.text.toString().trim()
            etMoodEmoji?.let {
                nuevosAtributos["mood_emoji"] = it.text.toString().trim()
            }

            val newProfile = currentProfile.copy(
                name = etName.text.toString().trim(),
                avatar = finalAvatarPath,
                customAttributes = nuevosAtributos
            )

            prefs.saveUserProfile(newProfile)
            Toast.makeText(this, "Identidad actualizada en la Colmena", Toast.LENGTH_SHORT).show()
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
                    val intent = Intent(this, ChatsListActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_profile -> true
                R.id.nav_settings -> {
                    val intent = Intent(this, SettingsActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                else -> false
            }
        }
    }

    private fun mostrarDialogoEmojisMood(etMoodEmoji: EditText) {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_emoji_picker, null)
        bottomSheet.setContentView(view)

        val currentProfile = prefs.getUserProfile()
        var auraColor = Color.parseColor("#4CAF50")
        try { auraColor = Color.parseColor(currentProfile.colorHex) } catch (e: Exception) { e.printStackTrace() }

        val emojiHelper = EmojiHelper(view, auraColor) { emoji ->
            etMoodEmoji.setText(emoji)
            bottomSheet.dismiss()
        }
        emojiHelper.setup()

        view.findViewById<View>(R.id.flEmojiPicker)?.visibility = View.VISIBLE

        val parent = view.parent as View
        val layoutParams = parent.layoutParams
        layoutParams.height = (resources.displayMetrics.heightPixels * 0.55).toInt()
        parent.layoutParams = layoutParams

        bottomSheet.show()
    }

    override fun onResume() {
        super.onResume()
        bottomNavigation.selectedItemId = R.id.nav_profile
        val currentProfile = prefs.getUserProfile()
        aplicarColorGlobal(currentProfile.colorHex)
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
    }

    private fun calculateTimeUsingApp(createdAt: Long): String {
        val diffInMs = System.currentTimeMillis() - createdAt
        val days = TimeUnit.MILLISECONDS.toDays(diffInMs) + 1
        return if (days == 1L) {
            "$days día"
        } else {
            "$days días"
        }
    }

    private fun aplicarColorGlobal(hexCode: String) {
        try {
            val colorInt = Color.parseColor(hexCode)
            tvProfileTitle.setTextColor(colorInt)

            // Colorea la flechita del acordeón con el color del Aura también
            findViewById<ImageView>(R.id.ivStatsArrow)?.setColorFilter(colorInt)

            avatarImageView.backgroundTintList = ColorStateList.valueOf(colorInt)
            btnSave.backgroundTintList = ColorStateList.valueOf(colorInt)

            val navColors = ThemeUtils.getBottomNavColorStateList(colorInt)
            bottomNavigation.itemIconTintList = navColors
            bottomNavigation.itemTextColor = navColors
        } catch (e: Exception) {
            e.printStackTrace()
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