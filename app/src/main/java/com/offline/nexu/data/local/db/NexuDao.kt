package com.offline.nexu.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NexuDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    // Filtra por el chat Y por el usuario logueado
    @Query("SELECT * FROM messages WHERE endpointId = :endpointId AND localUserId = :localUserId ORDER BY timestamp ASC")
    fun getMessagesForUserSync(endpointId: String, localUserId: String): List<MessageEntity>

    @Query("UPDATE messages SET status = :newStatus WHERE msgId = :msgId")
    fun updateStatus(msgId: Long, newStatus: Int)

    // Obtiene el último mensaje de cada chat, solo para el usuario logueado
    @Query("SELECT * FROM messages WHERE localUserId = :localUserId AND msgId IN (SELECT MAX(msgId) FROM messages WHERE localUserId = :localUserId GROUP BY endpointId) ORDER BY timestamp DESC")
    fun getRecentChats(localUserId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE endpointId = :endpointId AND localUserId = :localUserId")
    fun deleteAllMessagesFrom(endpointId: String, localUserId: String)

    @Query("SELECT imagePath FROM messages WHERE endpointId = :endpointId AND localUserId = :localUserId AND imagePath IS NOT NULL AND imagePath != '' ORDER BY timestamp DESC")
    fun getSharedImages(endpointId: String, localUserId: String): List<String>

    @Query("DELETE FROM messages WHERE msgId = :id")
    fun deleteMessageById(id: Long)

    @Query("SELECT * FROM groups WHERE groupId = :groupId LIMIT 1")
    fun getGroup(groupId: String): GroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertGroup(group: GroupEntity)

    @Query("UPDATE groups SET groupAvatar = :path WHERE groupId = :groupId")
    fun updateGroupAvatar(groupId: String, path: String)
}