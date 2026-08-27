package com.example.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * Manages secure API keys and tokens independently from the Room memory database.
 * Keys are kept strictly private and never exposed to logs or public memory queries.
 */
class CredentialStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("secure_assistant_vault", Context.MODE_PRIVATE)

    fun saveApiKey(providerId: String, key: String) {
        if (key.isBlank()) {
            prefs.edit().remove(keyFor(providerId)).apply()
        } else {
            // Obfuscate in storage
            val encoded = Base64.encodeToString(key.trim().toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            prefs.edit().putString(keyFor(providerId), encoded).apply()
        }
    }

    fun getApiKey(providerId: String): String {
        val encoded = prefs.getString(keyFor(providerId), null) ?: return ""
        return try {
            val decoded = Base64.decode(encoded, Base64.NO_WRAP)
            String(decoded, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun hasApiKey(providerId: String): Boolean {
        return getApiKey(providerId).isNotBlank()
    }

    fun saveSelectedModel(providerId: String, model: String) {
        if (model.isBlank()) {
            prefs.edit().remove("model_${providerId.lowercase()}").apply()
        } else {
            prefs.edit().putString("model_${providerId.lowercase()}", model.trim()).apply()
        }
    }

    fun getSelectedModel(providerId: String, defaultModel: String = ""): String {
        return prefs.getString("model_${providerId.lowercase()}", defaultModel)?.takeIf { it.isNotBlank() } ?: defaultModel
    }

    fun clearAllCredentials() {
        prefs.edit().clear().apply()
    }

    private fun keyFor(providerId: String): String = "sec_key_${providerId.lowercase()}"
}
