package com.offline.nexu.ui.chat

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.MessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ContactInfoActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var myLocalUserId: String
    private lateinit var contactId: String
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact_info)

        database = AppDatabase.getDatabase(this)
        prefs = Prefs(this)
        myLocalUserId = prefs.getUserProfile().id
        contactId = intent.getStringExtra("EXTRA_USER_NAME") ?: ""

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<TextView>(R.id.tvContactName).text = contactId
        findViewById<TextView>(R.id.tvContactId).text = "ID P2P: $contactId"

        val avatarStr = prefs.getContactAvatar(contactId)
        val tvAvatarEmoji = findViewById<TextView>(R.id.tvAvatarEmoji)

        if (avatarStr.startsWith("file://") || avatarStr.startsWith("content://") || avatarStr.startsWith("/")) {
            tvAvatarEmoji.text = ""
            try {
                val uri = Uri.parse(avatarStr)
                val file = File(uri.path!!)
                if (file.exists()) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    val rounded = RoundedBitmapDrawableFactory.create(resources, bitmap)
                    rounded.isCircular = true
                    tvAvatarEmoji.background = rounded
                }
            } catch (e: Exception) {
                tvAvatarEmoji.text = "👤"
            }
        } else {
            tvAvatarEmoji.text = avatarStr
        }

        tvAvatarEmoji.setOnClickListener {
            if (avatarStr.startsWith("file://") || avatarStr.startsWith("content://") || avatarStr.startsWith("/")) {
                val intent = Intent(this, ImageViewerActivity::class.java)
                intent.putExtra("IMAGE_URI", avatarStr)
                startActivity(intent)
            } else {
                Toast.makeText(this, "El usuario utiliza un emoji como avatar", Toast.LENGTH_SHORT).show()
            }
        }

        val rvSharedFiles = findViewById<RecyclerView>(R.id.rvSharedFiles)
        rvSharedFiles.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        lifecycleScope.launch(Dispatchers.IO) {
            val messages = database.nexuDao().getMessagesForUserSync(contactId, myLocalUserId)
            val mediaFiles = messages.filter { it.imagePath != null && it.status != -1 }

            withContext(Dispatchers.Main) {
                val tvFilesCount = findViewById<TextView>(R.id.tvFilesCount)
                if (mediaFiles.isEmpty()) {
                    tvFilesCount?.text = "0 archivos >"
                } else {
                    tvFilesCount?.text = "${mediaFiles.size} archivos >"

                    val mediaPaths = mediaFiles.take(10).mapNotNull { it.imagePath }

                    // CORRECCIÓN DEL ERROR DE COMPILACIÓN
                    rvSharedFiles.adapter = MediaVaultAdapter(mediaPaths, "MEDIA", false) { filePath ->
                        val isVideo = filePath.endsWith(".mp4", ignoreCase = true) || filePath.endsWith(".mkv", ignoreCase = true)

                        if (isVideo) {
                            val intent = Intent(this@ContactInfoActivity, VideoPlayerActivity::class.java).apply {
                                putExtra("video_path", filePath)
                            }
                            startActivity(intent)
                        } else {
                            val intent = Intent(this@ContactInfoActivity, ImageViewerActivity::class.java).apply {
                                putExtra("IMAGE_URI", filePath)
                            }
                            startActivity(intent)
                        }
                    }
                }
            }
        }

        findViewById<RelativeLayout>(R.id.btnViewFiles).setOnClickListener {
            val intent = Intent(this, MediaVaultActivity::class.java)
            intent.putExtra("EXTRA_USER_NAME", contactId)
            startActivity(intent)
        }

        findViewById<LinearLayout>(R.id.btnSearch).setOnClickListener {
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("EXTRA_USER_NAME", contactId)
            intent.putExtra("EXTRA_IS_GROUP", false)
            intent.putExtra("EXTRA_SEARCH_MODE", true)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        findViewById<LinearLayout>(R.id.btnEmptyChat).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Vaciar Chat")
                .setMessage("¿Estás seguro de vaciar el historial con $contactId?")
                .setPositiveButton("Vaciar") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val messages = database.nexuDao().getMessagesForUserSync(contactId, myLocalUserId)
                        borrarArchivosFisicos(messages)
                        database.nexuDao().deleteAllMessagesFrom(contactId, myLocalUserId)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ContactInfoActivity, "Historial vaciado", Toast.LENGTH_SHORT).show()
                            recreate()
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        findViewById<LinearLayout>(R.id.llDeleteChat).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Eliminar Chat")
                .setMessage("¿Estás seguro de eliminar el chat con $contactId?")
                .setPositiveButton("Eliminar") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val messages = database.nexuDao().getMessagesForUserSync(contactId, myLocalUserId)
                        borrarArchivosFisicos(messages)
                        database.nexuDao().deleteAllMessagesFrom(contactId, myLocalUserId)

                        val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
                        sharedPreferences.edit().remove("pinned_${myLocalUserId}_${contactId}").apply()
                        sharedPreferences.edit().remove("muted_until_${myLocalUserId}_${contactId}").apply()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@ContactInfoActivity, "Chat eliminado", Toast.LENGTH_SHORT).show()
                            val intent = Intent(this@ContactInfoActivity, ChatsListActivity::class.java)
                            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(intent)
                            finish()
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun borrarArchivosFisicos(messages: List<MessageEntity>) {
        messages.forEach { msg ->
            if (!msg.imagePath.isNullOrEmpty()) {
                try {
                    val uri = Uri.parse(msg.imagePath)
                    val file = File(uri.path ?: return@forEach)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {}
            }
        }
    }
}