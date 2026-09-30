package com.offline.nexu.ui.chat

import android.content.Intent
import android.graphics.Typeface
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
        // Acá arranco y conecto el ViewBinding porque me da tremenda paja hacer findViewById de todo.
        binding = ActivityMediaVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Saco el nombre del contacto del intent para saber qué onda. Si falla, va "Desconocido" y rezamos.
        contactName = intent.getStringExtra("EXTRA_USER_NAME") ?: "Desconocido"
        
        // Instancio la base de datos de Room.
        db = AppDatabase.getDatabase(this)

        // Este botón corta el mambo y te manda para atrás, cortita y al pie.
        binding.btnBack.setOnClickListener { finish() }

        // Pongo el recycler con GridLayoutManager para que quede facherito tipo galería en 3 columnas.
        binding.rvMedia.layoutManager = GridLayoutManager(this, 3)

        // Cargo las fotos y armo el bardo de las pestañitas de arriba.
        cargarImagenes()
        setupCustomTabs()
    }

    private fun cargarImagenes() {
        // Mando corrutina en IO. Si no hago esto, Android se re calienta y crashea la app por leer la DB en el Main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            // Busco todas las imágenes que mandé o me mandaron con este chabón.
            imagesList = db.nexuDao().getSharedImages(contactName)
            
            // Vuelvo al hilo principal porque tocar la UI desde IO te escupe una excepción roja gigante.
            withContext(Dispatchers.Main) {
                mostrarMultimedia()
            }
        }
    }

    private fun setupCustomTabs() {
        // Hago andar las pestañitas de arriba. Básicamente pinto la que toco y cambio lo que se ve abajo.
        
        binding.tabMedia.setOnClickListener {
            seleccionarPestaña(binding.tabMedia)
            mostrarMultimedia() // Acá sí muestro la cuadrícula posta.
        }
        
        binding.tabDocs.setOnClickListener {
            seleccionarPestaña(binding.tabDocs)
            // Como todavía no programé lo de documentos, tiro humo diciendo que no hay nada encriptado jaja.
            mostrarMensajeVacio("Sin documentos encriptados")
        }
        
        binding.tabLinks.setOnClickListener {
            seleccionarPestaña(binding.tabLinks)
            // Lo mismo para los links, humo cósmico por ahora.
            mostrarMensajeVacio("Sin enlaces compartidos")
        }
    }

    private fun seleccionarPestaña(tabSeleccionada: TextView) {
        // Esta función me costó un toque pensarla, pero básicamente "apago" todas las pestañas primero...
        val tabs = listOf(binding.tabMedia, binding.tabDocs, binding.tabLinks)
        for (tab in tabs) {
            tab.background = null
            tab.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tab.setTypeface(null, Typeface.NORMAL)
        }

        // ... y después "prendo" solo la que toqué pasándole la vista por parámetro.
        // Le clavo un fondo con bordes, negrita y color primario para que se re note.
        tabSeleccionada.setBackgroundResource(R.drawable.button_rounded)
        tabSeleccionada.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_card)
        tabSeleccionada.setTextColor(ContextCompat.getColor(this, R.color.primary_color))
        tabSeleccionada.setTypeface(null, Typeface.BOLD)
    }

    private fun mostrarMultimedia() {
        // Me fijo si la consulta a la BD trajo algo.
        if (imagesList.isEmpty()) {
            // Si no hay nada, clavo el layout feo de que está vacío.
            mostrarMensajeVacio("No hay medios en la bóveda")
        } else {
            // Si hay fotos, escondo el mensaje pedorro y muestro la galería posta.
            binding.layoutEmptyState.visibility = View.GONE
            binding.rvMedia.visibility = View.VISIBLE
            
            // Le paso la lista al adapter que armé para esto.
            binding.rvMedia.adapter = MediaVaultAdapter(imagesList) { uri ->
                // Si el chabón toca una foto, abro la otra Activity que armé para ver la imagen en grande.
                val intent = Intent(this@MediaVaultActivity, ImageViewerActivity::class.java)
                intent.putExtra("IMAGE_URI", uri)
                startActivity(intent)
            }
        }
    }

    private fun mostrarMensajeVacio(mensaje: String) {
        // Funciocinta rápida para esconder el recycler y mostrar el TextView de "acá no hay nada".
        binding.rvMedia.visibility = View.GONE
        binding.layoutEmptyState.visibility = View.VISIBLE
        binding.tvEmptyState.text = mensaje
    }
}