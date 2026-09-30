package com.offline.nexu.data.local

import android.content.Context
import android.content.SharedPreferences
import com.offline.nexu.data.model.UserProfile
import java.util.UUID

class Prefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)

    fun getUserProfile(): UserProfile {
        // Intenta leer el JSON si tienes tu lógica antigua
        val jsonString = prefs.getString("user_profile_data", null)
        if (jsonString != null) {
            try {
                // Si tienes un método fromJson en tu modelo, lo usamos
                return UserProfile.fromJson(jsonString)
            } catch (e: Exception) {
                // Si falla, caemos en la lectura normal abajo
            }
        }

        // Manejamos las variables antiguas y nuevas para que no se pierda tu sesión
        val name = prefs.getString("user_name", prefs.getString("username", "Astronauta")) ?: "Astronauta"
        val avatar = prefs.getString("user_avatar", prefs.getString("avatar", "👤")) ?: "👤"
        val color = prefs.getString("user_color", "#00BCD4") ?: "#00BCD4"

        return UserProfile(name, avatar, color)
    }

    fun saveUserProfile(profile: UserProfile) {
        // Guardado de compatibilidad para evitar cierres de sesión
        prefs.edit()
            .putString("user_name", profile.name)
            .putString("username", profile.name)
            .putString("user_avatar", profile.avatar)
            .putString("avatar", profile.avatar)
            .putString("user_color", profile.colorHex)
            .apply()

        try {
            prefs.edit().putString("user_profile_data", profile.toJson()).apply()
        } catch (e: Exception) {
            // Ignorado si no existe toJson() en tu modelo
        }
    }

    fun saveContactAvatar(contactName: String, avatarData: String) {
        prefs.edit().putString("avatar_$contactName", avatarData).apply()
    }

    fun getContactAvatar(contactName: String): String {
        return prefs.getString("avatar_$contactName", "👤") ?: "👤"
    }

    // Generador de ID único para evitar auto-descubrimiento en el Radar
    fun getDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    // ---> MÉTODOS RECUPERADOS PARA SOLUCIONAR LOS ERRORES DE COMPILACIÓN <---

    fun isRegistered(): Boolean {
        return prefs.contains("username") || prefs.contains("user_name") || prefs.contains("user_profile_data")
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}