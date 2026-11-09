package com.offline.nexu.ui.chat.group

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.Payload
import com.offline.nexu.R
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.local.db.AppDatabase
import com.offline.nexu.data.local.db.GroupEntity
import com.offline.nexu.data.local.db.MessageEntity
import com.offline.nexu.data.network.NexuMeshManager
import com.offline.nexu.ui.home.P2PManager
import com.offline.nexu.utils.MsgIdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object GroupManager {
    private const val TAG = "NexuGroupDebug"

    fun handleGroupCreateCommand(context: Context, text: String, senderEndpointId: String) {
        Log.d(TAG, "handleGroupCreateCommand recibido crudo: $text")
        val parts = text.split("|")
        if (parts.size >= 7) {
            val groupId = parts[1]
            val groupName = parts[2]
            val members = parts[3]
            val avatarPayloadId = parts[4].toLongOrNull()
            val expirationTime = parts[5].toLongOrNull()?.takeIf { it > 0 }
            val version = parts[6].toLongOrNull() ?: 1L

            CoroutineScope(Dispatchers.IO).launch {
              try {
                val db = AppDatabase.getDatabase(context)
                val existing = db.nexuDao().getGroup(groupId)
                Log.d(TAG, "groupId=$groupId existing=${existing != null}")
                if (existing == null) {
                    val group = GroupEntity(groupId, groupName, null, "", members, expirationTime, version)
                    db.nexuDao().insertGroup(group)
                    Log.d(TAG, "Grupo insertado OK: $groupId '$groupName' miembros=$members")

                    val dummyMsg = MessageEntity(
                        msgId = MsgIdGenerator.next(),
                        localUserId = Prefs(context).getUserProfile().id,
                        endpointId = groupId,
                        originalSender = groupId,
                        isGroup = true,
                        text = "Te han añadido al grupo '$groupName'",
                        imagePath = null,
                        isMine = false,
                        status = 99,
                        timestamp = System.currentTimeMillis()
                    )
                    db.nexuDao().insertMessage(dummyMsg)

                    if (avatarPayloadId != null && avatarPayloadId != 0L) {
                        P2PManager.setExpectedGroupAvatar(avatarPayloadId, groupId)
                    }

                    withContext(Dispatchers.Main) {
                        val channelId = "nexu_groups"
                        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val channel = NotificationChannel(channelId, "Grupos P2P", NotificationManager.IMPORTANCE_HIGH)
                            notifManager.createNotificationChannel(channel)
                        }

                        val notifIntent = Intent(context, Class.forName("com.offline.nexu.ui.chat.ChatActivity")).apply {
                            putExtra("EXTRA_IS_GROUP", true)
                            putExtra("EXTRA_GROUP_ID", groupId)
                            putExtra("EXTRA_GROUP_NAME", groupName)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }

                        val pendingIntent = PendingIntent.getActivity(context, groupId.hashCode(), notifIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                        val builder = NotificationCompat.Builder(context, channelId)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle("Nuevo Grupo")
                            .setContentText("Te han añadido al grupo '$groupName'")
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setContentIntent(pendingIntent)
                            .setAutoCancel(true)

                        notifManager.notify(groupId.hashCode(), builder.build())

                        val intent = Intent("com.offline.nexu.ACTION_UPDATE_CHAT_LIST")
                        intent.setPackage(context.packageName)
                        context.sendBroadcast(intent)
                    }
                }
              } catch (e: Exception) {
                  Log.e(TAG, "Excepción insertando grupo", e)
                  withContext(Dispatchers.Main) { Toast.makeText(context, "Error recibiendo grupo: ${e.message}", Toast.LENGTH_LONG).show() }
              }
            }
        } else {
            Log.e(TAG, "SYS:GROUP_CREATE descartado, partes=${parts.size} (se necesitan >=7): $text")
        }
    }

    fun handleGroupUpdateCommand(context: Context, text: String) {
        Log.d(TAG, "handleGroupUpdateCommand recibido crudo: $text")
        val parts = text.split("|")
        if (parts.size >= 5) {
            val groupId = parts[1]
            val groupName = parts[2]
            val members = parts[3]
            val version = parts[4].toLongOrNull() ?: 0L

            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(context)
                val existing = db.nexuDao().getGroup(groupId)
                // Ignora actualizaciones con versión igual o anterior a la ya aplicada: evita que
                // un SYS:GROUP_UPDATE viejo, reenviado por la cola de pendientes después de uno
                // más nuevo ya confirmado, revierta el estado del grupo (miembro que "reaparece").
                if (existing != null && version > existing.version) {
                    val updated = existing.copy(groupName = groupName, members = members, version = version)
                    db.nexuDao().insertGroup(updated)

                    withContext(Dispatchers.Main) {
                        val intent = Intent("com.offline.nexu.ACTION_UPDATE_CHAT_LIST")
                        intent.setPackage(context.packageName)
                        context.sendBroadcast(intent)

                        if (P2PManager.activeChatIdentifier == groupId) {
                            P2PManager.onMessageReceivedListener?.invoke(MsgIdGenerator.next(), "SYS:REFRESH_GROUP", null)
                        }
                    }
                }
            }
        }
    }

    fun createGroupNetworkCommand(context: Context, groupName: String, selectedContactsIds: List<String>, selectedImageUri: Uri?, expirationTime: Long?, onSuccess: (String) -> Unit) {
        val myLocalUserId = Prefs(context).getUserProfile().id
        val myName = Prefs(context).getUserProfile().name
        val groupId = "GRP_${System.currentTimeMillis()}"

        // TRADUCCIÓN NOMBRES (MESH FIX)
        val realMemberNames = selectedContactsIds.map { P2PManager.connectedNames[it] ?: it }
        val allMembersIds = realMemberNames + myName
        val membersString = allMembersIds.joinToString(",")

        val groupEntity = GroupEntity(
            groupId = groupId, groupName = groupName, groupAvatar = selectedImageUri?.toString(),
            adminId = myLocalUserId, members = membersString, expirationTime = expirationTime
        )

        var avatarPayloadId: Long? = null
        var filePayload: Payload? = null
        if (selectedImageUri != null) {
            try {
                val file = File(selectedImageUri.path!!)
                if (file.exists()) {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    filePayload = Payload.fromFile(pfd)
                    avatarPayloadId = filePayload.id
                }
            } catch (e: Exception) {}
        }

        val dummyText = if (expirationTime != null) "Creaste el grupo efímero '$groupName'" else "Creaste el grupo '$groupName'"
        val dummyMsg = MessageEntity(
            msgId = MsgIdGenerator.next(), localUserId = myLocalUserId, endpointId = groupId,
            originalSender = myLocalUserId, isGroup = true, text = dummyText, imagePath = null,
            isMine = true, status = 99, timestamp = System.currentTimeMillis()
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                db.nexuDao().insertGroup(groupEntity)
                db.nexuDao().insertMessage(dummyMsg)

                val expStr = expirationTime?.toString() ?: "0"
                val cmd = "SYS:GROUP_CREATE|$groupId|$groupName|$membersString|${avatarPayloadId ?: ""}|$expStr|${groupEntity.version}"

                selectedContactsIds.forEach { memberId ->
                    val targetName = P2PManager.connectedNames[memberId] ?: memberId
                    val msgId = MsgIdGenerator.next()

                    // GUARDAR EL COMANDO EN DB PARA GARANTIZAR ENTREGA POR SYNC SI SE DESCONECTAN
                    val pendingSysMsg = MessageEntity(
                        msgId = msgId, localUserId = myLocalUserId, endpointId = targetName,
                        originalSender = myLocalUserId, isGroup = false, text = cmd, imagePath = null,
                        isMine = true, status = 0, timestamp = System.currentTimeMillis()
                    )
                    db.nexuDao().insertMessage(pendingSysMsg)

                    // Enviar el comando puro, sin la envoltura TXT:0: que generaba conflictos en la persistencia
                    try {
                        NexuMeshManager.enviarMensajeMesh(context, myLocalUserId, myName, targetName, msgId, cmd)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { Toast.makeText(context, "No se pudo notificar a $targetName: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                    delay(400)
                }

                if (filePayload != null) {
                    NexuMeshManager.connectedEndpoints.forEach { ep ->
                        try { Nearby.getConnectionsClient(context).sendPayload(ep, filePayload) } catch (e: Exception) {}
                    }
                }

                withContext(Dispatchers.Main) { onSuccess(groupId) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Error creando el grupo: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun addMemberToGroup(context: Context, groupId: String, nuevoMiembroName: String, currentMembers: MutableList<String>, onSuccess: () -> Unit) {
        val myLocalUserId = Prefs(context).getUserProfile().id
        val myName = Prefs(context).getUserProfile().name

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val group = db.nexuDao().getGroup(groupId)
                if (group == null) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "El grupo ya no existe localmente", Toast.LENGTH_SHORT).show() }
                    return@launch
                }

                val targetNuevo = P2PManager.connectedNames[nuevoMiembroName] ?: nuevoMiembroName
                currentMembers.add(targetNuevo)
                val newMembersString = currentMembers.joinToString(",")
                val newVersion = group.version + 1
                val updatedGroup = group.copy(members = newMembersString, version = newVersion)
                db.nexuDao().insertGroup(updatedGroup)

                val expStr = group.expirationTime?.toString() ?: "0"
                val cmdCreate = "SYS:GROUP_CREATE|$groupId|${group.groupName}|$newMembersString||$expStr|$newVersion"

                val msgIdCreate = MsgIdGenerator.next()
                val pendingSysMsgCreate = MessageEntity(
                    msgId = msgIdCreate, localUserId = myLocalUserId, endpointId = targetNuevo,
                    originalSender = myLocalUserId, isGroup = false, text = cmdCreate, imagePath = null,
                    isMine = true, status = 0, timestamp = System.currentTimeMillis()
                )
                db.nexuDao().insertMessage(pendingSysMsgCreate)
                try {
                    NexuMeshManager.enviarMensajeMesh(context, myLocalUserId, myName, targetNuevo, msgIdCreate, cmdCreate)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "No se pudo notificar a $targetNuevo: ${e.message}", Toast.LENGTH_LONG).show() }
                }

                val cmdUpdate = "SYS:GROUP_UPDATE|$groupId|${group.groupName}|$newMembersString|$newVersion"
                currentMembers.forEach { member ->
                    val targetMember = P2PManager.connectedNames[member] ?: member
                    if (targetMember != myLocalUserId && targetMember != myName && targetMember != targetNuevo) {
                        val msgIdUpdate = MsgIdGenerator.next()
                        val pendingSysMsgUpdate = MessageEntity(
                            msgId = msgIdUpdate, localUserId = myLocalUserId, endpointId = targetMember,
                            originalSender = myLocalUserId, isGroup = false, text = cmdUpdate, imagePath = null,
                            isMine = true, status = 0, timestamp = System.currentTimeMillis()
                        )
                        db.nexuDao().insertMessage(pendingSysMsgUpdate)
                        try {
                            NexuMeshManager.enviarMensajeMesh(context, myLocalUserId, myName, targetMember, msgIdUpdate, cmdUpdate)
                        } catch (e: Exception) {}
                    }
                }

                val eventText = "$myName añadió a $targetNuevo"
                val eventId = MsgIdGenerator.next()
                try {
                    NexuMeshManager.enviarMensajeGrupoMesh(context, myLocalUserId, myName, groupId, eventId, "TXT:0:SYS:EVENT:$eventText")
                } catch (e: Exception) {}

                val localMsg = MessageEntity(
                    msgId = MsgIdGenerator.next(), localUserId = myLocalUserId, endpointId = groupId,
                    originalSender = myLocalUserId, isGroup = true, text = "Añadiste a $targetNuevo",
                    imagePath = null, isMine = true, status = 99, timestamp = System.currentTimeMillis()
                )
                db.nexuDao().insertMessage(localMsg)

                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Error añadiendo miembro: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun removeMemberFromGroup(context: Context, groupId: String, miembroARemover: String, currentMembers: MutableList<String>, onSuccess: () -> Unit) {
        val myLocalUserId = Prefs(context).getUserProfile().id
        val myName = Prefs(context).getUserProfile().name

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                val group = db.nexuDao().getGroup(groupId)
                if (group == null) {
                    withContext(Dispatchers.Main) { Toast.makeText(context, "El grupo ya no existe localmente", Toast.LENGTH_SHORT).show() }
                    return@launch
                }

                val targetRemover = P2PManager.connectedNames[miembroARemover] ?: miembroARemover
                currentMembers.remove(targetRemover)

                val newMembersString = currentMembers.joinToString(",")
                val newVersion = group.version + 1
                val updatedGroup = group.copy(members = newMembersString, version = newVersion)
                db.nexuDao().insertGroup(updatedGroup)

                val eventText = "$myName eliminó a $targetRemover"
                val eventId = MsgIdGenerator.next()
                try {
                    NexuMeshManager.enviarMensajeGrupoMesh(context, myLocalUserId, myName, groupId, eventId, "TXT:0:SYS:EVENT:$eventText")
                } catch (e: Exception) {}

                val cmdUpdate = "SYS:GROUP_UPDATE|$groupId|${group.groupName}|$newMembersString|$newVersion"
                val notifyList = currentMembers + targetRemover
                notifyList.forEach { member ->
                    val targetMember = P2PManager.connectedNames[member] ?: member
                    if (targetMember != myLocalUserId && targetMember != myName) {
                        val msgIdUpdate = MsgIdGenerator.next()
                        val pendingSysMsgUpdate = MessageEntity(
                            msgId = msgIdUpdate, localUserId = myLocalUserId, endpointId = targetMember,
                            originalSender = myLocalUserId, isGroup = false, text = cmdUpdate, imagePath = null,
                            isMine = true, status = 0, timestamp = System.currentTimeMillis()
                        )
                        db.nexuDao().insertMessage(pendingSysMsgUpdate)
                        try {
                            NexuMeshManager.enviarMensajeMesh(context, myLocalUserId, myName, targetMember, msgIdUpdate, cmdUpdate)
                        } catch (e: Exception) {}
                    }
                }

                val localMsg = MessageEntity(
                    msgId = MsgIdGenerator.next(), localUserId = myLocalUserId, endpointId = groupId,
                    originalSender = myLocalUserId, isGroup = true, text = "Eliminaste a $targetRemover",
                    imagePath = null, isMine = true, status = 99, timestamp = System.currentTimeMillis()
                )
                db.nexuDao().insertMessage(localMsg)

                withContext(Dispatchers.Main) { onSuccess() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(context, "Error eliminando miembro: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}