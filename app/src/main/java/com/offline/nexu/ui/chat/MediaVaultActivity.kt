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
        // Aquí arranca la pantalla. Lo primero que hago es preparar el diseño visual conectando el ViewBinding.
        binding = ActivityMediaVaultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Obtengo el nombre del contacto desde la pantalla anterior para saber de quién son los archivos.
        contactName = intent.getStringExtra("EXTRA_USER_NAME") ?: "Desconocido"
        // Inicializo mi base de datos para poder buscar las imágenes guardadas.
        db = AppDatabase.getDatabase(this)

        // Botón simple para volver atrás y cerrar esta pantalla.
        binding.btnBack.setOnClickListener { finish() }

        // Configuro la lista para que las imágenes se vean como una cuadrícula de 3 columnas, estilo galería.
        binding.rvMedia.layoutManager = GridLayoutManager(this, 3)

        // Llamo a mis funciones para cargar los datos y configurar los botones de las pestañas.
        cargarImagenes()
        setupCustomTabs()
    }

    private fun cargarImagenes() {
        // Uso corrutinas para buscar en la base de datos en segundo plano y no congelar la pantalla de la app.
        lifecycleScope.launch(Dispatchers.IO) {
            // Busco todas las imágenes que he compartido con este contacto en específico.
            imagesList = db.nexuDao().getSharedImages(contactName)
            
            // Vuelvo al hilo principal (la interfaz gráfica) para mostrar las imágenes que encontré.
            withContext(Dispatchers.Main) {
                mostrarMultimedia()
            }
        }
    }

    private fun setupCustomTabs() {
        // Configuro qué pasa cuando toco cada pestaña (Media, Documentos, Enlaces).
        
        binding.tabMedia.setOnClickListener {
            seleccionarPestaña(binding.tabMedia)
            mostrarMultimedia() // Muestro la cuadrícula de fotos.
        }
        
        binding.tabDocs.setOnClickListener {
            seleccionarPestaña(binding.tabDocs)
            // Como aún no tengo documentos, solo muestro un mensaje de que está vacío.
            mostrarMensajeVacio("Sin documentos encriptados")
        }
        
        binding.tabLinks.setOnClickListener {
            seleccionarPestaña(binding.tabLinks)
            // Igual que con los documentos, por ahora solo muestro un mensaje de vacío.
            mostrarMensajeVacio("Sin enlaces compartidos")
        }
    }

    private fun seleccionarPestaña(tabSeleccionada: TextView) {
        // Primero, devuelvo todas las pestañas a su estado normal ("apagadas").
        // Les quito el fondo y les pongo la letra en su color secundario normal.
        val tabs = listOf(binding.tabMedia, binding.tabDocs, binding.tabLinks)
        for (tab in tabs) {
            tab.background = null
            tab.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            tab.setTypeface(null, Typeface.NORMAL)
        }

        // Luego, "enciendo" solo la pestaña que acabo de tocar.
        // Le pongo un fondo redondeado, cambio el color de letra y la pongo en negrita para que resalte.
        tabSeleccionada.setBackgroundResource(R.drawable.button_rounded)
        tabSeleccionada.backgroundTintList = ContextCompat.getColorStateList(this, R.color.surface_card)
        tabSeleccionada.setTextColor(ContextCompat.getColor(this, R.color.primary_color))
        tabSeleccionada.setTypeface(null, Typeface.BOLD)
    }

    private fun mostrarMultimedia() {
        // Verifico si encontré imágenes en la base de datos.
        if (imagesList.isEmpty()) {
            // Si la lista está vacía, muestro mi mensaje por defecto.
            mostrarMensajeVacio("No hay medios en la bóveda")
        } else {
            // Si tengo fotos, oculto el mensaje de "vacío" y hago visible mi cuadrícula.
            binding.layoutEmptyState.visibility = View.GONE
            binding.rvMedia.visibility = View.VISIBLE
            
            // Le paso la lista de imágenes al adaptador para que las dibuje en pantalla.
            binding.rvMedia.adapter = MediaVaultAdapter(imagesList) { uri ->
                // Si el usuario toca una imagen, abro el visor de imágenes a pantalla completa.
                val intent = Intent(this@MediaVaultActivity, ImageViewerActivity::class.java)
                intent.putExtra("IMAGE_URI", uri)
                startActivity(intent)
            }
        }
    }

    private fun mostrarMensajeVacio(mensaje: String) {
        // Esta función me sirve para esconder la cuadrícula y en su lugar mostrar un texto en medio de la pantalla.
        binding.rvMedia.visibility = View.GONE
        binding.layoutEmptyState.visibility = View.VISIBLE
        binding.tvEmptyState.text = mensaje
    }
}