package com.offline.nexu.ui.chat

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.GroupEntity
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.data.network.NexuMeshManager
import com.offline.nexu.ui.chat.group.GroupManager
import com.offline.nexu.ui.chat.helpers.AttachmentHelper
import com.offline.nexu.ui.home.P2PManager
import com.offline.nexu.utils.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*

class EditGroupActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var myLocalUserId: String
    private lateinit var groupId: String
    private lateinit var prefs: Prefs
    private var currentGroup: GroupEntity? = null
    private var membersList: MutableList<String> = mutableListOf()
    private var auraColor: Int = 0

    private val requestGalleryPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            AttachmentHelper.openCustomGallery(this, layoutInflater) { uri -> procesarUriAvatarGrupo(uri) }
        } else Toast.makeText(this, "Permiso denegado", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_group)

        database = AppDatabase.getDatabase(this)
        prefs = Prefs(this)
        myLocalUserId = prefs.getUserProfile().id
        groupId = intent.getStringExtra("EXTRA_GROUP_ID") ?: ""
        auraColor = ThemeUtils.getAuraColor(this)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        cargarInfoGrupo()

        findViewById<RelativeLayout>(R.id.btnViewFiles).setOnClickListener {
            val intent = Intent(this, MediaVaultActivity::class.java)
            intent.putExtra("EXTRA_USER_NAME", groupId)
            startActivity(intent)
        }

        findViewById<LinearLayout>(R.id.btnSearch).setOnClickListener {
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("EXTRA_GROUP_ID", groupId)
            intent.putExtra("EXTRA_IS_GROUP", true)
            intent.putExtra("EXTRA_SEARCH_MODE", true)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(intent)
            finish()
        }

        val sharedPreferences = getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)
        val mutedUntil = sharedPreferences.getLong("muted_until_${myLocalUserId}_${groupId}", 0L)
        val isMuted = mutedUntil == -1L || mutedUntil > System.currentTimeMillis()

        val tvGroupMuteText = findViewById<TextView>(R.id.tvGroupMuteText)
        val ivGroupMuteIcon = findViewById<ImageView>(R.id.ivGroupMuteIcon)

        if (isMuted) {
            tvGroupMuteText.text = "Activar notificaciones"
            ivGroupMuteIcon.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
        } else {
            tvGroupMuteText.text = "Silenciar grupo"
            ivGroupMuteIcon.setImageResource(android.R.drawable.ic_lock_silent_mode)
        }

        findViewById<LinearLayout>(R.id.btnGroupMute).setOnClickListener {
            if (isMuted) {
                sharedPreferences.edit().putLong("muted_until_${myLocalUserId}_${groupId}", 0L).apply()
                Toast.makeText(this, "Notificaciones activadas", Toast.LENGTH_SHORT).show()
                recreate()
            } else {
                val view = layoutInflater.inflate(R.layout.dialog_mute_notifications, null)
                val dialog = AlertDialog.Builder(this).setView(view).create()
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                view.findViewById<TextView>(R.id.tvMuteTitle).setTextColor(auraColor)
                view.findViewById<ImageView>(R.id.ivMuteIcon).setColorFilter(auraColor)
                val btnConfirm = view.findViewById<Button>(R.id.btnConfirmMute)
                btnConfirm.setTextColor(auraColor)
                val rgOptions = view.findViewById<android.widget.RadioGroup>(R.id.rgMuteOptions)

                view.findViewById<Button>(R.id.btnCancelMute).setOnClickListener { dialog.dismiss() }
                btnConfirm.setOnClickListener {
                    val duration = when (rgOptions.checkedRadioButtonId) {
                        R.id.rb8Hours -> System.currentTimeMillis() + (8 * 60 * 60 * 1000L)
                        R.id.rb1Week -> System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000L)
                        R.id.rbForever -> -1L
                        else -> -1L
                    }
                    sharedPreferences.edit().putLong("muted_until_${myLocalUserId}_${groupId}", duration).apply()
                    Toast.makeText(this, "Grupo silenciado", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    recreate()
                }
                dialog.show()
            }
        }

        findViewById<LinearLayout>(R.id.btnEmptyChat).setOnClickListener {
            mostrarDialogoConfirmacion(
                "Vaciar Chat",
                "¿Estás seguro de vaciar el historial del grupo? Solo se borrará para ti.",
                android.R.drawable.ic_menu_revert
            ) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val messages = database.nexuDao().getMessagesForUserSync(groupId, myLocalUserId)
                    borrarArchivosFisicos(messages)
                    database.nexuDao().deleteAllMessagesFrom(groupId, myLocalUserId)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EditGroupActivity, "Historial vaciado", Toast.LENGTH_SHORT).show()
                        recreate()
                    }
                }
            }
        }

        findViewById<LinearLayout>(R.id.llDeleteChat).setOnClickListener {
            mostrarDialogoConfirmacion(
                "Salir del Grupo",
                "¿Estás seguro de salir del grupo y eliminar el chat?",
                android.R.drawable.ic_menu_delete,
                isDestructive = true
            ) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val messages = database.nexuDao().getMessagesForUserSync(groupId, myLocalUserId)
                    borrarArchivosFisicos(messages)
                    database.nexuDao().deleteAllMessagesFrom(groupId, myLocalUserId)

                    sharedPreferences.edit().remove("pinned_${myLocalUserId}_${groupId}").apply()
                    sharedPreferences.edit().remove("muted_until_${myLocalUserId}_${groupId}").apply()

                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EditGroupActivity, "Has salido del grupo", Toast.LENGTH_SHORT).show()
                        val intent = Intent(this@EditGroupActivity, ChatsListActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        finish()
                    }
                }
            }
        }

        findViewById<LinearLayout>(R.id.btnAddMember).setOnClickListener {
            mostrarDialogoAñadirMiembros()
        }
    }

    private fun mostrarDialogoConfirmacion(titulo: String, mensaje: String, iconoResId: Int, isDestructive: Boolean = false, accionConfirmar: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_confirm_action, null)
        val dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.tvConfirmTitle).text = titulo
        view.findViewById<TextView>(R.id.tvConfirmMessage).text = mensaje
        val ivIcon = view.findViewById<ImageView>(R.id.ivConfirmIcon)
        ivIcon.setImageResource(iconoResId)

        val btnConfirm = view.findViewById<Button>(R.id.btnConfirmAction)

        if (isDestructive) {
            ivIcon.setColorFilter(Color.parseColor("#F44336"))
            btnConfirm.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
        } else {
            ivIcon.setColorFilter(auraColor)
            btnConfirm.backgroundTintList = ColorStateList.valueOf(auraColor)
        }

        view.findViewById<Button>(R.id.btnCancelAction).setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            accionConfirmar()
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun procesarUriAvatarGrupo(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val inputStream = contentResolver.openInputStream(uri) ?: return@launch
            val file = File(filesDir, "group_avatar_${UUID.randomUUID()}.jpg")
            val outputStream = FileOutputStream(file)
            try {
                inputStream.copyTo(outputStream)
                database.nexuDao().updateGroupAvatar(groupId, file.absolutePath)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditGroupActivity, "Imagen del grupo actualizada", Toast.LENGTH_SHORT).show()
                    cargarInfoGrupo()
                }
            } catch (e: Exception) {} finally {
                inputStream.close()
                outputStream.close()
            }
        }
    }

    private fun borrarArchivosFisicos(messages: List<MessageEntity>) {
        messages.forEach { msg ->
            if (!msg.imagePath.isNullOrEmpty()) {
                try {
                    val uri = Uri.parse(msg.imagePath)
                    val file = File(uri.path ?: return@forEach)
                    if (file.exists()) file.delete()
                } catch (e: Exception) {}
            }
        }
    }

    private fun cargarInfoGrupo() {
        val tvGroupEmoji = findViewById<TextView>(R.id.tvAvatarEmoji)

        lifecycleScope.launch(Dispatchers.IO) {
            val group = database.nexuDao().getGroup(groupId)
            currentGroup = group
            if (group != null) {
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.tvContactName).text = group.groupName

                    membersList = group.members?.split(",")
                        ?.filter { it.isNotBlank() }
                        ?.map { it.trim() }
                        ?.toMutableList() ?: mutableListOf()

                    val tvContactId = findViewById<TextView>(R.id.tvContactId)
                    tvContactId.text = "${membersList.size} miembros"
                    tvContactId.setTextColor(auraColor)

                    val avatarStr = group.groupAvatar ?: "👥"
                    if (avatarStr.startsWith("file://") || avatarStr.startsWith("content://") || avatarStr.startsWith("/")) {
                        tvGroupEmoji.text = ""
                        try {
                            val uri = Uri.parse(avatarStr)
                            val file = File(uri.path!!)
                            if (file.exists()) {
                                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                                val rounded = RoundedBitmapDrawableFactory.create(resources, bitmap)
                                rounded.isCircular = true
                                tvGroupEmoji.background = rounded
                            }
                        } catch (e: Exception) {
                            tvGroupEmoji.text = "👥"
                        }
                    } else {
                        tvGroupEmoji.text = avatarStr
                    }

                    tvGroupEmoji.setOnClickListener {
                        val view = layoutInflater.inflate(R.layout.dialog_confirm_action, null)
                        val dialog = AlertDialog.Builder(this@EditGroupActivity).setView(view).create()
                        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

                        view.findViewById<TextView>(R.id.tvConfirmTitle).text = "Opciones de Imagen"
                        view.findViewById<TextView>(R.id.tvConfirmMessage).text = "¿Qué deseas hacer con la imagen del grupo?"
                        view.findViewById<ImageView>(R.id.ivConfirmIcon).setImageResource(R.drawable.ic_camera_placeholder)
                        view.findViewById<ImageView>(R.id.ivConfirmIcon).setColorFilter(auraColor)

                        view.findViewById<Button>(R.id.btnCancelAction).text = "Ver foto"
                        val btnChange = view.findViewById<Button>(R.id.btnConfirmAction)
                        btnChange.text = "Cambiar"
                        btnChange.backgroundTintList = ColorStateList.valueOf(auraColor)

                        view.findViewById<Button>(R.id.btnCancelAction).setOnClickListener {
                            dialog.dismiss()
                            if (avatarStr.startsWith("file://") || avatarStr.startsWith("content://") || avatarStr.startsWith("/")) {
                                val intent = Intent(this@EditGroupActivity, ImageViewerActivity::class.java)
                                intent.putExtra("IMAGE_URI", avatarStr)
                                startActivity(intent)
                            } else {
                                Toast.makeText(this@EditGroupActivity, "El grupo utiliza un emoji como avatar", Toast.LENGTH_SHORT).show()
                            }
                        }

                        btnChange.setOnClickListener {
                            dialog.dismiss()
                            val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_MEDIA_IMAGES) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                            if (ContextCompat.checkSelfPermission(this@EditGroupActivity, p[0]) != PackageManager.PERMISSION_GRANTED) {
                                requestGalleryPermissionLauncher.launch(p)
                            } else {
                                AttachmentHelper.openCustomGallery(this@EditGroupActivity, layoutInflater) { uri -> procesarUriAvatarGrupo(uri) }
                            }
                        }
                        dialog.show()
                    }

                    val rvMembersList = findViewById<RecyclerView>(R.id.rvMembersList)
                    rvMembersList.adapter = MembersAdapter(membersList)
                }
                rvGroupSharedFiles()
            }
        }
    }

    private suspend fun rvGroupSharedFiles() {
        val messages = withContext(Dispatchers.IO) {
            database.nexuDao().getMessagesForUserSync(groupId, myLocalUserId)
        }
        val mediaFiles = messages.mapNotNull { it.imagePath }.filter { it.isNotBlank() }

        withContext(Dispatchers.Main) {
            val tvFilesCount = findViewById<TextView>(R.id.tvFilesCount)
            tvFilesCount?.setTextColor(auraColor)

            if (mediaFiles.isEmpty()) {
                tvFilesCount?.text = "0 archivos >"
            } else {
                tvFilesCount?.text = "${mediaFiles.size} archivos >"

                val rvGroupSharedFiles = findViewById<RecyclerView>(R.id.rvSharedFiles)
                rvGroupSharedFiles.layoutManager = LinearLayoutManager(this@EditGroupActivity, LinearLayoutManager.HORIZONTAL, false)

                rvGroupSharedFiles.adapter = MediaVaultAdapter(mediaFiles.take(10)) { uriStr ->
                    val isVideo = uriStr.endsWith(".mp4") || uriStr.contains("VID_") || uriStr.contains("video")
                    if (isVideo) {
                        val intent = Intent(this@EditGroupActivity, Class.forName("com.offline.nexu.ui.chat.VideoPlayerActivity"))
                        intent.putExtra("VIDEO_URI", uriStr)
                        startActivity(intent)
                    } else {
                        val intent = Intent(this@EditGroupActivity, ImageViewerActivity::class.java)
                        intent.putExtra("IMAGE_URI", uriStr)
                        startActivity(intent)
                    }
                }
            }
        }
    }

    private fun mostrarDialogoAñadirMiembros() {
        val connectedNames = P2PManager.connectedNames
        val availableToAdd = connectedNames.filter { it.value !in membersList }

        if (availableToAdd.isEmpty()) {
            Toast.makeText(this, "No hay contactos nuevos disponibles en tu radar para añadir.", Toast.LENGTH_SHORT).show()
            return
        }

        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_select_user, null)
        bottomSheet.setContentView(view)

        val llUserList = view.findViewById<LinearLayout>(R.id.llUserList)

        for (entry in availableToAdd) {
            val endpointId = entry.key
            val userName = entry.value
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
                    GroupManager.addMemberToGroup(this@EditGroupActivity, groupId, userName, membersList) {
                        cargarInfoGrupo()
                    }
                }
            }

            val avatarCard = androidx.cardview.widget.CardView(this).apply {
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
                setTypeface(null, android.graphics.Typeface.BOLD)
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
                setTextColor(getColor(R.color.text_primary))
                textSize = 18f
            }

            userView.addView(avatarCard)
            userView.addView(tvName)
            llUserList.addView(userView)
        }

        (view.parent as View).setBackgroundColor(Color.TRANSPARENT)
        bottomSheet.show()
    }

    inner class MembersAdapter(private val members: List<String>) : RecyclerView.Adapter<MembersAdapter.MemberHolder>() {

        inner class MemberHolder(view: View) : RecyclerView.ViewHolder(view) {
            val llContainer: LinearLayout = view as LinearLayout
            val vOnlineDot: View = llContainer.getChildAt(0)
            val tvName: TextView = llContainer.getChildAt(1) as TextView
            val ivRemove: ImageView = llContainer.getChildAt(2) as ImageView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberHolder {
            val layout = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(40, 24, 40, 24)
            }

            val dot = View(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(24, 24).apply { marginEnd = 24 }
                background = ContextCompat.getDrawable(parent.context, R.drawable.circle_background)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            }

            val name = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                textSize = 16f
                setTextColor(ContextCompat.getColor(parent.context, R.color.text_primary))
            }

            val remove = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(60, 60)
                setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                imageTintList = ColorStateList.valueOf(Color.parseColor("#F44336"))
                background = ContextCompat.getDrawable(parent.context, ThemeUtils.getSelectableItemBackground(parent.context))
                setPadding(8, 8, 8, 8)
            }

            layout.addView(dot)
            layout.addView(name)
            layout.addView(remove)

            return MemberHolder(layout)
        }

        override fun onBindViewHolder(holder: MemberHolder, position: Int) {
            val memberId = members[position]
            val isMe = memberId == myLocalUserId

            holder.tvName.text = if (isMe) "Tú" else memberId

            val isOnline = !isMe && (
                    P2PManager.connectedNames.containsValue(memberId) ||
                            NexuMeshManager.connectedEndpoints.contains(memberId)
                    )

            holder.vOnlineDot.visibility = if (isOnline) View.VISIBLE else View.INVISIBLE

            holder.ivRemove.visibility = if (!isMe) View.VISIBLE else View.GONE

            holder.ivRemove.setOnClickListener {
                mostrarDialogoConfirmacion(
                    "Eliminar Participante",
                    "¿Estás seguro de que deseas sacar a $memberId del grupo?",
                    android.R.drawable.ic_menu_delete,
                    isDestructive = true
                ) {
                    GroupManager.removeMemberFromGroup(this@EditGroupActivity, groupId, memberId, membersList) {
                        cargarInfoGrupo()
                    }
                }
            }
        }

        override fun getItemCount() = members.size
    }
}

fun com.offline.nexu.data.local.db.NexuDao.updateGroupAvatar(groupId: String, path: String) {
}