package com.offline.nexu.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NexuDao {
    // Si recibo o envío un mensaje, lo guardo. Si ya existe un mensaje con ese mismo ID, lo reemplazo (REPLACE).
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    // Me traigo todos los mensajes que he intercambiado con un contacto en específico y los ordeno del más viejo al más nuevo.
    @Query("SELECT * FROM messages WHERE endpointId = :endpointId ORDER BY timestamp ASC")
    fun getMessagesForUserSync(endpointId: String): List<MessageEntity>

    // Esta la uso cuando mi mensaje fue entregado y leído para ponerle las famosas palomitas azules.
    @Query("UPDATE messages SET status = :newStatus WHERE msgId = :msgId")
    fun updateStatus(msgId: Long, newStatus: Int)

    // Para la lista de chats, agrupo los mensajes por contacto y solo me traigo el ÚLTIMO mensaje de cada uno.
    @Query("SELECT * FROM messages WHERE id IN (SELECT MAX(id) FROM messages GROUP BY endpointId) ORDER BY timestamp DESC")
    fun getRecentChats(): List<MessageEntity>

    // Me sirve para vaciar un chat completo. Borro todo lo que pertenezca a ese usuario.
    @Query("DELETE FROM messages WHERE endpointId = :endpointId")
    fun deleteAllMessagesFrom(endpointId: String)

    // Con esta función reviso toda la conversación buscando específicamente mensajes que tengan una foto (imagePath). 
    // Luego, mando todas las rutas al MediaVaultActivity.
    @Query("SELECT imagePath FROM messages WHERE endpointId = :endpointId AND imagePath IS NOT NULL AND imagePath != '' ORDER BY timestamp DESC")
    fun getSharedImages(endpointId: String): List<String>
}