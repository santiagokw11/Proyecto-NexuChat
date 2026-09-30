package com.offline.nexu.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.databinding.ActivityContactInfoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactInfoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityContactInfoBinding
    private var contactName: String = ""
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Preparo el diseño de la pantalla de información del contacto.
        binding = ActivityContactInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        // Obtengo el nombre del contacto al que le di tap en el chat.
        contactName = intent.getStringExtra("EXTRA_USER_NAME") ?: "Desconocido"

        // Pongo el nombre del contacto en pantalla para saber con quién estoy tratando.
        binding.tvContactName.text = contactName
        binding.tvContactId.text = "Conectado | $contactName"

        // Empiezo a buscar las fotos compartidas con esta persona.
        cargarArchivosCompartidos()

        // Botón de atrás.
        binding.btnBack.setOnClickListener { finish() }

        // Abre la pantalla de la Bóveda de Archivos donde se ven todas las fotos, links, etc.
        binding.btnViewFiles.setOnClickListener {
            val intent = Intent(this, MediaVaultActivity::class.java)
            intent.putExtra("EXTRA_USER_NAME", contactName)
            startActivity(intent)
        }

        // Si toco buscar, solo pongo un aviso porque esto aún no está programado.
        binding.btnSearch.setOnClickListener {
            Toast.makeText(this, "Buscador en desarrollo", Toast.LENGTH_SHORT).show()
        }

        // Al tocar estos botones, muestro una pequeña ventana para que me confirmen antes de borrar cosas.
        binding.btnEmptyChat.setOnClickListener { confirmarAccion("vaciar") }
        binding.llDeleteChat.setOnClickListener { confirmarAccion("eliminar") }
    }

    private fun cargarArchivosCompartidos() {
        // Uso corrutinas para buscar las imágenes en segundo plano en mi base de datos.
        lifecycleScope.launch(Dispatchers.IO) {
            val imagenes = db.nexuDao().getSharedImages(contactName)

            // Vuelvo al hilo principal para actualizar la pantalla.
            withContext(Dispatchers.Main) {
                if (imagenes.isNotEmpty()) {
                    // Si tengo fotos con este contacto, digo cuántas hay y las muestro de ladito (horizontal).
                    binding.tvFilesCount.text = "${imagenes.size} >"
                    binding.rvSharedFiles.layoutManager = LinearLayoutManager(this@ContactInfoActivity, LinearLayoutManager.HORIZONTAL, false)
                    binding.rvSharedFiles.adapter = SharedImagesAdapter(imagenes)
                    binding.rvSharedFiles.visibility = View.VISIBLE
                } else {
                    // Si no tengo fotos, oculto la lista.
                    binding.tvFilesCount.text = "0 >"
                    binding.rvSharedFiles.visibility = View.GONE
                }
            }
        }
    }

    private fun confirmarAccion(accion: String) {
        // Preparo el texto dependiendo de si voy a "vaciar" o "eliminar" por completo.
        val mensaje = if (accion == "vaciar") {
            "¿Deseas vaciar todos los mensajes de este chat?"
        } else {
            "¿Deseas eliminar este chat por completo?"
        }

        // Saco una ventanita de Android para confirmar mi decisión.
        AlertDialog.Builder(this)
            .setTitle("Confirmar")
            .setMessage(mensaje)
            .setPositiveButton("Sí") { _, _ ->
                // Si me dicen que sí, borro todos los mensajes de la base de datos en segundo plano.
                lifecycleScope.launch(Dispatchers.IO) {
                    db.nexuDao().deleteAllMessagesFrom(contactName)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ContactInfoActivity, "Chat ${if(accion=="vaciar") "vaciado" else "eliminado"}", Toast.LENGTH_SHORT).show()
                        if (accion == "eliminar") {
                            // Si lo eliminé, regreso a la pantalla de chats mandando un aviso de "Borrar".
                            setResult(RESULT_OK, Intent().putExtra("ACTION", "DELETE"))
                            finish()
                        } else {
                            // Si solo lo vacié, actualizo las fotos que se ven y aviso con "Vaciar".
                            cargarArchivosCompartidos() 
                            setResult(RESULT_OK, Intent().putExtra("ACTION", "EMPTY"))
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}