package com.offline.nexu.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NexuDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE endpointId = :endpointId ORDER BY timestamp ASC")
    fun getMessagesForUserSync(endpointId: String): List<MessageEntity>

    @Query("UPDATE messages SET status = :newStatus WHERE msgId = :msgId")
    fun updateStatus(msgId: Long, newStatus: Int)

    @Query("SELECT * FROM messages WHERE id IN (SELECT MAX(id) FROM messages GROUP BY endpointId) ORDER BY timestamp DESC")
    fun getRecentChats(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE endpointId = :endpointId")
    fun deleteAllMessagesFrom(endpointId: String)

    // NUEVA CONSULTA: Obtener solo rutas de imágenes que no sean nulas ni vacías
    @Query("SELECT imagePath FROM messages WHERE endpointId = :endpointId AND imagePath IS NOT NULL AND imagePath != '' ORDER BY timestamp DESC")
    fun getSharedImages(endpointId: String): List<String>
}