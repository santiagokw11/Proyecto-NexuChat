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
        // Preparo el diseño visual de esta pantalla usando ViewBinding.
        binding = ActivityChatsListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configuro mi adaptador para la lista de chats. 
        // Si alguien toca un chat, abriré ChatActivity mandando el ID de esa persona.
        adapter = ChatListAdapter { endpointId ->
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("TARGET_ENDPOINT", endpointId)
            intent.putExtra("EXTRA_USER_NAME", endpointId)
            startActivity(intent)
        }

        // Le digo a la lista que muestre los elementos uno debajo del otro, y le pego el adaptador.
        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = adapter

        // Marco la pestaña "Chats" como seleccionada en la barra de navegación de abajo.
        binding.bottomNavigation.selectedItemId = R.id.nav_chats
        
        // Aquí configuro la barra de abajo para que cuando toque otro botón, me mueva a esa pantalla.
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
        // Cuando vuelvo a esta pantalla, me aseguro de que el botón "Chats" de abajo siga iluminado.
        binding.bottomNavigation.selectedItemId = R.id.nav_chats
        
        // Uso una corrutina para buscar en segundo plano los últimos chats que tuve en mi base de datos.
        lifecycleScope.launch(Dispatchers.IO) {
            val recentChats = AppDatabase.getDatabase(this@ChatsListActivity).nexuDao().getRecentChats()
            
            // Cuando la base de datos me devuelve los chats, le digo a mi adaptador que los dibuje en la pantalla.
            withContext(Dispatchers.Main) { adapter.submitList(recentChats) }
        }
    }
}