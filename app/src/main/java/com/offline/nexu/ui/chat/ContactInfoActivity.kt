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
        binding = ActivityContactInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        contactName = intent.getStringExtra("EXTRA_USER_NAME") ?: "Desconocido"

        binding.tvContactName.text = contactName
        binding.tvContactId.text = "Conectado | $contactName"

        cargarArchivosCompartidos()

        binding.btnBack.setOnClickListener { finish() }

        // Abre la nueva pantalla de la Bóveda de Archivos
        binding.btnViewFiles.setOnClickListener {
            val intent = Intent(this, MediaVaultActivity::class.java)
            intent.putExtra("EXTRA_USER_NAME", contactName)
            startActivity(intent)
        }

        binding.btnSearch.setOnClickListener {
            Toast.makeText(this, "Buscador en desarrollo", Toast.LENGTH_SHORT).show()
        }

        binding.btnEmptyChat.setOnClickListener { confirmarAccion("vaciar") }
        binding.llDeleteChat.setOnClickListener { confirmarAccion("eliminar") }
    }

    private fun cargarArchivosCompartidos() {
        lifecycleScope.launch(Dispatchers.IO) {
            val imagenes = db.nexuDao().getSharedImages(contactName)

            withContext(Dispatchers.Main) {
                if (imagenes.isNotEmpty()) {
                    binding.tvFilesCount.text = "${imagenes.size} >"
                    binding.rvSharedFiles.layoutManager = LinearLayoutManager(this@ContactInfoActivity, LinearLayoutManager.HORIZONTAL, false)
                    binding.rvSharedFiles.adapter = SharedImagesAdapter(imagenes)
                    binding.rvSharedFiles.visibility = View.VISIBLE
                } else {
                    binding.tvFilesCount.text = "0 >"
                    binding.rvSharedFiles.visibility = View.GONE
                }
            }
        }
    }

    private fun confirmarAccion(accion: String) {
        val mensaje = if (accion == "vaciar") {
            "¿Deseas vaciar todos los mensajes de este chat?"
        } else {
            "¿Deseas eliminar este chat por completo?"
        }

        AlertDialog.Builder(this)
            .setTitle("Confirmar")
            .setMessage(mensaje)
            .setPositiveButton("Sí") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.nexuDao().deleteAllMessagesFrom(contactName)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ContactInfoActivity, "Chat ${if(accion=="vaciar") "vaciado" else "eliminado"}", Toast.LENGTH_SHORT).show()
                        if (accion == "eliminar") {
                            setResult(RESULT_OK, Intent().putExtra("ACTION", "DELETE"))
                            finish()
                        } else {
                            cargarArchivosCompartidos() // Refresca la vista si se vacía
                            setResult(RESULT_OK, Intent().putExtra("ACTION", "EMPTY"))
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}