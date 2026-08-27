package com.example.data.local

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.UserProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages JSON export/import of memory entries and user profile
 * to the external storage Downloads folder (e.g. /Download/AgentMemory/).
 */
object MemoryFileManager {

    private const val TAG = "MemoryFileManager"
    private const val FOLDER_NAME = "AgentMemory"
    private const val USER_MEMORY_FILE = "user_memory.json"
    private const val SYSTEM_CONFIG_FILE = "system_config.json"

    private fun getStorageDir(context: Context): File {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadDir, FOLDER_NAME)
        if (!targetDir.exists()) {
            val created = targetDir.mkdirs()
            if (!created) {
                // Fallback to internal app files directory if external isn't writable
                val fallbackDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER_NAME)
                fallbackDir.mkdirs()
                return fallbackDir
            }
        }
        return targetDir
    }

    /**
     * Exports user profile and all memory entries to external JSON files.
     */
    suspend fun exportMemoryToDownloads(
        context: Context,
        profile: UserProfileEntity?,
        memories: List<MemoryEntryEntity>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val targetDir = getStorageDir(context)

            // 1. Export user_memory.json
            val memoryJsonArray = JSONArray()
            for (entry in memories) {
                val obj = JSONObject().apply {
                    put("id", entry.id)
                    put("category", entry.category)
                    put("key", entry.key)
                    put("value", entry.value)
                    put("importance", entry.importance)
                    put("timestamp", entry.timestamp)
                }
                memoryJsonArray.put(obj)
            }

            val memoryFile = File(targetDir, USER_MEMORY_FILE)
            memoryFile.writeText(memoryJsonArray.toString(2), Charsets.UTF_8)

            // 2. Export system_config.json
            if (profile != null) {
                val profileObj = JSONObject().apply {
                    put("id", profile.id)
                    put("userName", profile.userName)
                    put("aiName", profile.aiName)
                    put("personalityTone", profile.personalityTone)
                    put("primaryExpectation", profile.primaryExpectation)
                    put("isAwakened", profile.isAwakened)
                    put("preferredAiProvider", profile.preferredAiProvider)
                    put("customApiKey", profile.customApiKey)
                    put("updatedAt", profile.updatedAt)
                }
                val configFile = File(targetDir, SYSTEM_CONFIG_FILE)
                configFile.writeText(profileObj.toString(2), Charsets.UTF_8)
            }

            Log.i(TAG, "Successfully exported ${memories.size} memories to ${targetDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export memories to Downloads folder", e)
            false
        }
    }

    /**
     * Imports user memory entries from Downloads folder if available.
     */
    suspend fun importMemoriesFromDownloads(context: Context): List<MemoryEntryEntity>? = withContext(Dispatchers.IO) {
        try {
            val targetDir = getStorageDir(context)
            val memoryFile = File(targetDir, USER_MEMORY_FILE)
            if (!memoryFile.exists() || !memoryFile.canRead()) {
                return@withContext null
            }

            val content = memoryFile.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(content)
            val list = mutableListOf<MemoryEntryEntity>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    MemoryEntryEntity(
                        id = obj.optLong("id", 0),
                        category = obj.optString("category", "USER_IDENTITY"),
                        key = obj.optString("key", ""),
                        value = obj.optString("value", ""),
                        importance = obj.optInt("importance", 1),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
            Log.i(TAG, "Imported ${list.size} memories from ${memoryFile.absolutePath}")
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error importing memories from file", e)
            null
        }
    }

    /**
     * Imports system config / user profile from Downloads folder if available.
     */
    suspend fun importProfileFromDownloads(context: Context): UserProfileEntity? = withContext(Dispatchers.IO) {
        try {
            val targetDir = getStorageDir(context)
            val configFile = File(targetDir, SYSTEM_CONFIG_FILE)
            if (!configFile.exists() || !configFile.canRead()) {
                return@withContext null
            }

            val content = configFile.readText(Charsets.UTF_8)
            val obj = JSONObject(content)

            UserProfileEntity(
                id = obj.optInt("id", 1),
                userName = obj.optString("userName", "Kullanıcı"),
                aiName = obj.optString("aiName", "Nova"),
                personalityTone = obj.optString("personalityTone", "WARM_ASSISTANT"),
                primaryExpectation = obj.optString("primaryExpectation", ""),
                isAwakened = obj.optBoolean("isAwakened", true),
                preferredAiProvider = obj.optString("preferredAiProvider", "gemini"),
                customApiKey = obj.optString("customApiKey", ""),
                updatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error importing profile from file", e)
            null
        }
    }
}
