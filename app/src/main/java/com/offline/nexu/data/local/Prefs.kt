package com.offline.nexu.data.local

import android.content.Context
import android.content.SharedPreferences
import com.offline.nexu.data.model.UserProfile

class Prefs(context: Context) {
    private val storage: SharedPreferences = context.getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)

    fun saveUser(user: String, pass: String, avatar: String) {
        storage.edit().apply {
            putString("username", user)
            putString("password", pass)
            putString("avatar", avatar)
            apply()
        }
    }

    fun getUsername(): String = storage.getString("username", "") ?: ""
    fun getAvatar(): String = storage.getString("avatar", "👤") ?: "👤"
    fun isRegistered(): Boolean = storage.contains("username")

    fun getUserProfile(): UserProfile {
        val jsonString = storage.getString("user_profile", null)
        return if (jsonString != null) {
            UserProfile.fromJson(jsonString)
        } else {
            UserProfile(
                name = getUsername(),
                avatar = getAvatar()
            )
        }
    }

    fun saveUserProfile(profile: UserProfile) {
        storage.edit().apply {
            putString("user_profile", profile.toJson())
            apply()
        }
    }
}