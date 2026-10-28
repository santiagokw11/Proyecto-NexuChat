package com.offline.nexu.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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

    @Query("UPDATE messages SET status = :newStatus WHERE msgId = :msgId")
    fun updateStatus(msgId: Long, newStatus: Int)

    @Query("SELECT * FROM messages WHERE localUserId = :localUserId AND msgId IN (SELECT MAX(msgId) FROM messages WHERE localUserId = :localUserId GROUP BY endpointId) ORDER BY timestamp DESC")
    fun getRecentChats(localUserId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE endpointId = :endpointId AND localUserId = :localUserId")
    fun deleteAllMessagesFrom(endpointId: String, localUserId: String)

    @Query("SELECT imagePath FROM messages WHERE endpointId = :endpointId AND localUserId = :localUserId AND imagePath IS NOT NULL AND imagePath != '' ORDER BY timestamp DESC")
    fun getSharedImages(endpointId: String, localUserId: String): List<String>

    @Query("DELETE FROM messages WHERE msgId = :id")
    fun deleteMessageById(id: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE msgId = :msgId LIMIT 1)")
    fun messageExists(msgId: Long): Boolean

    @Query("DELETE FROM messages WHERE expiresAt IS NOT NULL AND expiresAt <= :currentTime")
    fun deleteExpiredMessages(currentTime: Long)

    @Query("DELETE FROM groups WHERE expirationTime IS NOT NULL AND expirationTime <= :currentTime")
    fun deleteExpiredGroups(currentTime: Long)

    @Query("DELETE FROM messages WHERE endpointId IN (SELECT groupId FROM groups WHERE expirationTime IS NOT NULL AND expirationTime <= :currentTime)")
    fun deleteMessagesOfExpiredGroups(currentTime: Long)

    // NUEVO: Permite sumar tiempo de vida al grupo
    @Query("UPDATE groups SET expirationTime = :newTime WHERE groupId = :groupId")
    fun updateGroupExpiration(groupId: String, newTime: Long)
}