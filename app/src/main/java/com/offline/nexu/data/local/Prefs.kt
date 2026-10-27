package com.offline.nexu.data.local

import android.content.Context
import android.content.SharedPreferences
import com.offline.nexu.data.model.UserProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class Prefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("NexuPrefs", Context.MODE_PRIVATE)

    fun getUserProfile(): UserProfile {
        val currentId = prefs.getString("current_session_id", null) ?: return UserProfile()
        val accountsArray = getSavedAccountsArray()

        for (i in 0 until accountsArray.length()) {
            val acc = accountsArray.getJSONObject(i)
            if (acc.getString("id") == currentId) {
                return UserProfile.fromJson(acc.getString("profile"))
            }
        }
        return UserProfile()
    }

    fun saveUserProfile(profile: UserProfile) {
        val pass = getCurrentPassword(profile.id) ?: "1234"
        saveAccount(profile, pass)
    }

    private fun getCurrentPassword(userId: String): String? {
        val accountsArray = getSavedAccountsArray()
        for (i in 0 until accountsArray.length()) {
            val acc = accountsArray.getJSONObject(i)
            if (acc.getString("id") == userId) {
                return acc.getString("password")
            }
        }
        return null
    }

    fun saveAccount(profile: UserProfile, pass: String) {
        val accountsArray = getSavedAccountsArray()
        var exists = false

        for (i in 0 until accountsArray.length()) {
            val acc = accountsArray.getJSONObject(i)
            if (acc.getString("id") == profile.id) {
                acc.put("profile", profile.toJson())
                acc.put("password", pass)
                exists = true
                break
            }
        }

        if (!exists) {
            val newAcc = JSONObject()
            newAcc.put("id", profile.id)
            newAcc.put("profile", profile.toJson())
            newAcc.put("password", pass)
            accountsArray.put(newAcc)
        }

        prefs.edit().putString("saved_accounts", accountsArray.toString()).apply()
        setCurrentSession(profile.id)
    }

    // NUEVA FUNCIÓN: Eliminar una cuenta de la bóveda
    fun deleteAccount(userId: String) {
        val accountsArray = getSavedAccountsArray()
        val newArray = JSONArray()

        for (i in 0 until accountsArray.length()) {
            val acc = accountsArray.getJSONObject(i)
            if (acc.getString("id") != userId) {
                newArray.put(acc)
            }
        }
        prefs.edit().putString("saved_accounts", newArray.toString()).apply()
    }

    fun getSavedAccountsArray(): JSONArray {
        val jsonString = prefs.getString("saved_accounts", "[]")
        return JSONArray(jsonString)
    }

    fun setCurrentSession(userId: String) {
        prefs.edit().putString("current_session_id", userId).apply()
    }

    fun logout() {
        prefs.edit().remove("current_session_id").apply()
    }

    fun isRegistered(): Boolean {
        return prefs.contains("current_session_id")
    }

    fun updatePassword(newPass: String) {
        val profile = getUserProfile()
        saveAccount(profile, newPass)
    }

    fun verifyPassword(userId: String, inputPass: String): Boolean {
        val accountsArray = getSavedAccountsArray()
        for (i in 0 until accountsArray.length()) {
            val acc = accountsArray.getJSONObject(i)
            if (acc.getString("id") == userId) {
                return acc.getString("password") == inputPass
            }
        }
        return false
    }

    fun saveContactAvatar(contactName: String, avatarData: String) {
        prefs.edit().putString("avatar_$contactName", avatarData).apply()
    }

    fun getContactAvatar(contactName: String): String {
        return prefs.getString("avatar_$contactName", "👤") ?: "👤"
    }

    fun getDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString().substring(0, 8)
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}