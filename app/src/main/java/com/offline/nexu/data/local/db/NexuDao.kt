package com.offline.nexu.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NexuDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertGroup(group: GroupEntity)

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    fun getGroup(groupId: String): GroupEntity?

    @Query("SELECT * FROM messages WHERE endpointId = :endpointId AND localUserId = :localUserId ORDER BY timestamp ASC")
    fun getMessagesForUserSync(endpointId: String, localUserId: String): List<MessageEntity>

    // Alcance por localUserId: msgId no es único entre cuentas en multicuenta (ver MessageEntity).
    // status != 99 protege mensajes "de sistema" (zumbido, chat inicializado, eventos de grupo):
    // comparten outgoingPayloadsTracker con mensajes normales y, al confirmarse la entrega, el
    // flujo normal de ACK (status 1/2/3) podía pisar su 99 y mostrarlos como mensaje de texto.
    @Query("UPDATE messages SET status = :newStatus WHERE msgId = :msgId AND localUserId = :localUserId AND status != 99")
    fun updateStatus(msgId: Long, localUserId: String, newStatus: Int)

    @Query("SELECT * FROM messages WHERE localUserId = :localUserId AND msgId IN (SELECT MAX(msgId) FROM messages WHERE localUserId = :localUserId AND (text IS NULL OR text NOT LIKE 'SYS:%') GROUP BY endpointId) ORDER BY timestamp DESC")
    fun getRecentChats(localUserId: String): List<MessageEntity>

    // NUEVO: Consulta reactiva con Flow para actualizaciones en tiempo real
    @Query("SELECT * FROM messages WHERE localUserId = :localUserId AND msgId IN (SELECT MAX(msgId) FROM messages WHERE localUserId = :localUserId AND (text IS NULL OR text NOT LIKE 'SYS:%') GROUP BY endpointId) ORDER BY timestamp DESC")
    fun getRecentChatsFlow(localUserId: String): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE endpointId = :endpointId AND localUserId = :localUserId")
    fun deleteAllMessagesFrom(endpointId: String, localUserId: String)

    // Endpoints con mensajes propios aún no confirmados (incluye comandos SYS: de grupo),
    // usado para reintentos de sincronización: a diferencia de getRecentChats, no excluye SYS:.
    @Query("SELECT DISTINCT endpointId FROM messages WHERE localUserId = :localUserId AND isMine = 1 AND status IN (0, 1, -2)")
    fun getEndpointsWithPendingMessages(localUserId: String): List<String>

    @Query("SELECT imagePath FROM messages WHERE endpointId = :endpointId AND localUserId = :localUserId AND imagePath IS NOT NULL AND imagePath != '' ORDER BY timestamp DESC")
    fun getSharedImages(endpointId: String, localUserId: String): List<String>

    @Query("SELECT imagePath FROM messages WHERE localUserId = :localUserId AND imagePath IS NOT NULL AND imagePath != '' ORDER BY timestamp DESC")
    fun getAllSharedImages(localUserId: String): List<String>

    @Query("SELECT text FROM messages WHERE endpointId = :endpointId AND localUserId = :localUserId AND text LIKE '%http%' ORDER BY timestamp DESC")
    fun getSharedLinks(endpointId: String, localUserId: String): List<String>

    @Query("SELECT text FROM messages WHERE localUserId = :localUserId AND text LIKE '%http%' ORDER BY timestamp DESC")
    fun getAllSharedLinks(localUserId: String): List<String>

    @Query("DELETE FROM messages WHERE msgId = :id AND localUserId = :localUserId")
    fun deleteMessageById(id: Long, localUserId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE msgId = :msgId LIMIT 1)")
    fun messageExists(msgId: Long): Boolean

    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :currentTime")
    fun deleteExpiredMessages(currentTime: Long)

    @Query("DELETE FROM groups WHERE expirationTime IS NOT NULL AND expirationTime <= :currentTime")
    fun deleteExpiredGroups(currentTime: Long)

    @Query("DELETE FROM messages WHERE endpointId IN (SELECT groupId FROM groups WHERE expirationTime IS NOT NULL AND expirationTime <= :currentTime)")
    fun deleteMessagesOfExpiredGroups(currentTime: Long)

    @Query("UPDATE groups SET expirationTime = :newTime WHERE groupId = :groupId")
    fun updateGroupExpiration(groupId: String, newTime: Long)

    @Query("SELECT COUNT(*) FROM messages WHERE endpointId = :endpointId AND localUserId = :localUserId AND isMine = 0 AND status = 0")
    fun getUnreadCount(endpointId: String, localUserId: String): Int

    @Query("UPDATE messages SET status = 3 WHERE endpointId = :endpointId AND localUserId = :localUserId AND isMine = 0 AND status = 0")
    fun markAsRead(endpointId: String, localUserId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE localUserId = :localUserId")
    fun getTotalMessagesCount(localUserId: String): Int
}