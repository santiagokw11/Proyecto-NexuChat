package com.offline.nexu.ui.chat

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.databinding.ActivityChatsListBinding
import com.offline.nexu.ui.home.HomeActivity
import com.offline.nexu.ui.home.P2PManager
import com.offline.nexu.ui.profile.ProfileActivity
import com.offline.nexu.data.network.NexuMeshManager
import com.offline.nexu.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatItemUI(
    val entity: MessageEntity,
    val isPinned: Boolean,
    val displayTitle: String,
    val avatar: String,
    val groupExpirationTime: Long? = null,
    val isMuted: Boolean = false // NUEVO: Estado de silenciado para la campanita
)

class ChatsListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatsListBinding
    private lateinit var adapter: ChatListAdapter
    private lateinit var database: AppDatabase
    private lateinit var prefs: Prefs
    private lateinit var myLocalUserId: String

    private var activeChatsTracker: List<ChatItemUI> = emptyList()
    private var selectedChat: ChatItemUI? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatsListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)
        prefs = Prefs(this)
        myLocalUserId = prefs.getUserProfile().id

        setupBottomNavigation()
        aplicarColorAura()

        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = adapter

        binding.fabNewChat.setOnClickListener {
            val intent = Intent(this, SelectMembersActivity::class.java)
            startActivity(intent)
        }

        configurarMenuLateral()
        iniciarLimpiezaEfimeros()
    }

    private fun configurarMenuLateral() {
        binding.btnMenuClose.setOnClickListener {
            cerrarMenuLateral()
        }

        binding.btnMenuAddTime.setOnClickListener {
            val chat = selectedChat ?: return@setOnClickListener
            val groupId = chat.entity.endpointId

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 40, 60, 20)
            }
            val inputLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
            }
            val etValue = EditText(this).apply {
                hint = "Cantidad"
                inputType = InputType.TYPE_CLASS_NUMBER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val spinnerUnit = Spinner(this).apply {
                adapter = ArrayAdapter(this@ChatsListActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Segundos", "Minutos", "Horas", "Días"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            inputLayout.addView(etValue)
            inputLayout.addView(spinnerUnit)
            layout.addView(inputLayout)

            AlertDialog.Builder(this)
                .setTitle("Añadir tiempo al grupo")
                .setView(layout)
                .setPositiveButton("Agregar") { _, _ ->
                    val valStr = etValue.text.toString().trim()
                    val value = valStr.toLongOrNull() ?: 0L
                    if (value > 0) {
                        val multiplier = when (spinnerUnit.selectedItemPosition) {
                            0 -> 1000L
                            1 -> 60 * 1000L
                            2 -> 60 * 60 * 1000L
                            3 -> 24 * 60 * 60 * 1000L
                            else -> 1000L
                        }
                        val addedTime = value * multiplier
                        val newExpirationTime = chat.groupExpirationTime!! + addedTime

                        lifecycleScope.launch(Dispatchers.IO) {
                            database.nexuDao().updateGroupExpiration(groupId, newExpirationTime)
                            val cmd = "SYS:ADD_TIME:$groupId:$newExpirationTime"
                            NexuMeshManager.enviarMensajeGrupoMesh(applicationContext, myLocalUserId, groupId, System.currentTimeMillis(), "TXT:0:$cmd")

                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ChatsListActivity, "Tiempo extra añadido", Toast.LENGTH_SHORT).show()
                                cargarChats()
                                cerrarMenuLateral()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        binding.btnMenuPin.setOnClickListener {
            val chat = selectedChat ?: return@setOnClickListener
            val endpointId = chat.entity.endpointId
            val isPinned = isChatPinned(endpointId)
            val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
            sharedPreferences.edit().putBoolean("pinned_${myLocalUserId}_${endpointId}", !isPinned).apply()
            cargarChats()
            cerrarMenuLateral()
        }

        binding.btnMenuMute.setOnClickListener {
            val chat = selectedChat ?: return@setOnClickListener
            val endpointId = chat.entity.endpointId
            val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
            val mutedUntil = sharedPreferences.getLong("muted_until_${myLocalUserId}_${endpointId}", 0L)

            val isCurrentlyMuted = mutedUntil == -1L || mutedUntil > System.currentTimeMillis()

            if (isCurrentlyMuted) {
                sharedPreferences.edit().putLong("muted_until_${myLocalUserId}_${endpointId}", 0L).apply()
                Toast.makeText(this, "Sonido activado", Toast.LENGTH_SHORT).show()
                cargarChats()
                cerrarMenuLateral()
            } else {
                val options = arrayOf("8 horas", "1 semana", "Para siempre")
                AlertDialog.Builder(this)
                    .setTitle("Silenciar notificaciones")
                    .setItems(options) { _, which ->
                        val duration = when (which) {
                            0 -> System.currentTimeMillis() + (8 * 60 * 60 * 1000L) // 8 horas
                            1 -> System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L) // 1 semana
                            else -> -1L // Para siempre
                        }
                        sharedPreferences.edit().putLong("muted_until_${myLocalUserId}_${endpointId}", duration).apply()
                        Toast.makeText(this, "Chat silenciado", Toast.LENGTH_SHORT).show()
                        cargarChats()
                        cerrarMenuLateral()
                    }
                    .show()
            }
        }

        binding.btnMenuClear.setOnClickListener {
            val chat = selectedChat ?: return@setOnClickListener
            val endpointId = chat.entity.endpointId

            lifecycleScope.launch(Dispatchers.IO) {
                database.nexuDao().deleteAllMessagesFrom(endpointId, myLocalUserId)
                val dummyMsg = MessageEntity(
                    msgId = System.currentTimeMillis(),
                    localUserId = myLocalUserId,
                    endpointId = endpointId,
                    originalSender = endpointId,
                    isGroup = chat.entity.isGroup,
                    text = "Historial vaciado",
                    imagePath = null,
                    isMine = false,
                    status = 3,
                    timestamp = System.currentTimeMillis()
                )
                database.nexuDao().insertMessage(dummyMsg)
                withContext(Dispatchers.Main) {
                    cargarChats()
                    cerrarMenuLateral()
                }
            }
        }

        binding.btnMenuDelete.setOnClickListener {
            val chat = selectedChat ?: return@setOnClickListener
            val endpointId = chat.entity.endpointId

            lifecycleScope.launch(Dispatchers.IO) {
                database.nexuDao().deleteAllMessagesFrom(endpointId, myLocalUserId)
                val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
                sharedPreferences.edit().remove("pinned_${myLocalUserId}_${endpointId}").apply()
                sharedPreferences.edit().remove("muted_until_${myLocalUserId}_${endpointId}").apply()
                withContext(Dispatchers.Main) {
                    cargarChats()
                    cerrarMenuLateral()
                }
            }
        }
    }

    private fun abrirMenuLateral(item: ChatItemUI) {
        selectedChat = item
        adapter.setSelectedChat(item.entity.endpointId)

        val isPinned = isChatPinned(item.entity.endpointId)
        binding.tvMenuPin.text = if (isPinned) "Desfijar" else "Fijar"

        binding.tvMenuMute.text = if (item.isMuted) "Sonido" else "Silenciar"

        binding.btnMenuAddTime.visibility = if (item.groupExpirationTime != null) View.VISIBLE else View.GONE

        val auraColor = ThemeUtils.getAuraColor(this)
        binding.btnMenuClose.backgroundTintList = ColorStateList.valueOf(auraColor)

        if (binding.cvLateralMenu.visibility != View.VISIBLE) {
            binding.fabNewChat.visibility = View.GONE
            binding.cvLateralMenu.apply {
                alpha = 0f
                visibility = View.VISIBLE
                translationX = 100f
                animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(200)
                    .start()
            }
        }
    }

    private fun cerrarMenuLateral() {
        if (binding.cvLateralMenu.visibility == View.VISIBLE) {
            binding.cvLateralMenu.animate()
                .alpha(0f)
                .translationX(100f)
                .setDuration(200)
                .withEndAction {
                    binding.cvLateralMenu.visibility = View.GONE
                    binding.fabNewChat.visibility = View.VISIBLE
                }.start()
        }
        selectedChat = null
        adapter.setSelectedChat(null)
    }

    private fun iniciarLimpiezaEfimeros() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val now = System.currentTimeMillis()

                try {
                    database.nexuDao().deleteExpiredMessages(now)
                    database.nexuDao().deleteMessagesOfExpiredGroups(now)
                    database.nexuDao().deleteExpiredGroups(now)
                } catch (e: Exception) { e.printStackTrace() }

                var needsRefresh = false
                var hasEphemeralGroups = false
                val currentTracker = activeChatsTracker

                for (item in currentTracker) {
                    if (item.groupExpirationTime != null) hasEphemeralGroups = true

                    if (item.entity.expiresAt != null && item.entity.expiresAt <= now) {
                        needsRefresh = true
                        break
                    }
                    if (item.groupExpirationTime != null && item.groupExpirationTime <= now) {
                        needsRefresh = true
                        break
                    }
                }

                if (needsRefresh) {
                    cargarChats()
                } else if (hasEphemeralGroups) {
                    withContext(Dispatchers.Main) {
                        adapter.notifyDataSetChanged()
                    }
                }
                delay(1000)
            }
        }
    }

    private fun aplicarColorAura() {
        val auraColor = ThemeUtils.getAuraColor(this)

        val navColors = ThemeUtils.getBottomNavColorStateList(auraColor)
        binding.bottomNavigation.itemIconTintList = navColors
        binding.bottomNavigation.itemTextColor = navColors

        binding.tvTitle.setTextColor(auraColor)
        findViewById<TextView>(R.id.tvEmptyState)?.setTextColor(auraColor)
        binding.fabNewChat.backgroundTintList = ColorStateList.valueOf(auraColor)

        adapter = ChatListAdapter(
            auraColor = auraColor,
            onChatClick = { item ->
                if (selectedChat != null) {
                    if (selectedChat?.entity?.endpointId == item.entity.endpointId) {
                        cerrarMenuLateral()
                    } else {
                        abrirMenuLateral(item)
                    }
                } else {
                    val intent = Intent(this, ChatActivity::class.java)
                    intent.putExtra("EXTRA_IS_GROUP", item.entity.isGroup)
                    if (item.entity.isGroup) {
                        intent.putExtra("EXTRA_GROUP_ID", item.entity.endpointId)
                        intent.putExtra("EXTRA_GROUP_NAME", item.displayTitle)
                    } else {
                        intent.putExtra("EXTRA_USER_NAME", item.entity.endpointId)
                    }
                    startActivity(intent)
                }
            },
            onAvatarClick = { item ->
                if (!item.entity.isGroup) {
                    val intent = Intent(this, ContactInfoActivity::class.java)
                    intent.putExtra("EXTRA_USER_NAME", item.entity.endpointId)
                    startActivity(intent)
                }
            },
            onChatLongClick = { item ->
                abrirMenuLateral(item)
            }
        )
        binding.rvChats.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        binding.bottomNavigation.selectedItemId = R.id.nav_chats
        P2PManager.currentActivity = this
        aplicarColorAura()
        cargarChats()
    }

    override fun onPause() {
        super.onPause()
        if (P2PManager.currentActivity == this) P2PManager.currentActivity = null
        overridePendingTransition(0, 0)
    }

    private fun isChatPinned(endpointId: String): Boolean {
        val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
        return sharedPreferences.getBoolean("pinned_${myLocalUserId}_${endpointId}", false)
    }

    private fun cargarChats() {
        lifecycleScope.launch(Dispatchers.IO) {
            val recentMessages = database.nexuDao().getRecentChats(myLocalUserId)
            val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
            val currentTime = System.currentTimeMillis()

            val uiList = recentMessages.map { msg ->
                val isPinned = isChatPinned(msg.endpointId)

                val group = if (msg.isGroup) database.nexuDao().getGroup(msg.endpointId) else null
                val displayTitle = if (msg.isGroup) group?.groupName ?: "Grupo" else msg.endpointId
                val avatar = if (msg.isGroup) group?.groupAvatar ?: "👥" else prefs.getContactAvatar(msg.endpointId)

                // NUEVO: Verificamos si está silenciado para pasarlo a la UI y mostrar la campanita
                val mutedUntil = sharedPreferences.getLong("muted_until_${myLocalUserId}_${msg.endpointId}", 0L)
                val isMuted = mutedUntil == -1L || mutedUntil > currentTime

                ChatItemUI(msg, isPinned, displayTitle, avatar, group?.expirationTime, isMuted)
            }.sortedWith(compareByDescending<ChatItemUI> { it.isPinned }.thenByDescending { it.entity.timestamp })

            withContext(Dispatchers.Main) {
                activeChatsTracker = uiList

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