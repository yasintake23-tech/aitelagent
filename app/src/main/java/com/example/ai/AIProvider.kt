package com.example.ai

import com.example.data.model.ChatMessageEntity
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.UserProfileEntity
import kotlinx.coroutines.flow.Flow

data class ProviderValidationResult(
    val isSuccess: Boolean,
    val errorMessage: String? = null
)

interface AIProvider {
    val id: String
    val displayName: String
    val shortDescription: String
    val requiresApiKey: Boolean
    val keyPlaceholder: String
    val keyHint: String
    val freeTierInfo: String
    val isCloudBased: Boolean
    val defaultModel: String get() = ""
    val availableModels: List<String> get() = emptyList()

    suspend fun validateCredentials(apiKey: String): ProviderValidationResult

    suspend fun generateResponse(
        prompt: String,
        conversationHistory: List<ChatMessageEntity>,
        memories: List<MemoryEntryEntity>,
        profile: UserProfileEntity?,
        overrideApiKey: String? = null,
        overrideModel: String? = null,
        onError: ((String) -> Unit)? = null
    ): Flow<String>
}

