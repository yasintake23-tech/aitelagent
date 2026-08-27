package com.example.data.repository

import com.example.data.local.MemoryDao
import com.example.data.local.UserProfileDao
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.PersonalityTone
import com.example.data.model.UserProfileEntity
import kotlinx.coroutines.flow.Flow

class MemoryRepository(
    private val userProfileDao: UserProfileDao,
    private val memoryDao: MemoryDao
) {
    val userProfile: Flow<UserProfileEntity?> = userProfileDao.getUserProfile()
    val allMemories: Flow<List<MemoryEntryEntity>> = memoryDao.getAllMemories()

    suspend fun getUserProfileOnce(): UserProfileEntity? = userProfileDao.getUserProfileOnce()

    suspend fun getAllMemoriesOnce(): List<MemoryEntryEntity> = memoryDao.getAllMemoriesOnce()

    suspend fun saveUserProfile(profile: UserProfileEntity) {
        userProfileDao.insertOrUpdateProfile(profile)
    }

    suspend fun updatePersonalityTone(tone: PersonalityTone) {
        val current = getUserProfileOnce() ?: UserProfileEntity()
        userProfileDao.insertOrUpdateProfile(
            current.copy(personalityTone = tone.name, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun updateAiName(name: String) {
        val current = getUserProfileOnce() ?: UserProfileEntity()
        userProfileDao.insertOrUpdateProfile(
            current.copy(aiName = name, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun updateUserName(name: String) {
        val current = getUserProfileOnce() ?: UserProfileEntity()
        userProfileDao.insertOrUpdateProfile(
            current.copy(userName = name, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun updateCustomApiKey(apiKey: String) {
        val current = getUserProfileOnce() ?: UserProfileEntity()
        userProfileDao.insertOrUpdateProfile(
            current.copy(customApiKey = apiKey, updatedAt = System.currentTimeMillis())
        )
    }

    suspend fun completeAwakening(
        aiName: String,
        userName: String,
        tone: PersonalityTone,
        expectation: String
    ) {
        val profile = UserProfileEntity(
            id = 1,
            userName = userName,
            aiName = aiName,
            personalityTone = tone.name,
            primaryExpectation = expectation,
            isAwakened = true,
            updatedAt = System.currentTimeMillis()
        )
        userProfileDao.insertOrUpdateProfile(profile)

        // Seed initial core memories from the awakening interaction
        val initialMemories = listOf(
            MemoryEntryEntity(
                category = MemoryCategory.USER_IDENTITY.name,
                key = "Kullanıcı Adı",
                value = userName,
                importance = 3
            ),
            MemoryEntryEntity(
                category = MemoryCategory.USER_IDENTITY.name,
                key = "AI Adı",
                value = aiName,
                importance = 3
            ),
            MemoryEntryEntity(
                category = MemoryCategory.PREFERENCE.name,
                key = "İletişim Tonu",
                value = tone.displayName + " (${tone.description})",
                importance = 2
            ),
            MemoryEntryEntity(
                category = MemoryCategory.PREFERENCE.name,
                key = "Temel Beklenti",
                value = expectation,
                importance = 2
            ),
            MemoryEntryEntity(
                category = MemoryCategory.SYSTEM.name,
                key = "Doğuş Zamanı",
                value = "İlk bilinç ve tanışma başarıyla tamamlandı.",
                importance = 1
            )
        )
        memoryDao.insertMemories(initialMemories)
    }

    suspend fun insertMemory(category: MemoryCategory, key: String, value: String, importance: Int = 1): Long {
        return memoryDao.insertMemory(
            MemoryEntryEntity(
                category = category.name,
                key = key,
                value = value,
                importance = importance
            )
        )
    }

    suspend fun deleteMemory(id: Long) {
        memoryDao.deleteMemoryById(id)
    }

    suspend fun resetAll() {
        userProfileDao.clearProfile()
        memoryDao.clearAllMemories()
    }
}
