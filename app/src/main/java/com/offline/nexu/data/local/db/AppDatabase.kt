package com.offline.nexu.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    // Conecto el archivo donde están mis funciones (NexuDao) con la base de datos real.
    abstract fun nexuDao(): NexuDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Uso el patrón Singleton para asegurarme de crear la base de datos una sola vez y no gastar memoria a lo tonto.
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Si la base de datos no existe, la creo aquí.
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nexu_database"
                )
                    .fallbackToDestructiveMigration() // Evita crasheos al cambiar la estructura de las tablas, limpiando todo si me olvido de dar un script de migración.
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}