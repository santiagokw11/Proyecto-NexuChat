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
    val version: Long = 1L
)