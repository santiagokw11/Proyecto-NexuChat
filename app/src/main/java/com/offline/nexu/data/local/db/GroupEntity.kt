package com.offline.nexu.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val groupName: String,
    val groupAvatar: String?,
    val adminId: String,
    val members: String,
    val expirationTime: Long? = null, // NUEVO: Tiempo en que el grupo se autodestruye
    // Número de secuencia de la última actualización aplicada. Evita que un SYS:GROUP_UPDATE
    // viejo reenviado (reintento pendiente) revierta una edición más reciente ya aplicada.
    val version: Long = 1L,
    // Clave AES-256 (base64) del grupo, repartida una vez por miembro al crear/unir el grupo
    // (ver GroupManager). Null si todavía no se pudo envolver (peer sin llave pública conocida):
    // en ese caso los mensajes del grupo viajan con el fallback PLAIN: de CryptoManager.
    val encryptionKeyB64: String? = null
)