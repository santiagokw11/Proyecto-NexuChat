package com.offline.nexu.ui.chat.group

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object GroupManager {

    fun handleGroupCreateCommand(context: Context, text: String, senderEndpointId: String) {
        val parts = text.split("|")
        if (parts.size >= 6) {
            val groupId = parts[1]
            val groupName = parts[2]
            val members = parts[3]
            val avatarPayloadId = parts[4].toLongOrNull()
            val expirationTime = parts[5].toLongOrNull()?.takeIf { it > 0 }

            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(context)
                val existing = db.nexuDao().getGroup(groupId)
                if (existing == null) {
                    val group = GroupEntity(groupId, groupName, null, "", members, expirationTime)
                    db.nexuDao().insertGroup(group)

                    val dummyMsg = MessageEntity(
                        msgId = System.currentTimeMillis(),
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
            }
        }
    }

    fun handleGroupUpdateCommand(context: Context, text: String) {
        val parts = text.split("|")
        if (parts.size >= 4) {
            val groupId = parts[1]
            val groupName = parts[2]
            val members = parts[3]
            val avatarPayloadId = if (parts.size >= 5) parts[4].toLongOrNull() else null

            CoroutineScope(Dispatchers.IO).launch {
                val db = AppDatabase.getDatabase(context)
                val existing = db.nexuDao().getGroup(groupId)
                if (existing != null) {
                    val updated = existing.copy(groupName = groupName, members = members)
                    db.nexuDao().insertGroup(updated)

                    withContext(Dispatchers.Main) {
                        val intent = Intent("com.offline.nexu.ACTION_UPDATE_CHAT_LIST")
                        intent.setPackage(context.packageName)
                        context.sendBroadcast(intent)

                        if (P2PManager.activeChatIdentifier == groupId) {
                            P2PManager.onMessageReceivedListener?.invoke(System.currentTimeMillis(), "SYS:REFRESH_GROUP", null)
                        }
                    }
                }
            }

            if (avatarPayloadId != null && avatarPayloadId != 0L) {
                P2PManager.setExpectedGroupAvatar(avatarPayloadId, groupId)
            }
        }
    }

    fun createGroupNetworkCommand(context: Context, groupName: String, selectedContactsIds: List<String>, selectedImageUri: Uri?, expirationTime: Long?, onSuccess: (String) -> Unit) {
        val myLocalUserId = Prefs(context).getUserProfile().id
        val myName = Prefs(context).getUserProfile().name
        val groupId = "GRP_${System.currentTimeMillis()}"

        // TRADUCCIÓN NOMBRES (MESH FIX)
        val realMemberNames = selectedContactsIds.map { NexuMeshManager.connectedNames[it] ?: it }
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
            msgId = System.currentTimeMillis(), localUserId = myLocalUserId, endpointId = groupId,
            originalSender = myLocalUserId, isGroup = true, text = dummyText, imagePath = null,
            isMine = true, status = 99, timestamp = System.currentTimeMillis()
        )

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(context)
            db.nexuDao().insertGroup(groupEntity)
            db.nexuDao().insertMessage(dummyMsg)

            val expStr = expirationTime?.toString() ?: "0"
            val cmd = "SYS:GROUP_CREATE|$groupId|$groupName|$membersString|${avatarPayloadId ?: ""}|$expStr"

            selectedContactsIds.forEachIndexed { index, memberId ->
                val targetName = NexuMeshManager.connectedNames[memberId] ?: memberId
                val msgId = System.currentTimeMillis() + index

                // GUARDAR EL COMANDO EN DB PARA GARANTIZAR ENTREGA POR SYNC SI SE DESCONECTAN
                val pendingSysMsg = MessageEntity(
                    msgId = msgId, localUserId = myLocalUserId, endpointId = targetName,
                    originalSender = myLocalUserId, isGroup = false, text = cmd, imagePath = null,
                    isMine = true, status = 0, timestamp = System.currentTimeMillis()
                )
                db.nexuDao().insertMessage(pendingSysMsg)

                // Enviar el comando puro, sin la envoltura TXT:0: que generaba conflictos en la persistencia
                NexuMeshManager.enviarMensajeMesh(context, myLocalUserId, targetName, msgId, cmd)
                delay(400)
            }

            if (filePayload != null) {
                NexuMeshManager.connectedEndpoints.forEach { ep ->
                    Nearby.getConnectionsClient(context).sendPayload(ep, filePayload)
                }
            }

            withContext(Dispatchers.Main) { onSuccess(groupId) }
        }
    }

    fun addMemberToGroup(context: Context, groupId: String, nuevoMiembroName: String, currentMembers: MutableList<String>, onSuccess: () -> Unit) {
        val myLocalUserId = Prefs(context).getUserProfile().id
        val myName = Prefs(context).getUserProfile().name

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(context)
            val group = db.nexuDao().getGroup(groupId) ?: return@launch

            val targetNuevo = NexuMeshManager.connectedNames[nuevoMiembroName] ?: nuevoMiembroName
            currentMembers.add(targetNuevo)
            val newMembersString = currentMembers.joinToString(",")
            val updatedGroup = group.copy(members = newMembersString)
            db.nexuDao().insertGroup(updatedGroup)

            val expStr = group.expirationTime?.toString() ?: "0"
            val cmdCreate = "SYS:GROUP_CREATE|$groupId|${group.groupName}|$newMembersString||$expStr"

            val msgIdCreate = System.currentTimeMillis()
            val pendingSysMsgCreate = MessageEntity(
                msgId = msgIdCreate, localUserId = myLocalUserId, endpointId = targetNuevo,
                originalSender = myLocalUserId, isGroup = false, text = cmdCreate, imagePath = null,
                isMine = true, status = 0, timestamp = System.currentTimeMillis()
            )
            db.nexuDao().insertMessage(pendingSysMsgCreate)
            NexuMeshManager.enviarMensajeMesh(context, myLocalUserId, targetNuevo, msgIdCreate, cmdCreate)

            val cmdUpdate = "SYS:GROUP_UPDATE|$groupId|${group.groupName}|$newMembersString"
            currentMembers.forEachIndexed { index, member ->
                val targetMember = NexuMeshManager.connectedNames[member] ?: member
                if (targetMember != myLocalUserId && targetMember != myName && targetMember != targetNuevo) {
                    val msgIdUpdate = System.currentTimeMillis() + 50 + index
                    val pendingSysMsgUpdate = MessageEntity(
                        msgId = msgIdUpdate, localUserId = myLocalUserId, endpointId = targetMember,
                        originalSender = myLocalUserId, isGroup = false, text = cmdUpdate, imagePath = null,
                        isMine = true, status = 0, timestamp = System.currentTimeMillis()
                    )
                    db.nexuDao().insertMessage(pendingSysMsgUpdate)
                    NexuMeshManager.enviarMensajeMesh(context, myLocalUserId, targetMember, msgIdUpdate, cmdUpdate)
                }
            }

            val eventText = "$myName añadió a $targetNuevo"
            val eventId = System.currentTimeMillis() + 200
            NexuMeshManager.enviarMensajeGrupoMesh(context, myLocalUserId, groupId, eventId, "TXT:0:SYS:EVENT:$eventText")

            val localMsg = MessageEntity(
                msgId = System.currentTimeMillis() + 250, localUserId = myLocalUserId, endpointId = groupId,
                originalSender = myLocalUserId, isGroup = true, text = "Añadiste a $targetNuevo",
                imagePath = null, isMine = true, status = 99, timestamp = System.currentTimeMillis()
            )
            db.nexuDao().insertMessage(localMsg)

            withContext(Dispatchers.Main) { onSuccess() }
        }
    }

    fun removeMemberFromGroup(context: Context, groupId: String, miembroARemover: String, currentMembers: MutableList<String>, onSuccess: () -> Unit) {
        val myLocalUserId = Prefs(context).getUserProfile().id
        val myName = Prefs(context).getUserProfile().name

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(context)
            val group = db.nexuDao().getGroup(groupId) ?: return@launch

            val targetRemover = NexuMeshManager.connectedNames[miembroARemover] ?: miembroARemover
            currentMembers.remove(targetRemover)

            val newMembersString = currentMembers.joinToString(",")
            val updatedGroup = group.copy(members = newMembersString)
            db.nexuDao().insertGroup(updatedGroup)

            val eventText = "$myName eliminó a $targetRemover"
            val eventId = System.currentTimeMillis() + 50
            NexuMeshManager.enviarMensajeGrupoMesh(context, myLocalUserId, groupId, eventId, "TXT:0:SYS:EVENT:$eventText")

            val cmdUpdate = "SYS:GROUP_UPDATE|$groupId|${group.groupName}|$newMembersString"
            val notifyList = currentMembers + targetRemover
            notifyList.forEachIndexed { index, member ->
                val targetMember = NexuMeshManager.connectedNames[member] ?: member
                if (targetMember != myLocalUserId && targetMember != myName) {
                    val msgIdUpdate = System.currentTimeMillis() + 100 + index
                    val pendingSysMsgUpdate = MessageEntity(
                        msgId = msgIdUpdate, localUserId = myLocalUserId, endpointId = targetMember,
                        originalSender = myLocalUserId, isGroup = false, text = cmdUpdate, imagePath = null,
                        isMine = true, status = 0, timestamp = System.currentTimeMillis()
                    )
                    db.nexuDao().insertMessage(pendingSysMsgUpdate)
                    NexuMeshManager.enviarMensajeMesh(context, myLocalUserId, targetMember, msgIdUpdate, cmdUpdate)
                }
            }

            val localMsg = MessageEntity(
                msgId = System.currentTimeMillis() + 200, localUserId = myLocalUserId, endpointId = groupId,
                originalSender = myLocalUserId, isGroup = true, text = "Eliminaste a $targetRemover",
                imagePath = null, isMine = true, status = 99, timestamp = System.currentTimeMillis()
            )
            db.nexuDao().insertMessage(localMsg)

            withContext(Dispatchers.Main) { onSuccess() }
        }
    }
}