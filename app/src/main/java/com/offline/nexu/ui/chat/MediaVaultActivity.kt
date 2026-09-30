package com.offline.nexu.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.offline.nexu.R
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.databinding.ActivityMediaVaultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaVaultActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMediaVaultBinding
    private var contactName: String = ""
    private lateinit var db: AppDatabase
    private var imagesList = listOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMediaVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactName = intent.getStringExtra("EXTRA_USER_NAME") ?: "Desconocido"
        db = AppDatabase.getDatabase(this)

        binding.btnBack.setOnClickListener { finish() }

        // Cuadrícula ajustada para NexuChat
        binding.rvMedia.layoutManager = GridLayoutManager(this, 3)

        cargarImagenes()
        setupCustomTabs()
    }

    private fun cargarImagenes() {
        lifecycleScope.launch(Dispatchers.IO) {
            imagesList = db.nexuDao().getSharedImages(contactName)
            withContext(Dispatchers.Main) {
                mostrarMultimedia()
            }
        }
    }

    private fun setupCustomTabs() {
        binding.tabMedia.setOnClickListener {
            seleccionarPestaña(binding.tabMedia)
            mostrarMultimedia()
        }
        binding.tabDocs.setOnClickListener {
            seleccionarPestaña(binding.tabDocs)
            mostrarMensajeVacio("Sin documentos encriptados")
        }
        binding.tabLinks.setOnClickListener {
            seleccionarPestaña(binding.tabLinks)
            mostrarMensajeVacio("Sin enlaces compartidos")
        }
    }

    private fun seleccionarPestaña(tabSeleccionada: TextView) {
        // Reiniciar todas a estado inactivo
        val tabs = listOf(binding.tabMedia, binding.tabDocs, binding.tabLinks)
        for (tab in tabs) {
            tab.background = null
            tab.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tab.setTypeface(null, android.graphics.Typeface.NORMAL)
        }

        // Activar la seleccionada
        tabSeleccionada.setBackgroundResource(R.drawable.button_rounded)
        tabSeleccionada.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_card)
        tabSeleccionada.setTextColor(ContextCompat.getColor(this, R.color.primary_color))
        tabSeleccionada.setTypeface(null, android.graphics.Typeface.BOLD)
    }

    private fun mostrarMultimedia() {
        if (imagesList.isEmpty()) {
            mostrarMensajeVacio("No hay medios en la bóveda")
        } else {
            binding.layoutEmptyState.visibility = View.GONE
            binding.rvMedia.visibility = View.VISIBLE
            binding.rvMedia.adapter = MediaVaultAdapter(imagesList) { uri ->
                val intent = Intent(this@MediaVaultActivity, ImageViewerActivity::class.java)
                intent.putExtra("IMAGE_URI", uri)
                startActivity(intent)
            }
        }
    }

    private fun mostrarMensajeVacio(mensaje: String) {
        binding.rvMedia.visibility = View.GONE
        binding.layoutEmptyState.visibility = View.VISIBLE
        binding.tvEmptyState.text = mensaje
    }
}