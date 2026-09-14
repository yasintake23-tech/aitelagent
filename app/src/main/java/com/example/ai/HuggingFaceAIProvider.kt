package com.example.ai

import android.util.Log
import com.example.data.model.ChatMessageEntity
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.MessageRole
import com.example.data.model.PersonalityTone
import com.example.data.model.UserProfileEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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

class HuggingFaceAIProvider(
    private val localFallback: SmartLocalAIProvider = SmartLocalAIProvider()
) : AIProvider {
    override val id: String = "huggingface"
    override val displayName: String = "Hugging Face"
    override val shortDescription: String = "Açık kaynaklı yapay zekâ modelleri ve topluluk ekosistemi."
    override val requiresApiKey: Boolean = true
    override val keyPlaceholder: String = "hf_..."
    override val keyHint: String = "Hugging Face Kullanıcı Erişim Token'ı (Access Token)"
    override val freeTierInfo: String = "Ücretsiz hesapla sunucusuz Inference API erişimi."
    override val isCloudBased: Boolean = true
    override val defaultModel: String = "Qwen/Qwen2.5-7B-Instruct"
    override val availableModels: List<String> = listOf(
        "mistralai/Mistral-7B-Instruct-v0.3",
        "Qwen/Qwen2.5-7B-Instruct",
        "microsoft/Phi-3-mini-4k-instruct"
    )

    val defaultVisionModel: String = "Qwen/Qwen2.5-VL-3B-Instruct"
    val availableVisionModels: List<String> = listOf(
        "Qwen/Qwen2.5-VL-3B-Instruct",
        "Qwen/Qwen2.5-VL-7B-Instruct"
    )

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun validateCredentials(apiKey: String): ProviderValidationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ProviderValidationResult(false, "Hugging Face token'ı boş olamaz.")
        }
        try {
            // HuggingFace whoami API check
            val request = Request.Builder()
                .url("https://huggingface.co/api/whoami-v2")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                ProviderValidationResult(true)
            } else {
                val code = response.code
                if (code == 401 || code == 403) {
                    ProviderValidationResult(false, "Geçersiz Hugging Face Token'ı.")
                } else {
                    ProviderValidationResult(false, "Hugging Face bağlantı hatası (HTTP $code).")
                }
            }
        } catch (e: Exception) {
            ProviderValidationResult(false, "Ağ bağlantı hatası: ${e.localizedMessage ?: "Sunucuya erişilemedi"}")
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
        val token = overrideApiKey?.takeIf { it.isNotBlank() } ?: ""
        if (token.isBlank()) {
            localFallback.generateResponse(prompt, conversationHistory, memories, profile).collect { emit(it) }
            return@flow
        }

        try {
            val userName = profile?.userName?.ifBlank { "Kullanıcı" } ?: "Kullanıcı"
            val aiName = profile?.aiName?.ifBlank { "Nova" } ?: "Nova"
            val tone = PersonalityTone.fromString(profile?.personalityTone)

            val modelId = overrideModel?.takeIf { it.isNotBlank() } ?: defaultModel
            val url = "https://api-inference.huggingface.co/models/$modelId"

            val brevity = "Kullanıcıya her zaman MÜMKÜN OLAN EN KISA cevabı ver. Gereksiz açıklama yapma. Eylemde sadece 'Açılıyor', 'Yapıldı' de."
            val jsonBody = JSONObject()
            jsonBody.put("inputs", "<|system|>\nSen $aiName adında bir asistansın. Kullanıcın $userName. İletişim tonun: ${tone.displayName}. $brevity Türkçe konuş.\n<|user|>\n$prompt\n<|assistant|>\n")
            
            val params = JSONObject()
            params.put("max_new_tokens", 512)
            params.put("parameters", params)

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer ${token.trim()}")
                .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                val errorMsg = when (code) {
                    401, 403 -> "API Hatanız: HuggingFace 401 (Invalid Token)"
                    else -> "API Hatanız: HuggingFace HTTP $code"
                }
                Log.e("HuggingFaceAIProvider", "HuggingFace API error: $code")
                onError?.invoke(errorMsg)
                localFallback.generateResponse(prompt, conversationHistory, memories, profile).collect { emit(it) }
                return@flow
            }

            Log.d("HuggingFaceAIProvider", "API Connection Success")
            val respStr = response.body?.string() ?: ""
            var generated = ""
            try {
                val jsonArr = JSONArray(respStr)
                if (jsonArr.length() > 0) {
                    val obj = jsonArr.getJSONObject(0)
                    generated = obj.optString("generated_text", "")
                }
            } catch (e: Exception) {
                val obj = JSONObject(respStr)
                generated = obj.optString("generated_text", "")
            }

            if (generated.isNotBlank()) {
                val cleanText = generated.substringAfter("<|assistant|>\n").trim().ifBlank { generated }
                val words = cleanText.split(" ")
                val buffer = StringBuilder()
                for (i in words.indices) {
                    if (i > 0) buffer.append(" ")
                    buffer.append(words[i])
                    emit(buffer.toString())
                    delay(15)
                }
            } else {
                localFallback.generateResponse(prompt, conversationHistory, memories, profile).collect { emit(it) }
            }
        } catch (e: Exception) {
            Log.e("HuggingFaceAIProvider", "Error in HuggingFace inference", e)
            localFallback.generateResponse(prompt, conversationHistory, memories, profile).collect { emit(it) }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun generateStructuralContent(
        systemPrompt: String,
        userPrompt: String,
        apiKey: String,
        model: String
    ): String = withContext(Dispatchers.IO) {
        // ... (existing implementation or similar to text)
        // For simplicity, reusing text generation logic if structural content is requested
        val modelId = if (model.isNotBlank()) model else defaultModel
        val url = "https://api-inference.huggingface.co/models/$modelId"
        
        val jsonBody = JSONObject()
        jsonBody.put("inputs", "$systemPrompt\n\n$userPrompt")
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) throw RuntimeException("HF Error: ${response.code}")
        
        val respStr = response.body?.string() ?: ""
        try {
            val arr = JSONArray(respStr)
            arr.getJSONObject(0).optString("generated_text", "")
        } catch (e: Exception) {
            JSONObject(respStr).optString("generated_text", "")
        }
    }

    override suspend fun generateVisionContent(
        prompt: String,
        bitmap: android.graphics.Bitmap,
        apiKey: String,
        model: String
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw IllegalArgumentException("HF_API_KEY_MISSING")
        val modelId = model.takeIf { availableVisionModels.contains(it) } ?: defaultVisionModel
        val dataUrl = "data:image/jpeg;base64,${encodeBitmapToBase64(bitmap)}"

        val content = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply { put("url", dataUrl) })
            })
        }
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            })
        }
        val jsonBody = JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            put("temperature", 0.1)
            put("max_tokens", 400)
        }

        val request = Request.Builder()
            .url("https://router.huggingface.co/v1/chat/completions")
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            Log.e("HuggingFaceAIProvider", "Vision error ${response.code}: $body")
            throw RuntimeException("HF Vision Error: HTTP ${response.code}")
        }
        val json = JSONObject(body)
        val choices = json.optJSONArray("choices") ?: throw RuntimeException("HF Vision returned no choices")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
        val contentValue = message?.opt("content")
        when (contentValue) {
            is String -> contentValue.trim()
            is JSONArray -> buildString {
                for (i in 0 until contentValue.length()) {
                    val part = contentValue.optJSONObject(i)
                    append(part?.optString("text").orEmpty())
                }
            }.trim()
            else -> ""
        }.ifBlank { throw RuntimeException("HF Vision returned empty content") }
    }

    private fun encodeBitmapToBase64(bitmap: android.graphics.Bitmap): String {
        val outputStream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)
    }
}
