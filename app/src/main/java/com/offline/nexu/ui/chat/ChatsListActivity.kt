package com.offline.nexu.ui.chat

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.databinding.ActivityChatsListBinding
import com.offline.nexu.ui.home.HomeActivity
import com.offline.nexu.ui.home.P2PManager
import com.offline.nexu.ui.home.GlobalNotifications
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
    val isMuted: Boolean = false,
    val unreadCount: Int = 0,
    val forceUnread: Boolean = false,
    val isOnline: Boolean = false
)

class ChatsListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatsListBinding
    private lateinit var adapter: ChatListAdapter
    private lateinit var database: AppDatabase
    private lateinit var prefs: Prefs
    private lateinit var myLocalUserId: String

    private var activeChatsTracker: List<ChatItemUI> = emptyList()
    private var selectedChat: ChatItemUI? = null

    private val badgeListener = { runOnUiThread { updateBellBadge() } }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.offline.nexu.ACTION_UPDATE_CHAT_LIST") {
                cargarChats()
            }
        }
    }

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

        binding.btnSearch.setOnClickListener {
            if (binding.etSearch.visibility == View.VISIBLE) {
                binding.etSearch.visibility = View.GONE
                binding.etSearch.text.clear()
            } else {
                binding.etSearch.visibility = View.VISIBLE
                binding.etSearch.requestFocus()
            }
        }

        val ivBell = findViewById<ImageView>(R.id.ivBell)
        ivBell?.setOnClickListener { toggleNotificationsMenu() }

        binding.rvChats.setOnTouchListener { _, _ ->
            val dropdown = findViewById<View>(R.id.cvNotificationsDropdown)
            if (dropdown?.visibility == View.VISIBLE) {
                toggleNotificationsMenu()
            }
            false
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filtrarChats(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.fabNewChat.setOnClickListener {
            mostrarMenuNuevoChat()
        }

        binding.btnCloseSelection.setOnClickListener {
            adapter.clearSelection()
        }

        binding.btnDeleteSelected.setOnClickListener {
            eliminarChatsSeleccionados()
        }

        binding.btnPinSelected.setOnClickListener {
            fijarChatsSeleccionados()
        }

        configurarMenuLateral()
        iniciarLimpiezaEfimeros()
        iniciarMonitorConexion()
    }

    private fun updateBellBadge() {
        val redDot = findViewById<View>(R.id.vRedDot)
        val dropdown = findViewById<View>(R.id.cvNotificationsDropdown)
        redDot?.visibility = if (GlobalNotifications.pendingNotifications.isNotEmpty()) View.VISIBLE else View.GONE
        if (dropdown?.visibility == View.VISIBLE) {
            refreshNotificationsDropdown()
        }
    }

    private fun toggleNotificationsMenu() {
        val dropdown = findViewById<View>(R.id.cvNotificationsDropdown) ?: return
        if (dropdown.visibility == View.VISIBLE) {
            dropdown.animate()
                .alpha(0f)
                .translationY(-20f)
                .setDuration(150)
                .withEndAction { dropdown.visibility = View.GONE }
                .start()
        } else {
            refreshNotificationsDropdown()
            dropdown.alpha = 0f
            dropdown.translationY = -20f
            dropdown.visibility = View.VISIBLE
            dropdown.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(150)
                .start()
        }
    }

    private fun refreshNotificationsDropdown() {
        val llNotificationList = findViewById<LinearLayout>(R.id.llNotificationList) ?: return
        val tvEmptyNotifications = findViewById<TextView>(R.id.tvEmptyNotifications) ?: return

        llNotificationList.removeAllViews()

        if (GlobalNotifications.pendingNotifications.isEmpty()) {
            tvEmptyNotifications.visibility = View.VISIBLE
        } else {
            tvEmptyNotifications.visibility = View.GONE
            val iterator = GlobalNotifications.pendingNotifications.toList()
            val auraColor = ThemeUtils.getAuraColor(this)
            val textColorPrimary = ContextCompat.getColor(this, R.color.text_primary)
            val textColorSecondary = ContextCompat.getColor(this, R.color.text_secondary)

            for (notif in iterator) {
                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 0, 0, 32) }
                }

                val infoLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val emojiText = TextView(this).apply {
                    text = notif.emoji
                    textSize = 28f
                    setPadding(0, 0, 24, 0)
                }

                val textCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val nameText = TextView(this).apply {
                    text = notif.nombre
                    setTextColor(textColorPrimary)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    textSize = 16f
                }

                val descText = TextView(this).apply {
                    text = if (notif.isIncoming) "Quiere conectarse contigo" else "Detectado en la red"
                    setTextColor(textColorSecondary)
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 4 }
                }

                textCol.addView(nameText)
                textCol.addView(descText)
                infoLayout.addView(emojiText)
                infoLayout.addView(textCol)

                val actionLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 24, 0, 0)
                }

                val btnReject = Button(this).apply {
                    text = "Ignorar"
                    backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4A4A4A"))
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 16, 0)
                    }
                    setOnClickListener {
                        if (notif.isIncoming) {
                            Nearby.getConnectionsClient(applicationContext).rejectConnection(notif.endpointId)
                            val currentCount = GlobalNotifications.rejectedConnectionsCount[notif.endpointId] ?: 0
                            GlobalNotifications.rejectedConnectionsCount[notif.endpointId] = currentCount + 1
                        }
                        removerNotificacionYPopup(notif)
                    }
                }

                val btnAccept = Button(this).apply {
                    text = if (notif.isIncoming) "Aceptar" else "Conectar"
                    backgroundTintList = ColorStateList.valueOf(auraColor)
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    isAllCaps = false
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        if (notif.isIncoming) {
                            if (P2PManager.currentEndpointId == null) {
                                P2PManager.currentEndpointId = notif.endpointId
                                P2PManager.currentEndpointName = notif.nombre
                            }
                            Nearby.getConnectionsClient(applicationContext).acceptConnection(notif.endpointId, NexuMeshManager.getMeshPayloadCallback(applicationContext))
                        } else {
                            val profile = prefs.getUserProfile()
                            val deviceId = prefs.getDeviceId()
                            val avatarStr = profile.avatar
                            val displayEmoji = if (avatarStr.isNotEmpty()) avatarStr else "👤"
                            val miPerfilP2P = if (avatarStr.startsWith("/")) "🖼|${profile.name}|$deviceId" else "$displayEmoji|${profile.name}|$deviceId"

                            val basicCallback = object : ConnectionLifecycleCallback() {
                                override fun onConnectionInitiated(endId: String, info: ConnectionInfo) {
                                    Nearby.getConnectionsClient(applicationContext).acceptConnection(endId, NexuMeshManager.getMeshPayloadCallback(applicationContext))
                                }
                                override fun onConnectionResult(endId: String, res: ConnectionResolution) {
                                    if (res.status.isSuccess) {
                                        NexuMeshManager.connectedEndpoints.add(endId)
                                        P2PManager.connectedNames[endId] = notif.nombre
                                        if (P2PManager.currentEndpointId == null) {
                                            P2PManager.currentEndpointId = endId
                                            P2PManager.currentEndpointName = notif.nombre
                                        }
                                        Toast.makeText(this@ChatsListActivity, "Conexión establecida", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                override fun onDisconnected(endId: String) {}
                            }
                            Nearby.getConnectionsClient(applicationContext).requestConnection(miPerfilP2P, notif.endpointId, basicCallback)
                        }
                        removerNotificacionYPopup(notif)
                        toggleNotificationsMenu()
                    }
                }

                actionLayout.addView(btnReject)
                actionLayout.addView(btnAccept)

                itemLayout.addView(infoLayout)
                itemLayout.addView(actionLayout)

                if (iterator.indexOf(notif) < iterator.size - 1) {
                    val divider = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                            setMargins(0, 16, 0, 0)
                        }
                        setBackgroundColor(Color.parseColor("#33808080"))
                    }
                    itemLayout.addView(divider)
                }

                llNotificationList.addView(itemLayout)
            }
        }
    }

    private fun removerNotificacionYPopup(notif: GlobalNotifications.NotificationItem) {
        GlobalNotifications.pendingNotifications.remove(notif)
        GlobalNotifications.notifyBadgeUpdate()
    }

    private fun iniciarMonitorConexion() {
        lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                val snapshotTracker = activeChatsTracker.toList()
                if (snapshotTracker.isNotEmpty()) {
                    var hayCambios = false
                    for (chat in snapshotTracker) {
                        val currentOnline = !chat.entity.isGroup && (
                                P2PManager.connectedNames.containsKey(chat.entity.endpointId) ||
                                        P2PManager.connectedNames.containsValue(chat.entity.endpointId) ||
                                        NexuMeshManager.connectedEndpoints.contains(chat.entity.endpointId)
                                )
                        if (chat.isOnline != currentOnline) {
                            hayCambios = true
                            break
                        }
                    }
                    if (hayCambios) {
                        cargarChats()
                    }
                }
                delay(2000)
            }
        }
    }

    private fun actualizarModoSeleccion(isSelectionMode: Boolean, count: Int) {
        if (isSelectionMode) {
            binding.llNormalHeader.visibility = View.GONE
            binding.llSelectionHeader.visibility = View.VISIBLE
            binding.tvSelectionCount.text = count.toString()
            binding.fabNewChat.visibility = View.GONE
            cerrarMenuLateral()
        } else {
            binding.llNormalHeader.visibility = View.VISIBLE
            binding.llSelectionHeader.visibility = View.GONE
            binding.fabNewChat.visibility = View.VISIBLE
        }
    }

    private fun eliminarChatsSeleccionados() {
        val selectedIds = adapter.selectedChats.toList()
        if (selectedIds.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle("Eliminar Chats")
            .setMessage("¿Estás seguro de eliminar ${selectedIds.size} chat(s)?")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    selectedIds.forEach { endpointId ->
                        database.nexuDao().deleteAllMessagesFrom(endpointId, myLocalUserId)
                        val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
                        sharedPreferences.edit().remove("pinned_${myLocalUserId}_${endpointId}").apply()
                        sharedPreferences.edit().remove("muted_until_${myLocalUserId}_${endpointId}").apply()
                        sharedPreferences.edit().remove("force_unread_${myLocalUserId}_${endpointId}").apply()
                    }
                    withContext(Dispatchers.Main) {
                        adapter.clearSelection()
                        cargarChats()
                        Toast.makeText(this@ChatsListActivity, "Chats eliminados", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun fijarChatsSeleccionados() {
        val selectedIds = adapter.selectedChats.toList()
        if (selectedIds.isEmpty()) return

        val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)

        selectedIds.forEach { endpointId ->
            val isPinned = sharedPreferences.getBoolean("pinned_${myLocalUserId}_${endpointId}", false)
            sharedPreferences.edit().putBoolean("pinned_${myLocalUserId}_${endpointId}", !isPinned).apply()
        }

        adapter.clearSelection()
        cargarChats()
        Toast.makeText(this, "Estado fijado actualizado", Toast.LENGTH_SHORT).show()
    }

    private fun mostrarMenuNuevoChat() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_new_chat_menu, null)
        bottomSheet.setContentView(view)

        val auraColor = ThemeUtils.getAuraColor(this)
        view.findViewById<ImageView>(R.id.ivIconUser).setColorFilter(auraColor)
        view.findViewById<ImageView>(R.id.ivIconGroup).setColorFilter(auraColor)

        view.findViewById<View>(R.id.btnNewConversation).setOnClickListener {
            bottomSheet.dismiss()
            mostrarDialogoNuevaConversacion()
        }

        view.findViewById<View>(R.id.btnCreateGroup).setOnClickListener {
            bottomSheet.dismiss()
            startActivity(Intent(this, SelectMembersActivity::class.java))
        }

        (view.parent as View).setBackgroundColor(Color.TRANSPARENT)
        bottomSheet.show()
    }

    private fun mostrarDialogoNuevaConversacion() {
        val names = P2PManager.connectedNames.values.toList()
        val keys = P2PManager.connectedNames.keys.toList()

        if (names.isEmpty()) {
            Toast.makeText(this, "No hay personas conectadas actualmente", Toast.LENGTH_SHORT).show()
            return
        }

        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_select_user, null)
        bottomSheet.setContentView(view)

        val llUserList = view.findViewById<LinearLayout>(R.id.llUserList)
        val auraColor = ThemeUtils.getAuraColor(this)

        for (i in names.indices) {
            val endpointId = keys[i]
            val userName = names[i]

            val userView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding(60, 24, 60, 24)
                background = ContextCompat.getDrawable(context, ThemeUtils.getSelectableItemBackground(context))
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    bottomSheet.dismiss()
                    val intent = Intent(this@ChatsListActivity, ChatActivity::class.java)
                    intent.putExtra("TARGET_ENDPOINT", endpointId)
                    intent.putExtra("EXTRA_USER_NAME", userName)
                    intent.putExtra("EXTRA_IS_GROUP", false)
                    startActivity(intent)
                }
            }

            val avatarCard = CardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(120, 120)
                radius = 60f
                setCardBackgroundColor(auraColor)
                cardElevation = 0f
            }

            val avatarText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                gravity = Gravity.CENTER
                text = userName.take(1).uppercase()
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
            }
            avatarCard.addView(avatarText)

            val tvName = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = 40
                }
                text = userName
                setTextColor(Color.WHITE)
                textSize = 18f
            }

            userView.addView(avatarCard)
            userView.addView(tvName)
            llUserList.addView(userView)
        }

        (view.parent as View).setBackgroundColor(Color.TRANSPARENT)
        bottomSheet.show()
    }

    private fun filtrarChats(query: String) {
        if (query.isEmpty()) {
            adapter.submitList(activeChatsTracker)
        } else {
            val lowerQuery = query.lowercase()
            val filtrados = activeChatsTracker.filter {
                it.displayTitle.lowercase().contains(lowerQuery) ||
                        (it.entity.text?.lowercase()?.contains(lowerQuery) == true)
            }
            adapter.submitList(filtrados)
        }
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
                            val myName = prefs.getUserProfile().name
                            val cmd = "SYS:ADD_TIME:$groupId:$newExpirationTime"
                            NexuMeshManager.enviarMensajeGrupoMesh(applicationContext, myLocalUserId, myName, groupId, System.currentTimeMillis(), "TXT:0:$cmd")

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

        binding.btnMenuToggleRead?.setOnClickListener {
            val chat = selectedChat ?: return@setOnClickListener
            val endpointId = chat.entity.endpointId

            lifecycleScope.launch(Dispatchers.IO) {
                val sharedPrefs = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
                val isForceUnread = sharedPrefs.getBoolean("force_unread_${myLocalUserId}_${endpointId}", false)
                val unreadCount = database.nexuDao().getUnreadCount(endpointId, myLocalUserId)

                if (unreadCount > 0 || isForceUnread) {
                    database.nexuDao().markAsRead(endpointId, myLocalUserId)
                    sharedPrefs.edit().putBoolean("force_unread_${myLocalUserId}_${endpointId}", false).apply()
                } else {
                    sharedPrefs.edit().putBoolean("force_unread_${myLocalUserId}_${endpointId}", true).apply()
                }

                withContext(Dispatchers.Main) {
                    cargarChats()
                    cerrarMenuLateral()
                }
            }
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
                val view = layoutInflater.inflate(R.layout.dialog_mute_notifications, null)
                val dialog = AlertDialog.Builder(this)
                    .setView(view)
                    .create()

                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                val rgOptions = view.findViewById<android.widget.RadioGroup>(R.id.rgMuteOptions)
                val btnCancel = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCancelMute)
                val btnConfirm = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnConfirmMute)
                val tvTitle = view.findViewById<TextView>(R.id.tvMuteTitle)
                val ivIcon = view.findViewById<android.widget.ImageView>(R.id.ivMuteIcon)

                val auraColor = ThemeUtils.getAuraColor(this)
                tvTitle.setTextColor(auraColor)
                ivIcon.setColorFilter(auraColor)
                btnConfirm.setTextColor(auraColor)

                val colorStateList = ColorStateList(
                    arrayOf(
                        intArrayOf(-android.R.attr.state_checked),
                        intArrayOf(android.R.attr.state_checked)
                    ),
                    intArrayOf(
                        ContextCompat.getColor(this, android.R.color.darker_gray),
                        auraColor
                    )
                )

                view.findViewById<android.widget.RadioButton>(R.id.rb8Hours).buttonTintList = colorStateList
                view.findViewById<android.widget.RadioButton>(R.id.rb1Week).buttonTintList = colorStateList
                view.findViewById<android.widget.RadioButton>(R.id.rbForever).buttonTintList = colorStateList

                btnCancel.setOnClickListener { dialog.dismiss() }

                btnConfirm.setOnClickListener {
                    val duration = when (rgOptions.checkedRadioButtonId) {
                        R.id.rb8Hours -> System.currentTimeMillis() + (8 * 60 * 60 * 1000L)
                        R.id.rb1Week -> System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)
                        R.id.rbForever -> -1L
                        else -> -1L
                    }

                    sharedPreferences.edit().putLong("muted_until_${myLocalUserId}_${endpointId}", duration).apply()
                    Toast.makeText(this, "Chat silenciado", Toast.LENGTH_SHORT).show()
                    cargarChats()
                    cerrarMenuLateral()
                    dialog.dismiss()
                }

                dialog.show()
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
                    text = "",
                    imagePath = null,
                    isMine = false,
                    status = -1,
                    timestamp = System.currentTimeMillis()
                )
                database.nexuDao().insertMessage(dummyMsg)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChatsListActivity, "Historial vaciado", Toast.LENGTH_SHORT).show()
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
                sharedPreferences.edit().remove("force_unread_${myLocalUserId}_${endpointId}").apply()
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

        if (item.isMuted) {
            binding.tvMenuMute.text = "Sonido"
            binding.ivMenuMute.setImageResource(R.drawable.ic_menu_unmute)
        } else {
            binding.tvMenuMute.text = "Silenciar"
            binding.ivMenuMute.setImageResource(R.drawable.ic_menu_mute)
        }

        if (item.unreadCount > 0 || item.forceUnread) {
            binding.tvMenuToggleRead?.text = "Leído"
            binding.ivMenuToggleRead?.setImageResource(android.R.drawable.ic_menu_view)
        } else {
            binding.tvMenuToggleRead?.text = "No leído"
            binding.ivMenuToggleRead?.setImageResource(android.R.drawable.ic_menu_recent_history)
        }

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
                    if (!adapter.isSelectionMode) {
                        binding.fabNewChat.visibility = View.VISIBLE
                    }
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
                val currentTracker = activeChatsTracker.toList()

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
        binding.btnPinSelected.setColorFilter(auraColor)
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
                    lifecycleScope.launch(Dispatchers.IO) {
                        val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
                        sharedPreferences.edit().putBoolean("force_unread_${myLocalUserId}_${item.entity.endpointId}", false).apply()
                        database.nexuDao().markAsRead(item.entity.endpointId, myLocalUserId)
                    }

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
            },
            onSelectionModeChanged = { isSelectionMode, count ->
                actualizarModoSeleccion(isSelectionMode, count)
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

        GlobalNotifications.addListener(badgeListener)
        updateBellBadge()

        val filter = IntentFilter("com.offline.nexu.ACTION_UPDATE_CHAT_LIST")
        ContextCompat.registerReceiver(this, updateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        if (P2PManager.currentActivity == this) P2PManager.currentActivity = null

        GlobalNotifications.removeListener(badgeListener)
        unregisterReceiver(updateReceiver)
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

                val mutedUntil = sharedPreferences.getLong("muted_until_${myLocalUserId}_${msg.endpointId}", 0L)
                val isMuted = mutedUntil == -1L || mutedUntil > currentTime

                val unreadCount = database.nexuDao().getUnreadCount(msg.endpointId, myLocalUserId)
                val forceUnread = sharedPreferences.getBoolean("force_unread_${myLocalUserId}_${msg.endpointId}", false)

                val isOnline = !msg.isGroup && (
                        P2PManager.connectedNames.containsKey(msg.endpointId) ||
                                P2PManager.connectedNames.containsValue(msg.endpointId) ||
                                NexuMeshManager.connectedEndpoints.contains(msg.endpointId)
                        )

                ChatItemUI(msg, isPinned, displayTitle, avatar, group?.expirationTime, isMuted, unreadCount, forceUnread, isOnline)
            }.sortedWith(compareByDescending<ChatItemUI> { it.isPinned }.thenByDescending { it.entity.timestamp })

            withContext(Dispatchers.Main) {
                activeChatsTracker = uiList

                if (binding.etSearch.visibility == View.VISIBLE && binding.etSearch.text.isNotEmpty()) {
                    filtrarChats(binding.etSearch.text.toString())
                } else {
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