package com.offline.nexu.ui.chat

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.offline.nexu.R
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.databinding.ActivityChatsListBinding
import com.offline.nexu.ui.home.HomeActivity
import com.offline.nexu.ui.profile.ProfileActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatsListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatsListBinding
    private lateinit var adapter: ChatListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Inflo la vista de esta pantalla con ViewBinding porque ya estamos grandes para andar usando findViewById en todos lados.
        binding = ActivityChatsListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Preparo mi adaptador para la lista de chats. 
        // Si el usuario le da tap a algún chat viejo, lo mando de cabeza a la pantalla de ChatActivity con el ID del loco.
        adapter = ChatListAdapter { endpointId ->
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("TARGET_ENDPOINT", endpointId)
            intent.putExtra("EXTRA_USER_NAME", endpointId)
            startActivity(intent)
        }

        // Le digo al RecyclerView que ponga los ítems en filita, uno abajo del otro, y le enchufo el adaptador.
        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = adapter

        // Prendo la lucecita del botón de "Chats" en la barrita de abajo para que el usuario no se pierda.
        binding.bottomNavigation.selectedItemId = R.id.nav_chats
        
        // Hago andar los botoncitos de abajo. 
        // Si toca el radar o el perfil, lanzo el intent. 
        // Le clavo el CLEAR_TOP para no armar una torre infinita de pantallas en memoria que me coma toda la RAM.
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_radar -> { startActivity(Intent(this, HomeActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }); overridePendingTransition(0, 0); true }
                R.id.nav_profile -> { startActivity(Intent(this, ProfileActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }); overridePendingTransition(0, 0); true }
                R.id.nav_chats -> true
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Por las dudas, cada vez que vuelvo a esta pantalla, me aseguro de que el botón de "Chats" de abajo siga iluminado.
        binding.bottomNavigation.selectedItemId = R.id.nav_chats
        
        // Mando una corrutina a la DB (IO) porque Android me tira bronca si busco los mensajes desde el hilo principal.
        lifecycleScope.launch(Dispatchers.IO) {
            val recentChats = AppDatabase.getDatabase(this@ChatsListActivity).nexuDao().getRecentChats()
            
            // Vuelvo al hilo Main y le clavo la lista de chats que encontré al adapter para que los dibuje posta.
            withContext(Dispatchers.Main) { adapter.submitList(recentChats) }
        }
    }
}