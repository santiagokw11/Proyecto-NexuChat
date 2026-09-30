package com.offline.nexu.data.model

import org.json.JSONObject
import java.util.UUID

data class UserProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "Usuario",
    var avatar: String = "👤",
    var colorHex: String = "#FF6200EE",
    var customAttributes: Map<String, String> = emptyMap()
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        json.put("avatar", avatar)
        json.put("colorHex", colorHex)
        val attributesJson = JSONObject()
        customAttributes.forEach { (key, value) -> attributesJson.put(key, value) }
        json.put("attributes", attributesJson)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonString: String): UserProfile {
            return try {
                val json = JSONObject(jsonString)
                val attributesJson = json.optJSONObject("attributes")
                val attributesMap = mutableMapOf<String, String>()
                if (attributesJson != null) {
                    val keys = attributesJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        attributesMap[key] = attributesJson.getString(key)
                    }
                }
                UserProfile(
                    id = json.optString("id", UUID.randomUUID().toString()),
                    name = json.optString("name", "Usuario"),
                    avatar = json.optString("avatar", "👤"),
                    colorHex = json.optString("colorHex", "#FF6200EE"),
                    customAttributes = attributesMap
                )
            } catch (e: Exception) {
                UserProfile()
            }
        }
    }
}