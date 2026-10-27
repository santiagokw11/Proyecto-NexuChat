package com.offline.nexu.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.MessageEntity
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
    private lateinit var database: AppDatabase
    private lateinit var prefs: Prefs
    private lateinit var myLocalUserId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatsListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)
        prefs = Prefs(this)
        myLocalUserId = prefs.getUserProfile().id

        setupBottomNavigation()

        val auraColor = ThemeUtils.getAuraColor(this)
        adapter = ChatListAdapter(
            auraColor = auraColor,
            onChatClick = { endpointId ->
                val intent = Intent(this, ChatActivity::class.java)
                intent.putExtra("EXTRA_USER_NAME", endpointId)
                startActivity(intent)
            },
            onAvatarClick = { endpointId ->
                val intent = Intent(this, ContactInfoActivity::class.java)
                intent.putExtra("EXTRA_USER_NAME", endpointId)
                startActivity(intent)
            },
            onChatLongClick = { endpointId ->
                mostrarOpcionesChat(endpointId)
            }
        )

        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = adapter
    }

    private fun aplicarColorAura() {
        val auraColor = ThemeUtils.getAuraColor(this)

        val navColors = ThemeUtils.getBottomNavColorStateList(auraColor)
        binding.bottomNavigation.itemIconTintList = navColors
        binding.bottomNavigation.itemTextColor = navColors

        binding.tvTitle.setTextColor(auraColor)
        findViewById<TextView>(R.id.tvEmptyState)?.setTextColor(auraColor)
    }

    override fun onResume() {
        super.onResume()
        binding.bottomNavigation.selectedItemId = R.id.nav_chats
        aplicarColorAura()
        cargarChats()
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0)
    }

    private fun isChatPinned(endpointId: String): Boolean {
        val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean("pinned_${myLocalUserId}_${endpointId}", false)
    }

    private fun cargarChats() {
        lifecycleScope.launch(Dispatchers.IO) {
            val recentMessages = database.nexuDao().getRecentChats(myLocalUserId)

            val uiList = recentMessages.map { ChatItemUI(it, isChatPinned(it.endpointId)) }
                .sortedWith(compareByDescending<ChatItemUI> { it.isPinned }.thenByDescending { it.entity.timestamp })

            withContext(Dispatchers.Main) {
                val emptyState = findViewById<TextView>(R.id.tvEmptyState)

                if (uiList.isEmpty()) {
                    binding.rvChats.visibility = View.GONE
                    emptyState?.visibility = View.VISIBLE
                } else {
                    binding.rvChats.visibility = View.VISIBLE
                    emptyState?.visibility = View.GONE
                    adapter.submitList(uiList)
                }
            }
        }
    }

    private fun mostrarOpcionesChat(endpointId: String) {
        val dialog = android.app.Dialog(this)
        dialog.setContentView(R.layout.dialog_chat_options)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val auraColor = ThemeUtils.getAuraColor(this)
        dialog.findViewById<TextView>(R.id.tvOptionsTitle).setTextColor(auraColor)

        val isPinned = isChatPinned(endpointId)
        val btnPinChat = dialog.findViewById<MaterialButton>(R.id.btnPinChat)
        btnPinChat.text = if (isPinned) "📌 Desfijar Chat" else "📌 Fijar Chat"

        btnPinChat.setOnClickListener {
            val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
            sharedPreferences.edit().putBoolean("pinned_${myLocalUserId}_${endpointId}", !isPinned).apply()
            cargarChats()
            dialog.dismiss()
        }

        dialog.findViewById<View>(R.id.btnClearChat).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                database.nexuDao().deleteAllMessagesFrom(endpointId, myLocalUserId)
                val dummyMsg = MessageEntity(
                    System.currentTimeMillis(), myLocalUserId, endpointId,
                    "🧹 Historial vaciado", null, false, 3, System.currentTimeMillis()
                )
                database.nexuDao().insertMessage(dummyMsg)
                withContext(Dispatchers.Main) { cargarChats() }
            }
            dialog.dismiss()
        }

        dialog.findViewById<View>(R.id.btnDeleteChat).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                database.nexuDao().deleteAllMessagesFrom(endpointId, myLocalUserId)
                val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
                sharedPreferences.edit().remove("pinned_${myLocalUserId}_${endpointId}").apply()
                withContext(Dispatchers.Main) { cargarChats() }
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_radar -> {
                    val intent = Intent(this, HomeActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                R.id.nav_chats -> true
                R.id.nav_profile -> {
                    val intent = Intent(this, ProfileActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    startActivity(intent)
                    overridePendingTransition(0, 0)
                    true
                }
                else -> false
            }
        }
    }
}