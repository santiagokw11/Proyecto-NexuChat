package com.offline.nexu.ui.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.databinding.ActivityMediaVaultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaVaultActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaVaultBinding
    private var contactName: String = ""
    private var isGlobal: Boolean = false
    private lateinit var db: AppDatabase
    private lateinit var myLocalUserId: String

    private var currentTab: String = "MEDIA"
    private var mediaList = listOf<String>()
    private var docsList = listOf<String>()
    private var linksList = listOf<String>()

    // Modo de visualización: "", "GALLERY_ONLY", "DOCS_ONLY"
    private var viewMode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactName = intent.getStringExtra("EXTRA_USER_NAME") ?: ""
        isGlobal = intent.getBooleanExtra("EXTRA_GLOBAL", false)
        viewMode = intent.getStringExtra("EXTRA_MODE") ?: ""

        db = AppDatabase.getDatabase(this)
        myLocalUserId = Prefs(this).getUserProfile().id

        binding.btnBack.setOnClickListener { finish() }

        setupCustomTabs()

        val defaultTab = if (viewMode == "DOCS_ONLY") "DOCS" else "MEDIA"
        cargarDatos(defaultTab)
    }

    private fun cargarDatos(defaultTab: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val allPaths = if (isGlobal) {
                db.nexuDao().getAllSharedImages(myLocalUserId).filter { it.isNotBlank() }
            } else {
                db.nexuDao().getSharedImages(contactName, myLocalUserId).filter { it.isNotBlank() }
            }

            val allLinks = if (isGlobal) {
                db.nexuDao().getAllSharedLinks(myLocalUserId).filter { it.isNotBlank() }
            } else {
                db.nexuDao().getSharedLinks(contactName, myLocalUserId).filter { it.isNotBlank() }
            }

            val tempMedia = mutableListOf<String>()
            val tempDocs = mutableListOf<String>()

            allPaths.forEach { path ->
                val lower = path.lowercase()
                if (lower.endsWith(".mp4") || lower.contains("vid_") || lower.contains("video") ||
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".gif") || lower.endsWith(".webp") || lower.contains("img_") || lower.contains("image")) {
                    tempMedia.add(path)
                } else {
                    tempDocs.add(path)
                }
            }

            mediaList = tempMedia
            docsList = tempDocs

            val extractedLinks = mutableListOf<String>()
            val regex = "(?i)\\b((?:https?://|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,4}/)(?:[^\\s()<>]+|\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\))+(?:\\(([^\\s()<>]+|(\\([^\\s()<>]+\\)))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))".toRegex()
            allLinks.forEach { text ->
                regex.findAll(text).forEach { matchResult ->
                    extractedLinks.add(matchResult.value)
                }
            }
            linksList = extractedLinks.distinct()

            withContext(Dispatchers.Main) {
                when (defaultTab) {
                    "DOCS" -> {
                        seleccionarPestaña(binding.tabDocs)
                        currentTab = "DOCS"
                    }
                    "LINKS" -> {
                        seleccionarPestaña(binding.tabLinks)
                        currentTab = "LINKS"
                    }
                    else -> {
                        seleccionarPestaña(binding.tabMedia)
                        currentTab = "MEDIA"
                    }
                }
                actualizarLista()
            }
        }
    }

    private fun setupCustomTabs() {
        when (viewMode) {
            "GALLERY_ONLY" -> {
                // Aquí matamos el contenedor gris para que no quede la franja
                binding.tabContainer.visibility = View.GONE
            }
            "DOCS_ONLY" -> {
                binding.tabContainer.visibility = View.VISIBLE
                binding.tabMedia.visibility = View.GONE
                binding.tabDocs.visibility = View.VISIBLE
                binding.tabLinks.visibility = View.VISIBLE
            }
            else -> {
                binding.tabContainer.visibility = View.VISIBLE
                binding.tabMedia.visibility = View.VISIBLE
                binding.tabDocs.visibility = View.VISIBLE
                binding.tabLinks.visibility = View.VISIBLE
            }
        }

        binding.tabMedia.setOnClickListener {
            seleccionarPestaña(binding.tabMedia)
            currentTab = "MEDIA"
            actualizarLista()
        }
        binding.tabDocs.setOnClickListener {
            seleccionarPestaña(binding.tabDocs)
            currentTab = "DOCS"
            actualizarLista()
        }
        binding.tabLinks.setOnClickListener {
            seleccionarPestaña(binding.tabLinks)
            currentTab = "LINKS"
            actualizarLista()
        }
    }

    private fun seleccionarPestaña(tabSeleccionada: TextView) {
        val tabs = listOf(binding.tabMedia, binding.tabDocs, binding.tabLinks)
        for (tab in tabs) {
            tab.background = null
            tab.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tab.setTypeface(null, android.graphics.Typeface.NORMAL)
        }

        tabSeleccionada.setBackgroundResource(R.drawable.button_rounded)
        tabSeleccionada.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_card)
        tabSeleccionada.setTextColor(ContextCompat.getColor(this, R.color.primary_color))
        tabSeleccionada.setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun actualizarLista() {
        val currentList = when (currentTab) {
            "MEDIA" -> mediaList
            "DOCS" -> docsList
            "LINKS" -> linksList
            else -> emptyList()
        }

        if (currentList.isEmpty()) {
            mostrarMensajeVacio(
                when (currentTab) {
                    "MEDIA" -> "No hay fotos ni videos guardados"
                    "DOCS" -> "Sin documentos, audios o archivos"
                    "LINKS" -> "Sin enlaces compartidos"
                    else -> "Vacío"
                }
            )
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.rvMedia.visibility = View.VISIBLE

            if (currentTab == "MEDIA") {
                binding.rvMedia.layoutManager = GridLayoutManager(this, 3)
            } else {
                binding.rvMedia.layoutManager = LinearLayoutManager(this)
            }

            binding.rvMedia.adapter = MediaVaultAdapter(currentList, currentTab, true) { itemStr ->
                manejarClickItem(itemStr)
            }
        }
    }

    // Identificador dinámico de MIME types para Android
    private fun getMimeType(url: String): String {
        var type: String? = null
        val extension = MimeTypeMap.getFileExtensionFromUrl(url.replace(" ", "%20"))
        if (extension != null) {
            type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        }
        return type ?: "*/*"
    }

    private fun manejarClickItem(itemStr: String) {
        try {
            if (currentTab == "LINKS") {
                var url = itemStr
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "http://$url"
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } else if (currentTab == "DOCS") {

                // LÓGICA DE APERTURA CON FILE PROVIDER
                val file = File(Uri.parse(itemStr).path ?: itemStr)
                val uriToOpen = if (itemStr.startsWith("content://")) {
                    Uri.parse(itemStr)
                } else if (file.exists()) {
                    FileProvider.getUriForFile(
                        this,
                        "${applicationContext.packageName}.provider",
                        file
                    )
                } else {
                    Uri.parse(itemStr)
                }

                val intent = Intent(Intent.ACTION_VIEW)
                val mimeType = getMimeType(itemStr)

                intent.setDataAndType(uriToOpen, mimeType)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                startActivity(intent)

            } else {
                val isVideo = itemStr.endsWith(".mp4", ignoreCase = true) || itemStr.contains("VID_") || itemStr.contains("video")
                if (isVideo) {
                    val intent = Intent(this@MediaVaultActivity, Class.forName("com.offline.nexu.ui.chat.VideoPlayerActivity"))
                    intent.putExtra("VIDEO_URI", itemStr)
                    startActivity(intent)
                } else {
                    val intent = Intent(this@MediaVaultActivity, ImageViewerActivity::class.java)
                    intent.putExtra("IMAGE_URI", itemStr)
                    startActivity(intent)
                }
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No tienes una aplicación instalada para abrir este tipo de archivo.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error al intentar abrir el archivo.", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun mostrarMensajeVacio(mensaje: String) {
        binding.rvMedia.visibility = View.GONE
        binding.layoutEmptyState.visibility = View.VISIBLE
        binding.tvEmptyState.text = mensaje
    }
}