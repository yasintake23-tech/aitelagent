package com.example.ai

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.ChatMessageEntity
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.MessageRole
import com.example.data.model.PersonalityTone
import com.example.data.model.UserProfileEntity
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GeminiPart(val text: String? = null)

@JsonClass(generateAdapter = true)
data class GeminiContent(val role: String? = null, val parts: List<GeminiPart>)

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(val content: GeminiContent?)

@JsonClass(generateAdapter = true)
data class GeminiResponse(val candidates: List<GeminiCandidate>? = null)

class GeminiAIProvider(
    private val localFallback: SmartLocalAIProvider = SmartLocalAIProvider()
) : AIProvider {
    override val id: String = "gemini"
    override val displayName: String = "Google AI / Gemini"
    override val shortDescription: String = "Google'ın yeni nesil akıllı Gemini modelleri."
    override val requiresApiKey: Boolean = true
    override val keyPlaceholder: String = "AIzaSy..."
    override val keyHint: String = "Google AI Studio'dan aldığınız API anahtarı"
    override val freeTierInfo: String = "Geliştirici hesabı ile cömert ücretsiz kota sunulur."
    override val isCloudBased: Boolean = true
    override val defaultModel: String = "gemini-2.5-flash"
    override val availableModels: List<String> = listOf(
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "gemini-1.5-flash",
        "gemini-1.5-pro"
    )

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun validateCredentials(apiKey: String): ProviderValidationResult = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ProviderValidationResult(false, "API anahtarı boş olamaz.")
        }
        try {
            val testBody = """
                {"contents":[{"role":"user","parts":[{"text":"ping"}]}]}
            """.trimIndent()

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${apiKey.trim()}"
            val request = Request.Builder()
                .url(url)
                .post(testBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                ProviderValidationResult(true)
            } else {
                val code = response.code
                val err = when (code) {
                    400, 403 -> "Geçersiz API Anahtarı. Lütfen kontrol ediniz."
                    429 -> "Kota sınırı aşıldı. Lütfen daha sonra tekrar deneyiniz."
                    else -> "Bağlantı hatası (HTTP $code)."
                }
                ProviderValidationResult(false, err)
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
        // Resolve API key
        val apiKey = overrideApiKey?.takeIf { it.isNotBlank() }
            ?: (try { BuildConfig.GEMINI_API_KEY } catch (e: Throwable) { "" })

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            Log.d("GeminiAIProvider", "No valid Gemini API key found. Using intelligent local engine.")
            localFallback.generateResponse(prompt, conversationHistory, memories, profile).collect {
                emit(it)
            }
            return@flow
        }

        try {
            val userName = profile?.userName?.ifBlank { "Kullanıcı" } ?: "Kullanıcı"
            val aiName = profile?.aiName?.ifBlank { "Nova" } ?: "Nova"
            val tone = PersonalityTone.fromString(profile?.personalityTone)
            val expectation = profile?.primaryExpectation ?: "Genel Yardım"

            val memoryContext = memories.take(10).joinToString("\n- ") { "${it.key}: ${it.value}" }

            val systemPrompt = """
                Sen Android üzerinde çalışan yapay zekâ asistanısın.
                Adın: $aiName
                Kullanıcı: $userName
                İletişim Tonu: ${tone.displayName}
                Kalıcı Hafıza:
                - $memoryContext
                
                KATI KURALLAR:
                1. KULLANICIYA HER ZAMAN MÜMKÜN OLAN EN KISA CEVABI VER. Gereksiz hiçbir açıklama yapma, gevezelik etme.
                2. Bir eylem yapıyorsan veya yaptıysan sadece 'Açılıyor', 'Yapıldı', 'Tıklandı' veya 'Bulunamadı' de.
                3. Türkçe konuş, tek cümleyle veya birkaç kelimeyle öz ve net yanıt ver.
            """.trimIndent()

            // Prepare history (last 8 turns)
            val contentsList = mutableListOf<GeminiContent>()
            val recentHistory = conversationHistory.takeLast(8)
            for (msg in recentHistory) {
                val role = if (msg.role == MessageRole.USER.name) "user" else "model"
                contentsList.add(
                    GeminiContent(
                        role = role,
                        parts = listOf(GeminiPart(text = msg.content))
                    )
                )
            }
            // Add current prompt
            contentsList.add(
                GeminiContent(
                    role = "user",
                    parts = listOf(GeminiPart(text = prompt))
                )
            )

            val requestObj = GeminiRequest(
                contents = contentsList,
                systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt)))
            )

            val jsonAdapter = moshi.adapter(GeminiRequest::class.java)
            val requestBodyJson = jsonAdapter.toJson(requestObj)

            val selectedModel = overrideModel?.takeIf { it.isNotBlank() } ?: defaultModel
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$selectedModel:generateContent?key=$apiKey"

            val request = Request.Builder()
                .url(url)
                .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                val errBody = response.body?.string() ?: ""
                val errorMsg = if (code == 401 || code == 403) {
                    "API Hatanız: 401 Unauthorized (Geçersiz Key)"
                } else if (code == 429) {
                    "API Hatanız: 429 Quota Exceeded (Kota Doldu)"
                } else {
                    "API Hatanız: HTTP $code"
                }
                
                Log.e("GeminiAIProvider", "Gemini API error ($code): $errBody")
                onError?.invoke(errorMsg)

                localFallback.generateResponse(prompt, conversationHistory, memories, profile).collect {
                    emit(it)
                }
                return@flow
            }

            Log.d("GeminiAIProvider", "API Connection Success")
            val respBodyStr = response.body?.string()
            val respAdapter = moshi.adapter(GeminiResponse::class.java)
            val geminiResp = respAdapter.fromJson(respBodyStr ?: "{}")
            val generatedText = geminiResp?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

            if (!generatedText.isNullOrBlank()) {
                // Stream text smoothly to UI
                val words = generatedText.split(" ")
                val buffer = StringBuilder()
                for (i in words.indices) {
                    if (i > 0) buffer.append(" ")
                    buffer.append(words[i])
                    emit(buffer.toString())
                    delay(15)
                }
            } else {
                localFallback.generateResponse(prompt, conversationHistory, memories, profile).collect {
                    emit(it)
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiAIProvider", "Exception in Gemini API: ${e.message}", e)
            localFallback.generateResponse(prompt, conversationHistory, memories, profile).collect {
                emit(it)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun generateStructuralContent(
        systemPrompt: String,
        userPrompt: String,
        apiKey: String,
        model: String
    ): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val requestObj = GeminiRequest(
            contents = listOf(
                GeminiContent(
                    role = "user",
                    parts = listOf(GeminiPart(text = userPrompt))
                )
            ),
            systemInstruction = GeminiContent(parts = listOf(GeminiPart(text = systemPrompt)))
        )

        val jsonAdapter = moshi.adapter(GeminiRequest::class.java)
        val requestBodyJson = jsonAdapter.toJson(requestObj)
        val selectedModel = model.takeIf { it.isNotBlank() } ?: defaultModel
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$selectedModel:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            val body = response.body?.string() ?: ""
            throw RuntimeException("HTTP $code: $body")
        }

        val respBodyStr = response.body?.string()
        val respAdapter = moshi.adapter(GeminiResponse::class.java)
        val geminiResp = respAdapter.fromJson(respBodyStr ?: "{}")
        geminiResp?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: ""
    }
}
