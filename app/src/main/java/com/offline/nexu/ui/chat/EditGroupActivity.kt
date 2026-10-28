package com.offline.nexu.ui.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.GroupEntity
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.data.network.NexuMeshManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class EditGroupActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var prefs: Prefs
    private lateinit var myLocalUserId: String
    private lateinit var groupId: String
    private var currentGroup: GroupEntity? = null
    private val currentMembersList = mutableSetOf<String>()
    private var selectedImageUri: Uri? = null

    private val selectMembersLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val selected = result.data?.getStringArrayListExtra("SELECTED_USERS") ?: return@registerForActivityResult
            val isAdding = result.data?.getBooleanExtra("WAS_ADDING", true) ?: true

            if (isAdding) {
                currentMembersList.addAll(selected)
                if (selected.isNotEmpty()) Toast.makeText(this, "${selected.size} añadidos a la lista", Toast.LENGTH_SHORT).show()
            } else {
                currentMembersList.removeAll(selected.toSet())
                if (selected.isNotEmpty()) Toast.makeText(this, "${selected.size} marcados para remover", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val localUri = copyImageToLocal(uri)
                withContext(Dispatchers.Main) {
                    if (localUri != null) {
                        selectedImageUri = localUri
                        findViewById<ImageView>(R.id.ivGroupAvatar).setImageURI(localUri)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_group)

        database = AppDatabase.getDatabase(this)
        prefs = Prefs(this)
        myLocalUserId = prefs.getUserProfile().id
        groupId = intent.getStringExtra("EXTRA_GROUP_ID") ?: run { finish(); return }

        findViewById<View>(R.id.cvGroupAvatar).setOnClickListener { pickImageLauncher.launch("image/*") }

        findViewById<View>(R.id.btnAddMembers).setOnClickListener {
            verificarYAbrirSeleccion(isAdding = true)
        }

        findViewById<View>(R.id.btnRemoveMembers).setOnClickListener {
            verificarYAbrirSeleccion(isAdding = false)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            currentGroup = database.nexuDao().getGroup(groupId)
            currentGroup?.let { group ->
                val members = group.members.split(",").filter { it.isNotEmpty() }
                currentMembersList.clear()
                currentMembersList.addAll(members)
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.etGroupName).setText(group.groupName)
                    if (!group.groupAvatar.isNullOrEmpty()) {
                        try { findViewById<ImageView>(R.id.ivGroupAvatar).setImageURI(Uri.parse(group.groupAvatar)) } catch (e: Exception) {}
                    }
                }
            }
        }

        findViewById<View>(R.id.btnConfirmEditGroup).setOnClickListener {
            val name = findViewById<TextView>(R.id.etGroupName).text.toString().trim()
            if (name.isNotEmpty()) guardarCambios(name)
            else Toast.makeText(this, "El nombre del grupo no puede estar vacío", Toast.LENGTH_SHORT).show()
        }
    }

    private fun verificarYAbrirSeleccion(isAdding: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val myId = prefs.getUserProfile().id
            val contacts = if (isAdding) {
                val recent = database.nexuDao().getRecentChats(myId)
                recent.filter { !it.isGroup && !currentMembersList.contains(it.endpointId) }
                    .map { it.endpointId }.distinct()
            } else {
                currentMembersList.filter { it != myId }
            }

            withContext(Dispatchers.Main) {
                if (contacts.isEmpty()) {
                    val msg = if (isAdding) "No hay nuevos miembros disponibles para añadir en tus contactos"
                    else "No hay otros miembros en este grupo para remover"
                    Toast.makeText(this@EditGroupActivity, msg, Toast.LENGTH_SHORT).show()
                } else {
                    val intent = Intent(this@EditGroupActivity, SelectMembersActivity::class.java).apply {
                        putExtra("IS_ADDING_MODE", isAdding)
                        putStringArrayListExtra("CURRENT_MEMBERS", ArrayList(currentMembersList.toList()))
                    }
                    selectMembersLauncher.launch(intent)
                }
            }
        }
    }

    private fun copyImageToLocal(uri: Uri): Uri? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val mediaDir = File(getExternalFilesDir(null), "NexuChat/Media/Groups")
            if (!mediaDir.exists()) mediaDir.mkdirs()
            val file = File(mediaDir, "GRP_IMG_${System.currentTimeMillis()}.jpg")
            inputStream.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
            Uri.fromFile(file)
        } catch (e: Exception) { null }
    }

    private fun guardarCambios(newName: String) {
        val membersString = currentMembersList.joinToString(",")
        val finalAvatar = selectedImageUri?.toString() ?: currentGroup?.groupAvatar
        val updatedGroup = GroupEntity(groupId, newName, finalAvatar, currentGroup?.adminId ?: myLocalUserId, membersString)

        lifecycleScope.launch(Dispatchers.IO) {
            database.nexuDao().insertGroup(updatedGroup)
            database.nexuDao().insertMessage(MessageEntity(System.currentTimeMillis(), myLocalUserId, groupId, myLocalUserId, true, "Grupo actualizado", null, true, 3, System.currentTimeMillis()))

            val cmd = "SYS:GROUP_UPDATE|$groupId|$newName|$membersString"
            currentMembersList.forEach {
                if(it != myLocalUserId) NexuMeshManager.enviarMensajeMesh(applicationContext, myLocalUserId, it, System.currentTimeMillis(), "TXT:0:$cmd")
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@EditGroupActivity, "Cambios guardados", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}