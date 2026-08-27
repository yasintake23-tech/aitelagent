package com.example.ai

import com.example.data.model.ChatMessageEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.UserProfileEntity
import com.example.data.repository.MemoryRepository
import com.example.data.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import java.util.Locale

class AIProviderManager(
    private val memoryRepository: MemoryRepository,
    private val credentialStore: CredentialStore
) {
    private val localProvider = SmartLocalAIProvider()
    private val geminiProvider = GeminiAIProvider(localFallback = localProvider)
    private val huggingFaceProvider = HuggingFaceAIProvider(localFallback = localProvider)
    private val groqProvider = GroqAIProvider(localFallback = localProvider)

    private val providerList: List<AIProvider> = listOf(
        geminiProvider,
        huggingFaceProvider,
        groqProvider,
        localProvider
    )

    private val providersMap: Map<String, AIProvider> = providerList.associateBy { it.id.lowercase(Locale.ROOT) }

    init {
        // Sanitize stored models on startup. If an obsolete model is stored in preferences, reset to default.
        sanitizeStoredModels()
    }

    fun sanitizeStoredModels() {
        for (provider in providerList) {
            val savedModel = credentialStore.getSelectedModel(provider.id, provider.defaultModel)
            if (provider.availableModels.isNotEmpty() && !provider.availableModels.contains(savedModel)) {
                credentialStore.saveSelectedModel(provider.id, provider.defaultModel)
            }
        }
    }

    fun getAvailableProviders(): List<AIProvider> = providerList

    fun getProvider(id: String): AIProvider {
        return providersMap[id.lowercase(Locale.ROOT)] ?: geminiProvider
    }

    suspend fun validateProviderCredentials(providerId: String, apiKey: String): ProviderValidationResult {
        val provider = getProvider(providerId)
        return provider.validateCredentials(apiKey)
    }

    suspend fun processUserPrompt(
        prompt: String,
        conversationHistory: List<ChatMessageEntity>,
        profile: UserProfileEntity?,
        onError: ((String) -> Unit)? = null
    ): Flow<String> {
        // Auto extract explicit memory requests e.g. "Bunu hatırla: ...", "Hafızana yaz: ..."
        detectAndSaveMemory(prompt)

        val memories = memoryRepository.getAllMemoriesOnce()
        val preferredKey = profile?.preferredAiProvider?.lowercase(Locale.ROOT) ?: "gemini"
        val activeProvider = providersMap[preferredKey] ?: geminiProvider

        // Retrieve API key securely from CredentialStore (or fallback to profile legacy key if set)
        val secureKey = credentialStore.getApiKey(activeProvider.id).ifBlank {
            profile?.customApiKey ?: ""
        }

        // Retrieve selected model (or default model for provider), validating against availableModels
        val rawSelectedModel = credentialStore.getSelectedModel(activeProvider.id, activeProvider.defaultModel)
        val selectedModel = if (activeProvider.availableModels.isNotEmpty() && !activeProvider.availableModels.contains(rawSelectedModel)) {
            credentialStore.saveSelectedModel(activeProvider.id, activeProvider.defaultModel)
            activeProvider.defaultModel
        } else {
            rawSelectedModel
        }

        return activeProvider.generateResponse(
            prompt = prompt,
            conversationHistory = conversationHistory,
            memories = memories,
            profile = profile,
            overrideApiKey = secureKey,
            overrideModel = selectedModel,
            onError = onError
        )
    }

    fun getApiKey(providerId: String): String {
        return credentialStore.getApiKey(providerId)
    }

    fun getSelectedModel(providerId: String): String {
        val provider = getProvider(providerId)
        val savedModel = credentialStore.getSelectedModel(provider.id, provider.defaultModel)
        if (provider.availableModels.isNotEmpty() && !provider.availableModels.contains(savedModel)) {
            credentialStore.saveSelectedModel(provider.id, provider.defaultModel)
            return provider.defaultModel
        }
        return savedModel
    }

    fun setSelectedModel(providerId: String, model: String) {
        credentialStore.saveSelectedModel(providerId, model)
    }

    fun getAvailableModels(providerId: String): List<String> {
        return getProvider(providerId).availableModels
    }

    private suspend fun detectAndSaveMemory(prompt: String) {
        val lower = prompt.lowercase(Locale("tr", "TR")).trim()
        val explicitMemoryTriggers = listOf(
            "bunu hatırla" to MemoryCategory.IMPORTANT_FACT,
            "bunu kaydet" to MemoryCategory.IMPORTANT_FACT,
            "hafızana ekle" to MemoryCategory.IMPORTANT_FACT,
            "hafızana yaz" to MemoryCategory.IMPORTANT_FACT,
            "şunu unutma" to MemoryCategory.IMPORTANT_FACT,
            "unutma ki" to MemoryCategory.IMPORTANT_FACT,
            "şunu not al" to MemoryCategory.IMPORTANT_FACT,
            "not al" to MemoryCategory.IMPORTANT_FACT,
            "benim adım" to MemoryCategory.USER_IDENTITY
        )

        for ((trigger, category) in explicitMemoryTriggers) {
            if (lower.contains(trigger)) {
                val value = prompt.substringAfter(trigger).trim(':', ' ', '-', '"').ifBlank { prompt }
                if (value.isNotBlank() && value.length > 2) {
                    memoryRepository.insertMemory(
                        category = category,
                        key = trigger.replace(":", "").trim().replaceFirstChar { it.uppercase() },
                        value = value,
                        importance = 2
                    )
                    break
                }
            }
        }
    }
}

