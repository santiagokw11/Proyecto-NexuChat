package com.offline.nexu.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// Nuestra base de datos de Room. 
// Ojo: Si agregas tablas o columnas, acuérdate de subir la versión o esto va a crashear feo en los celulares.
@Database(entities = [MessageEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun nexuDao(): NexuDao

    companion object {
        // Singleton para la base de datos, porque no queremos tener 50 instancias abiertas a lo tonto
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nexu_database"
                )
                    // Evita crasheos al cambiar la estructura de las tablas 
                    // (Básicamente, borra todos los datos si cambiamos algo en dev. Muy peligroso en prod, pero útil ahorita xd)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}