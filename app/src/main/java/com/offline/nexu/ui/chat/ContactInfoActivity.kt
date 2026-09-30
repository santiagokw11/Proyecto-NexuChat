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
    // Declaro el binding acá arriba para poder usarlo en toda la clase. Usar findViewById es alto bardo a esta altura.
    private lateinit var binding: ActivityContactInfoBinding
    private var contactName: String = ""
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Bueno, acá inflo la vista. Básicamente engancho el XML con el código.
        binding = ActivityContactInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Instancio la base de datos de Room. Siempre clave para no perder data.
        db = AppDatabase.getDatabase(this)
        
        // A ver, me traigo el nombre del loco con el que estoy hablando desde el Intent. 
        // Si por alguna razón llega null, le clavo "Desconocido" y fue.
        contactName = intent.getStringExtra("EXTRA_USER_NAME") ?: "Desconocido"

        // Clavo el nombre del contacto en los textviews para saber con quién estoy tratando en esta pantalla.
        binding.tvContactName.text = contactName
        binding.tvContactId.text = "Conectado | $contactName"

        // Llamo a esto de una para que vaya buscando las fotos mientras el usuario mira la pantalla.
        cargarArchivosCompartidos()

        // Literal este botón solo hace que vuelva para atrás. Corta la bocha.
        binding.btnBack.setOnClickListener { finish() }

        // Acá armo el intent para abrir la Bóveda de Archivos. 
        // Medio flashero el nombre, pero bueno, ahí van todas las fotos.
        binding.btnViewFiles.setOnClickListener {
            val intent = Intent(this, MediaVaultActivity::class.java)
            intent.putExtra("EXTRA_USER_NAME", contactName)
            startActivity(intent)
        }

        // El buscador lo dejo para después, me da paja hacerlo ahora. 
        // Por ahora tiro un Toast para caretearla y que parezca que hace algo.
        binding.btnSearch.setOnClickListener {
            Toast.makeText(this, "Buscador en desarrollo", Toast.LENGTH_SHORT).show()
        }

        // Acá configuro los botones de borrar. Les paso "vaciar" o "eliminar" para reciclar la misma función de abajo.
        binding.btnEmptyChat.setOnClickListener { confirmarAccion("vaciar") }
        binding.llDeleteChat.setOnClickListener { confirmarAccion("eliminar") }
    }

    private fun cargarArchivosCompartidos() {
        // Mando una corrutina en IO porque Android se queja si toco la BD en el hilo principal (tira error y crashea).
        lifecycleScope.launch(Dispatchers.IO) {
            val imagenes = db.nexuDao().getSharedImages(contactName)

            // Vuelvo al Main para poder tocar la UI. Si no, revienta todo.
            withContext(Dispatchers.Main) {
                if (imagenes.isNotEmpty()) {
                    // Chequeo si hay fotos. Si hay, le clavo el tamaño al textview y armo el recycler.
                    binding.tvFilesCount.text = "${imagenes.size} >"
                    // Lo pongo en horizontal para que se deslice de costadito tipo Instagram.
                    binding.rvSharedFiles.layoutManager = LinearLayoutManager(this@ContactInfoActivity, LinearLayoutManager.HORIZONTAL, false)
                    binding.rvSharedFiles.adapter = SharedImagesAdapter(imagenes)
                    binding.rvSharedFiles.visibility = View.VISIBLE
                } else {
                    // Si no pasaron ninguna foto, escondo el recycler para que no ocupe espacio al pedo.
                    binding.tvFilesCount.text = "0 >"
                    binding.rvSharedFiles.visibility = View.GONE
                }
            }
        }
    }

    private fun confirmarAccion(accion: String) {
        // Preparo el texto del cartelito. Medio paja el if, pero bueno, hay que hacerlo dinámico.
        val mensaje = if (accion == "vaciar") {
            "¿Deseas vaciar todos los mensajes de este chat?"
        } else {
            "¿Deseas eliminar este chat por completo?"
        }

        // Saco el clásico AlertDialog de Android para confirmar y no cagarla si el usuario tocó sin querer.
        AlertDialog.Builder(this)
            .setTitle("Confirmar")
            .setMessage(mensaje)
            .setPositiveButton("Sí") { _, _ ->
                
                // Si me dicen que sí, otra vez al hilo secundario para mandarle mecha a la base de datos.
                lifecycleScope.launch(Dispatchers.IO) {
                    db.nexuDao().deleteAllMessagesFrom(contactName)

                    // Vuelvo al hilo principal para avisar que ya borré todo.
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ContactInfoActivity, "Chat ${if(accion=="vaciar") "vaciado" else "eliminado"}", Toast.LENGTH_SHORT).show()
                        
                        if (accion == "eliminar") {
                            // Si eligió eliminar, le devuelvo un OK a la pantalla anterior avisando de la acción para que actualice su lista.
                            setResult(RESULT_OK, Intent().putExtra("ACTION", "DELETE"))
                            finish()
                        } else {
                            // Si solo lo vació, recargo las fotos (que ahora van a ser 0) y aviso "EMPTY".
                            cargarArchivosCompartidos() 
                            setResult(RESULT_OK, Intent().putExtra("ACTION", "EMPTY"))
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null) // Si cancela, no hago nada de nada.
            .show()
    }
}