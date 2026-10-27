package com.offline.nexu.ui.chat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.databinding.ActivityContactInfoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactInfoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityContactInfoBinding
    private var contactName: String = ""
    private lateinit var db: AppDatabase
    private lateinit var myLocalUserId: String // NUEVO: Para saber quién es el dueño

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getDatabase(this)
        myLocalUserId = Prefs(this).getUserProfile().id // Obtenemos el ID de sesión

        contactName = intent.getStringExtra("EXTRA_USER_NAME") ?: "Desconocido"

        binding.tvContactName.text = contactName
        binding.tvContactId.text = "Conectado | $contactName"

        cargarArchivosCompartidos()

        binding.btnBack.setOnClickListener { finish() }

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
            // PASAMOS myLocalUserId A LA CONSULTA
            val imagenes = db.nexuDao().getSharedImages(contactName, myLocalUserId)

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
                    // PASAMOS myLocalUserId PARA BORRAR SOLO LOS CHATS DE ESTA SESIÓN
                    db.nexuDao().deleteAllMessagesFrom(contactName, myLocalUserId)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ContactInfoActivity, "Chat ${if(accion=="vaciar") "vaciado" else "eliminado"}", Toast.LENGTH_SHORT).show()
                        if (accion == "eliminar") {
                            setResult(RESULT_OK, Intent().putExtra("ACTION", "DELETE"))
                            finish()
                        } else {
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