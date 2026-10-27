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
    val expirationTime: Long? = null // NUEVO: Tiempo en que el grupo se autodestruye
)