package com.example.ai

import android.util.Log
import com.example.data.model.ChatMessageEntity
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.MessageRole
import com.example.data.model.PersonalityTone
import com.example.data.model.UserProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GroqAIProvider(
    private val localFallback: SmartLocalAIProvider = SmartLocalAIProvider()
) : AIProvider {
    override val id: String = "groq"
    override val displayName: String = "Groq Cloud LPU"
    override val shortDescription: String = "Ultra hızlı LPU işlemci tabanlı GPT-OSS & Qwen modelleri."
    override val requiresApiKey: Boolean = true
    override val keyPlaceholder: String = "gsk_..."
    override val keyHint: String = "Groq Console'dan (console.groq.com) oluşturulan API Key"
    override val freeTierInfo: String = "Geliştiricilere cömert ve yüksek hızlı ücretsiz kota."
    override val isCloudBased: Boolean = true
    override val defaultModel: String = DEFAULT_MODEL
    override val availableModels: List<String> = listOf(
        "openai/gpt-oss-120b",
        "groq/compound",
        "qwen/qwen3.8-27b"
    )

    companion object {
        const val DEFAULT_MODEL = "openai/gpt-oss-120b"
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun validateCredentials(apiKey: String): ProviderValidationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ProviderValidationResult(false, "Groq API anahtarı boş olamaz.")
        }
        try {
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                ProviderValidationResult(true)
            } else {
                val code = response.code
                val errBody = response.body?.string() ?: ""
                val errorJson = try { JSONObject(errBody) } catch(e: Exception) { null }
                val remoteMsg = errorJson?.optJSONObject("error")?.optString("message") ?: "Doğrulama başarısız"
                
                if (code == 401 || code == 403) {
                    ProviderValidationResult(false, "Geçersiz Groq API Key.")
                } else {
                    ProviderValidationResult(false, "Groq bağlantı hatası (HTTP $code): $remoteMsg")
                }
            }
        } catch (e: Exception) {
            ProviderValidationResult(false, "Ağ bağlantı hatası: ${e.localizedMessage ?: "Sunucuya ulaşılamadı"}")
        }
    }

    override suspend fun generateResponse(
        prompt: String,
        conversationHistory: List<ChatMessageEntity>,
        memories: List<MemoryEntryEntity>,
        profile: UserProfileEntity?,
        overrideApiKey: String?,
        overrideModel: String?,
        onError: ((String) -> Unit)?
    ): Flow<String> = flow {
        val apiKey = overrideApiKey?.takeIf { it.isNotBlank() } ?: ""
        if (apiKey.isBlank()) {
            localFallback.generateResponse(prompt, conversationHistory, memories, profile).collect { emit(it) }
            return@flow
        }

        try {
            val userName = profile?.userName?.ifBlank { "Kullanıcı" } ?: "Kullanıcı"
            val aiName = profile?.aiName?.ifBlank { "Nova" } ?: "Nova"
            val tone = PersonalityTone.fromString(profile?.personalityTone)
            val topMemories = memories.take(3).joinToString("; ") { "${it.key}: ${it.value.take(40)}" }
            val memoryClause = if (topMemories.isNotBlank()) " Hafıza: $topMemories." else ""

            val messagesArr = JSONArray()
            val sysObj = JSONObject()
            sysObj.put("role", "system")
            val systemText = "Sen Android asistanı $aiName'sın. Kullanıcı: $userName.$memoryClause Ton: ${tone.displayName}. KURAL: Kullanıcıya her zaman MÜMKÜN OLAN EN KISA cevabı ver. Gereksiz hiçbir açıklama yapma, gevezelik etme. Eylem yapıyorsan sadece 'Açılıyor', 'Yapıldı', 'Tıklandı' veya 'Bulunamadı' de. Yanıtların Türkçe, ultra kısa ve net olsun."
            sysObj.put("content", systemText)
            messagesArr.put(sysObj)

            // Sliding Context Window: Include strictly the last 2 messages (1 Question + 1 Answer)
            val lastMessages = conversationHistory.takeLast(2)
            for (msg in lastMessages) {
                val text = msg.content.trim().take(300)
                val m = JSONObject().apply {
                    put("role", if (msg.role == MessageRole.USER.name) "user" else "assistant")
                    put("content", text)
                }
                messagesArr.put(m)
            }

            val curObj = JSONObject()
            curObj.put("role", "user")
            curObj.put("content", prompt.take(1500))
            messagesArr.put(curObj)

            val selectedModel = if (overrideModel != null && availableModels.contains(overrideModel)) {
                overrideModel
            } else {
                DEFAULT_MODEL
            }
            val rootJson = JSONObject()
            rootJson.put("model", selectedModel)
            rootJson.put("messages", messagesArr)
            rootJson.put("temperature", 0.7)
            rootJson.put("max_tokens", 800)

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .post(rootJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                val errBody = response.body?.string() ?: ""
                val errorJson = try { JSONObject(errBody) } catch(e: Exception) { null }
                val remoteMsg = errorJson?.optJSONObject("error")?.optString("message") ?: errBody
                
                val errorMsg = when (code) {
                    401, 403 -> "Groq Hatası: 401 Unauthorized (API Key Geçersiz)"
                    429 -> "Groq Hatası: 429 Rate Limit (Kota Doldu)"
                    400 -> "Groq Hatası: 400 Bad Request ($remoteMsg)"
                    404 -> "Groq Hatası: 404 Not Found (Geçersiz Model: $selectedModel)"
                    else -> "Groq Hatası: HTTP $code ($remoteMsg)"
                }
                Log.e("GroqAIProvider", "Groq API error ($code): $errBody")
                onError?.invoke(errorMsg)
                localFallback.generateResponse(prompt, conversationHistory, memories, profile).collect { emit(it) }
                return@flow
            }

            Log.d("GroqAIProvider", "API Connection Success")
            val respStr = response.body?.string() ?: ""
            val respJson = JSONObject(respStr)
            val choices = respJson.getJSONArray("choices")
            val firstChoice = choices.getJSONObject(0)
            val answer = firstChoice.getJSONObject("message").getString("content")

            if (answer.isNotBlank()) {
                val words = answer.split(" ")
                val buffer = StringBuilder()
                for (i in words.indices) {
                    if (i > 0) buffer.append(" ")
                    buffer.append(words[i])
                    emit(buffer.toString())
                    delay(12)
                }
            } else {
                localFallback.generateResponse(prompt, conversationHistory, memories, profile).collect { emit(it) }
            }
        } catch (e: Exception) {
            Log.e("GroqAIProvider", "Error in Groq API", e)
            localFallback.generateResponse(prompt, conversationHistory, memories, profile).collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)
}
