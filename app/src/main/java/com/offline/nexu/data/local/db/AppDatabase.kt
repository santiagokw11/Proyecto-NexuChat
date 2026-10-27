package com.offline.nexu.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// ¡CAMBIO AQUÍ! Pasamos la versión a 3
@Database(entities = [MessageEntity::class, GroupEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nexuDao(): NexuDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nexu_database"
                )
                    .fallbackToDestructiveMigration() // Al detectar la versión 3, recreará las tablas limpias
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}