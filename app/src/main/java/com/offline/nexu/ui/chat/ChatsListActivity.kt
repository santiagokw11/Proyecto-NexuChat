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
import com.offline.nexu.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatsListActivity : AppCompatActivity() {
    private lateinit var binding: ActivityChatsListBinding
    private lateinit var adapter: ChatListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatsListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- APLICAR COLOR DE AURA ---
        val auraColor = ThemeUtils.getAuraColor(this)

        // Pintar el título "Chats Recientes"
        binding.tvTitle.setTextColor(auraColor)

        // Pintar la barra de navegación inferior
        val navColors = ThemeUtils.getBottomNavColorStateList(auraColor)
        binding.bottomNavigation.itemIconTintList = navColors
        binding.bottomNavigation.itemTextColor = navColors
        // ------------------------------

        adapter = ChatListAdapter { endpointId ->
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("TARGET_ENDPOINT", endpointId)
            intent.putExtra("EXTRA_USER_NAME", endpointId)
            startActivity(intent)
        }

        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = adapter

        binding.bottomNavigation.selectedItemId = R.id.nav_chats
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_radar -> {
                    val intent = Intent(this, HomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    }
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_profile -> {
                    val intent = Intent(this, ProfileActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    }
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_chats -> true
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        binding.bottomNavigation.selectedItemId = R.id.nav_chats
        lifecycleScope.launch(Dispatchers.IO) {
            val recentChats = AppDatabase.getDatabase(this@ChatsListActivity).nexuDao().getRecentChats()
            withContext(Dispatchers.Main) { adapter.submitList(recentChats) }
        }
    }
}