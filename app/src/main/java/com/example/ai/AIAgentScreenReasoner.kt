package com.example.ai

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.UserProfileEntity
import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class AgentActionType {
    CLICK_NODE,
    CLICK_COORD,
    SWIPE_DOWN,
    SWIPE_UP,
    SWIPE_LEFT,
    SWIPE_RIGHT,
    OPEN_APP,
    TYPE_TEXT,
    PRESS_BACK,
    PRESS_HOME,
    OPEN_QUICK_SETTINGS,
    OPEN_NOTIFICATIONS,
    VOLUME_UP,
    VOLUME_DOWN,
    TASK_COMPLETE,
    LEARN_AND_OBSERVE,
    IDLE
}

data class AgentStepDecision(
    val actionType: AgentActionType,
    val thought: String,
    val speechStatus: String,
    val targetIndex: Int = -1,
    val targetText: String = "",
    val coordinates: PointF? = null,
    val appName: String = "",
    val textToType: String = "",
    val completionSummary: String = "",
    val memoryKey: String? = null,
    val memoryValue: String? = null
)

class AIAgentScreenReasoner(
    private val aiProviderManager: AIProviderManager
) {
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Sends screen snapshot + context to Groq API / Gemini API (or selected provider) to get an intelligent decision.
     */
    suspend fun decideNextScreenAction(
        snapshot: ScreenSnapshot,
        taskPrompt: String,
        stepNumber: Int,
        visitedElements: Set<String>,
        memories: List<MemoryEntryEntity>,
        profile: UserProfileEntity?,
        liveScreenshot: Bitmap? = null
    ): AgentStepDecision = withContext(Dispatchers.IO) {
        val preferredProvider = profile?.preferredAiProvider?.lowercase(Locale.ROOT) ?: "gemini"
        val groqKey = aiProviderManager.getApiKey("groq").ifBlank {
            if (preferredProvider == "groq") profile?.customApiKey ?: "" else ""
        }
        val geminiKey = aiProviderManager.getApiKey("gemini").ifBlank {
            val keyFromStore = if (preferredProvider == "gemini") profile?.customApiKey?.ifBlank { null } else null
            keyFromStore ?: BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() && it != "MY_GEMINI_API_KEY" } ?: ""
        }

        // 1. If Groq is preferred and key is available, execute via Groq's high-speed LPU
        if (preferredProvider == "groq" && groqKey.isNotBlank()) {
            try {
                val selectedModel = aiProviderManager.getSelectedModel("groq")
                val groqDecision = callGroqScreenReasoning(
                    apiKey = groqKey,
                    snapshot = snapshot,
                    taskPrompt = taskPrompt,
                    stepNumber = stepNumber,
                    visitedElements = visitedElements,
                    memories = memories,
                    profile = profile,
                    model = selectedModel
                )
                if (groqDecision != null) {
                    return@withContext groqDecision
                }
            } catch (e: Exception) {
                Log.w("AIAgentScreenReasoner", "Groq screen reasoning failed, checking Gemini fallback", e)
            }
        }

        // 2. If Gemini API Key is available, try Multi-modal / Tree Reasoning
        if (geminiKey.isNotBlank() && geminiKey != "MY_GEMINI_API_KEY") {
            try {
                // If the accessibility tree is empty or very sparse (e.g. WebView / Canvas app), use Multimodal Vision Fallback
                val isSparseUi = snapshot.clickableNodes.isEmpty() && snapshot.texts.size <= 2
                if (isSparseUi && liveScreenshot != null) {
                    val visionDecision = callGeminiVisionPixelReasoning(
                        apiKey = geminiKey,
                        bitmap = liveScreenshot,
                        taskPrompt = taskPrompt,
                        stepNumber = stepNumber
                    )
                    if (visionDecision != null) {
                        return@withContext visionDecision
                    }
                }

                // Standard Tree + Context Reasoning
                val decision = callGeminiScreenReasoning(
                    apiKey = geminiKey,
                    snapshot = snapshot,
                    taskPrompt = taskPrompt,
                    stepNumber = stepNumber,
                    visitedElements = visitedElements,
                    memories = memories,
                    profile = profile,
                    liveScreenshot = liveScreenshot
                )
                if (decision != null) {
                    return@withContext decision
                }
            } catch (e: Exception) {
                Log.w("AIAgentScreenReasoner", "Gemini API call failed", e)
            }
        }

        // 3. If Groq key exists (even if preferred was gemini), try Groq fallback
        if (groqKey.isNotBlank()) {
            try {
                val groqDecision = callGroqScreenReasoning(
                    apiKey = groqKey,
                    snapshot = snapshot,
                    taskPrompt = taskPrompt,
                    stepNumber = stepNumber,
                    visitedElements = visitedElements,
                    memories = memories,
                    profile = profile
                )
                if (groqDecision != null) {
                    return@withContext groqDecision
                }
            } catch (e: Exception) {
                Log.w("AIAgentScreenReasoner", "Groq fallback call failed", e)
            }
        }

        // Fallback: Advanced Curiosity & Heuristics Reasoner
        return@withContext computeLocalCuriousDecision(
            snapshot = snapshot,
            taskPrompt = taskPrompt,
            stepNumber = stepNumber,
            visitedElements = visitedElements
        )
    }

    /**
     * Groq Cloud OpenAI-compatible Reasoning Engine
     */
    private fun callGroqScreenReasoning(
        apiKey: String,
        snapshot: ScreenSnapshot,
        taskPrompt: String,
        stepNumber: Int,
        visitedElements: Set<String>,
        memories: List<MemoryEntryEntity>,
        profile: UserProfileEntity?,
        model: String = "openai/gpt-oss-120b"
    ): AgentStepDecision? {
        try {
            val appName = snapshot.packageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: "Bilinmeyen"
            val visibleTexts = snapshot.texts.take(8).joinToString(" | ")
            val clickableList = snapshot.clickableNodes.take(35).mapIndexed { idx, node ->
                val cx = node.bounds.centerX()
                val cy = node.bounds.centerY()
                val textPart = node.text.takeIf { it.isNotBlank() }?.let { "txt:\"${it.replace("\"", "'").take(25)}\"" }
                val descPart = node.contentDescription.takeIf { it.isNotBlank() && it != node.text }?.let { "desc:\"${it.replace("\"", "'").take(25)}\"" }
                val idPart = node.viewId.takeIf { it.isNotBlank() }?.let { "viewId:\"${it.replace("\"", "'").take(20)}\"" }
                val details = listOfNotNull(textPart, descPart, idPart).joinToString(", ")
                if (details.isNotBlank()) {
                    "[id:$idx, $details, x:$cx, y:$cy]"
                } else {
                    "[id:$idx, x:$cx, y:$cy]"
                }
            }.joinToString("\n")

            val visitedSummary = visitedElements.toList().takeLast(6).joinToString(", ")

            val systemPrompt = """
                Sen Android ReAct (Reason + Act) Otonom Ekran Ajanısın.
                Kullanıcının isteğini yerine getirmek için doğrudan kör eyleme geçme. Önce <thought>...</thought> etiketleri içinde ekrandaki ögeleri, metinleri, ID'leri ve klasörleri detaylı inceleyerek ne yapacağını adım adım planla, ardından <action>...</action> etiketiyle tek bir eylem üret.
                
                FORMAT:
                <thought>Kullanıcı WhatsApp'ı açmamı istedi. Ekranda 'Sosyal' adlı bir klasör var, WhatsApp muhtemelen içinde. Önce klasöre tıklayacağım.</thought>
                <action>click(id:3)</action>
                
                EYLEM TÜRLERİ:
                - click(id:X) : Listedeki id:X düğmesine / klasöre tıkla.
                - click(x, y) : Koordinata tıkla.
                - swipe(down|up|left|right) : Ekranı kaydır.
                - open_app(appName) : Uygulamayı doğrudan aç.
                - type_text(text) : Metin kutusuna yaz.
                - open_quick_settings() : Hızlı ayarlar / bildirim kontrol panelini aç.
                - open_notifications() : Bildirimler panelini aç.
                - volume_up() : Medya / sistem sesini 1 kademe artır.
                - volume_down() : Medya / sistem sesini 1 kademe azalt.
                - go_back() / press_back() : Geri git.
                - go_home() / press_home() : Ana ekrana dön.
                - complete(mesaj) / task_complete(mesaj) : Görev başarıyla tamamlandığında veya hedeflenen ekrana ulaşıldığında bitir.
                
                KLASÖR VE GİZLİ UYGULAMA ARAMA ZEKASI (KRİTİK):
                1. Eğer aranan uygulama (örn: WhatsApp, Galeri, Ayarlar vb.) ana ekranda doğrudan görünmüyorsa, içinde uygulama olabilecek klasörleri (örn: 'Sosyal', 'İletişim', 'Google', 'Araçlar', 'Sistem', 'Medya' vb. veya 'Klasör' etiketli düğmeleri) analiz et ve önce o klasöre tıkla (`click(id:X)`). Klasör açıldıktan sonra gelen yeni ekranda aranan uygulamayı bulup tıkla.
                2. Asla ilk ekranda görünmüyor diye pes etme. Gerekirse sayfayı kaydır (`swipe(left)` / `swipe(right)` / `swipe(down)` / `swipe(up)`), klasörleri kontrol et veya uygulama çekmecesini aç.
                3. Aradığın uygulama veya hedefe ulaştığında mutlaka <action>complete(Uygulama açıldı ve görev tamamlandı)</action> çağırarak döngüyü bitir.
                
                SİSTEM VE DONANIM KOMUTLARI KURALI:
                Kullanıcı "sesi aç/kıs", "hızlı paneli aç", "bildirimleri göster", "ana ekrana dön", "geri git" gibi sistem düzeyinde bir işlem istediğinde ekranda arama yapma, doğrudan sistem aksiyonunu tetikle!
            """.trimIndent()

            val userPrompt = """
                Görev: $taskPrompt (Adım: $stepNumber, App: $appName)
                Metinler: $visibleTexts
                Ziyaret Edilenler: $visitedSummary
                Aday Ekran Düğmeleri:
                $clickableList
            """.trimIndent()

            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            }

            val requestBodyJson = JSONObject().apply {
                put("model", model.ifBlank { "openai/gpt-oss-120b" })
                put("messages", messages)
                put("temperature", 0.2)
                put("max_tokens", 300)
            }.toString()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("AIAgentScreenReasoner", "Groq screen reasoning error code: ${response.code}")
                return null
            }

            val bodyString = response.body?.string() ?: return null
            val jsonRoot = JSONObject(bodyString)
            val choices = jsonRoot.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val messageObj = choices.getJSONObject(0).optJSONObject("message") ?: return null
            val content = messageObj.optString("content", "")
            return parseDecisionJson(content, snapshot)
        } catch (e: Exception) {
            Log.e("AIAgentScreenReasoner", "Groq reasoning call failed", e)
            return null
        }
    }

    /**
     * Gemini Vision Pixel-Based Fallback for Canvas/WebView/Game screens where accessibility tree has no nodes.
     */
    private fun callGeminiVisionPixelReasoning(
        apiKey: String,
        bitmap: Bitmap,
        taskPrompt: String,
        stepNumber: Int
    ): AgentStepDecision? {
        try {
            val scaledBitmap = scaleBitmapDown(bitmap, 960)
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 75, outputStream)
            val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

            val width = bitmap.width
            val height = bitmap.height

            val systemInstruction = """
                Sen Android ekranının görsel analizini yapan AI agent'sın.
                Sana telefon ekranının canlı görüntüsü verildi.
                Kural: Asla uydurma koordinat seçme. speechStatus her zaman çok kısa ('Açılıyor', 'Tıklanıyor', 'İnceleniyor') olmalı.
                
                JSON ŞEMASI:
                {
                  "thought": "Kısa gerekçe",
                  "speechStatus": "Açılıyor",
                  "action": "CLICK_COORD" | "SWIPE_DOWN" | "SWIPE_UP" | "PRESS_BACK",
                  "x": 540,
                  "y": 960,
                  "memoryKey": "Bilgi Başlığı",
                  "memoryValue": "Detay"
                }
            """.trimIndent()

            val userText = "GÖREV: $taskPrompt (Adım $stepNumber). Ekran görüntüsündeki en uygun ögeyi tespit et ve JSON döndür."

            val partsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64Image)
                    })
                })
                put(JSONObject().apply {
                    put("text", userText)
                })
            }

            val requestBodyJson = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("parts", partsArray)
                }))
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.2)
                })
            }.toString()

            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null

            val jsonRoot = JSONObject(body)
            val candidates = jsonRoot.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            if (parts.length() == 0) return null

            val responseText = parts.getJSONObject(0).optString("text", "")
            return parseDecisionJson(responseText, ScreenSnapshot("", "", 0, emptyList(), emptyList()))
        } catch (e: Exception) {
            Log.e("AIAgentScreenReasoner", "Error in Gemini Vision pixel reasoning", e)
            return null
        }
    }

    private fun callGeminiScreenReasoning(
        apiKey: String,
        snapshot: ScreenSnapshot,
        taskPrompt: String,
        stepNumber: Int,
        visitedElements: Set<String>,
        memories: List<MemoryEntryEntity>,
        profile: UserProfileEntity?,
        liveScreenshot: Bitmap?
    ): AgentStepDecision? {
        val appName = snapshot.packageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: "Bilinmeyen"
        val visibleTexts = snapshot.texts.take(5).joinToString(" | ")
        val clickableList = snapshot.clickableNodes.take(30).mapIndexed { idx, node ->
            val cx = node.bounds.centerX()
            val cy = node.bounds.centerY()
            val textPart = node.text.takeIf { it.isNotBlank() }?.let { "txt:\"${it.replace("\"", "'").take(25)}\"" }
            val descPart = node.contentDescription.takeIf { it.isNotBlank() && it != node.text }?.let { "desc:\"${it.replace("\"", "'").take(25)}\"" }
            val idPart = node.viewId.takeIf { it.isNotBlank() }?.let { "viewId:\"${it.replace("\"", "'").take(20)}\"" }
            val details = listOfNotNull(textPart, descPart, idPart).joinToString(", ")
            if (details.isNotBlank()) {
                "[id:$idx, $details, x:$cx, y:$cy]"
            } else {
                "[id:$idx, x:$cx, y:$cy]"
            }
        }.joinToString("\n")

        val visitedSummary = visitedElements.toList().takeLast(6).joinToString(", ")

        val systemPrompt = """
            Sen Android ReAct (Reason + Act) Otonom Ekran Ajanısın.
            Kullanıcının isteğini yerine getirmek için doğrudan kör eyleme geçme. Önce <thought>...</thought> etiketleri içinde ekrandaki ögeleri, metinleri, ID'leri ve klasörleri detaylı inceleyerek ne yapacağını adım adım planla, ardından <action>...</action> etiketiyle tek bir eylem üret.
            
            FORMAT:
            <thought>Kullanıcı WhatsApp'ı açmamı istedi. Ekranda 'Sosyal' adlı bir klasör var, WhatsApp muhtemelen içinde. Önce klasöre tıklayacağım.</thought>
            <action>click(id:3)</action>
            
            EYLEM TÜRLERİ:
            - click(id:X) : Listedeki id:X düğmesine / klasöre tıkla.
            - click(x, y) : Koordinata tıkla.
            - swipe(down|up|left|right) : Ekranı kaydır.
            - open_app(appName) : Uygulamayı doğrudan aç.
            - type_text(text) : Metin kutusuna yaz.
            - open_quick_settings() : Hızlı ayarlar / bildirim kontrol panelini aç.
            - open_notifications() : Bildirimler panelini aç.
            - volume_up() : Medya / sistem sesini 1 kademe artır.
            - volume_down() : Medya / sistem sesini 1 kademe azalt.
            - go_back() / press_back() : Geri git.
            - go_home() / press_home() : Ana ekrana dön.
            - complete(mesaj) / task_complete(mesaj) : Görev başarıyla tamamlandığında veya hedeflenen ekrana ulaşıldığında bitir.
            
            KLASÖR VE GİZLİ UYGULAMA ARAMA ZEKASI (KRİTİK):
            1. Eğer aranan uygulama (örn: WhatsApp, Galeri, Ayarlar vb.) ana ekranda doğrudan görünmüyorsa, içinde uygulama olabilecek klasörleri (örn: 'Sosyal', 'İletişim', 'Google', 'Araçlar', 'Sistem', 'Medya' vb. veya 'Klasör' etiketli düğmeleri) analiz et ve önce o klasöre tıkla (`click(id:X)`). Klasör açıldıktan sonra gelen yeni ekranda aranan uygulamayı bulup tıkla.
            2. Asla ilk ekranda görünmüyor diye pes etme. Gerekirse sayfayı kaydır (`swipe(left)` / `swipe(right)` / `swipe(down)` / `swipe(up)`), klasörleri kontrol et veya uygulama çekmecesini aç.
            3. Aradığın uygulama veya hedefe ulaştığında mutlaka <action>complete(Uygulama açıldı ve görev tamamlandı)</action> çağırarak döngüyü bitir.
            
            SİSTEM VE DONANIM KOMUTLARI KURALI:
            Kullanıcı "sesi aç/kıs", "hızlı paneli aç", "bildirimleri göster", "ana ekrana dön", "geri git" gibi sistem düzeyinde bir işlem istediğinde ekranda arama yapma, doğrudan sistem aksiyonunu tetikle!
        """.trimIndent()

        val userPrompt = """
            Görev: $taskPrompt (Adım: $stepNumber, App: $appName)
            Metinler: $visibleTexts
            Ziyaret: $visitedSummary
            Aday Butonlar:
            $clickableList
        """.trimIndent()

        val partsArray = JSONArray()

        // If screenshot is available, attach visual context as well
        if (liveScreenshot != null) {
            try {
                val scaled = scaleBitmapDown(liveScreenshot, 640)
                val stream = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 65, stream)
                val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                partsArray.put(JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", base64)
                    })
                })
            } catch (e: Exception) {
                // Skip image if compression fails
            }
        }

        partsArray.put(JSONObject().apply {
            put("text", userPrompt)
        })

        val requestBodyJson = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", partsArray)
            }))
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.3)
            })
        }.toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w("AIAgentScreenReasoner", "Gemini API error code: ${response.code}")
            return null
        }

        val bodyString = response.body?.string() ?: return null
        val jsonRoot = JSONObject(bodyString)
        val candidates = jsonRoot.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null

        val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
        val parts = content.optJSONArray("parts") ?: return null
        if (parts.length() == 0) return null

        val responseText = parts.getJSONObject(0).optString("text", "")
        return parseDecisionJson(responseText, snapshot)
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        var resizedWidth = maxDimension
        var resizedHeight = maxDimension

        if (originalHeight > originalWidth) {
            resizedHeight = maxDimension
            resizedWidth = (resizedHeight * originalWidth.toFloat() / originalHeight.toFloat()).toInt()
        } else if (originalWidth > originalHeight) {
            resizedWidth = maxDimension
            resizedHeight = (resizedWidth * originalHeight.toFloat() / originalWidth.toFloat()).toInt()
        } else {
            resizedHeight = maxDimension
            resizedWidth = maxDimension
        }
        return Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, false)
    }

    private fun parseDecisionJson(rawJson: String, snapshot: ScreenSnapshot): AgentStepDecision? {
        try {
            val raw = rawJson.trim()
            var thought = "Ekran incelendi ve işlem planlandı."
            var actionStr = ""
            var targetIndex = -1
            var x = -1.0
            var y = -1.0
            var appName = ""
            var textToType = ""
            var completionSummary = ""
            var memoryKey: String? = null
            var memoryValue: String? = null

            // 1. ReAct XML tags: <thought>...</thought> and <action>...</action>
            val thoughtMatch = Regex("<thought>(.*?)</thought>", RegexOption.DOT_MATCHES_ALL).find(raw)
            val actionMatch = Regex("<action>(.*?)</action>", RegexOption.DOT_MATCHES_ALL).find(raw)

            if (actionMatch != null) {
                if (thoughtMatch != null) {
                    thought = thoughtMatch.groupValues[1].trim()
                }
                val rawAction = actionMatch.groupValues[1].trim()

                val clickIdMatch = Regex("click\\s*\\(\\s*id\\s*:\\s*(\\d+)\\s*\\)", RegexOption.IGNORE_CASE).find(rawAction)
                val clickCoordMatch = Regex("click\\s*\\(\\s*(\\d+(?:\\.\\d+)?)\\s*,\\s*(\\d+(?:\\.\\d+)?)\\s*\\)", RegexOption.IGNORE_CASE).find(rawAction)
                val swipeMatch = Regex("swipe\\s*\\(\\s*(\\w+)\\s*\\)", RegexOption.IGNORE_CASE).find(rawAction)
                val openAppMatch = Regex("open_app\\s*\\(\\s*[\"']?([^\"')]+)[\"']?\\s*\\)", RegexOption.IGNORE_CASE).find(rawAction)
                val typeTextMatch = Regex("type_text\\s*\\(\\s*[\"']?([^\"')]+)[\"']?\\s*\\)", RegexOption.IGNORE_CASE).find(rawAction)
                val completeMatch = Regex("(?:complete|task_complete|finish|done)\\s*\\(\\s*[\"']?([^\"')]+)?[\"']?\\s*\\)", RegexOption.IGNORE_CASE).find(rawAction)

                when {
                    completeMatch != null -> {
                        completionSummary = completeMatch.groupValues[1].trim().ifBlank { "Görev tamamlandı." }
                        actionStr = "TASK_COMPLETE"
                    }
                    clickIdMatch != null -> {
                        targetIndex = clickIdMatch.groupValues[1].toIntOrNull() ?: -1
                        actionStr = "CLICK_NODE"
                    }
                    clickCoordMatch != null -> {
                        x = clickCoordMatch.groupValues[1].toDoubleOrNull() ?: -1.0
                        y = clickCoordMatch.groupValues[2].toDoubleOrNull() ?: -1.0
                        actionStr = "CLICK_COORD"
                    }
                    swipeMatch != null -> {
                        val dir = swipeMatch.groupValues[1].uppercase(Locale.ROOT)
                        actionStr = when (dir) {
                            "UP" -> "SWIPE_UP"
                            "LEFT" -> "SWIPE_LEFT"
                            "RIGHT" -> "SWIPE_RIGHT"
                            else -> "SWIPE_DOWN"
                        }
                    }
                    openAppMatch != null -> {
                        appName = openAppMatch.groupValues[1].trim()
                        actionStr = "OPEN_APP"
                    }
                    typeTextMatch != null -> {
                        textToType = typeTextMatch.groupValues[1].trim()
                        actionStr = "TYPE_TEXT"
                    }
                    rawAction.contains("open_quick_settings", ignoreCase = true) || rawAction.contains("quick_settings", ignoreCase = true) || rawAction.contains("quick_panel", ignoreCase = true) -> {
                        actionStr = "OPEN_QUICK_SETTINGS"
                    }
                    rawAction.contains("open_notifications", ignoreCase = true) || rawAction.contains("notifications", ignoreCase = true) -> {
                        actionStr = "OPEN_NOTIFICATIONS"
                    }
                    rawAction.contains("volume_up", ignoreCase = true) -> {
                        actionStr = "VOLUME_UP"
                    }
                    rawAction.contains("volume_down", ignoreCase = true) -> {
                        actionStr = "VOLUME_DOWN"
                    }
                    rawAction.contains("go_back", ignoreCase = true) || rawAction.contains("press_back", ignoreCase = true) -> {
                        actionStr = "PRESS_BACK"
                    }
                    rawAction.contains("go_home", ignoreCase = true) || rawAction.contains("press_home", ignoreCase = true) -> {
                        actionStr = "PRESS_HOME"
                    }
                    rawAction.contains("complete", ignoreCase = true) || rawAction.contains("tamamlandı", ignoreCase = true) || rawAction.contains("bitti", ignoreCase = true) -> {
                        completionSummary = "Görev tamamlandı."
                        actionStr = "TASK_COMPLETE"
                    }
                    rawAction.contains("not_found", ignoreCase = true) -> {
                        actionStr = "SWIPE_DOWN"
                    }
                }
            }

            // 2. Fallback: Parse JSON if ReAct tags were not present
            if (actionStr.isBlank()) {
                val cleanJson = raw
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()

                val jsonStart = cleanJson.indexOf("{")
                val jsonEnd = cleanJson.lastIndexOf("}")
                if (jsonStart != -1 && jsonEnd > jsonStart) {
                    val obj = JSONObject(cleanJson.substring(jsonStart, jsonEnd + 1))
                    thought = obj.optString("thought", thought)
                    actionStr = obj.optString("action", "LEARN_AND_OBSERVE").uppercase(Locale.ROOT)
                    targetIndex = obj.optInt("targetIndex", -1)
                    x = obj.optDouble("x", -1.0)
                    y = obj.optDouble("y", -1.0)
                    appName = obj.optString("appName", "")
                    textToType = obj.optString("textToType", "")
                    completionSummary = obj.optString("completionSummary", "")
                    memoryKey = obj.optString("memoryKey").takeIf { it.isNotBlank() }
                    memoryValue = obj.optString("memoryValue").takeIf { it.isNotBlank() }
                }
            }

            val speechStatus = when {
                actionStr == "TASK_COMPLETE" -> "Görev tamamlandı"
                actionStr.contains("CLICK") -> "Tıklanıyor"
                actionStr.contains("OPEN_QUICK_SETTINGS") -> "Hızlı panel açılıyor"
                actionStr.contains("OPEN_NOTIFICATIONS") -> "Bildirimler açılıyor"
                actionStr.contains("VOLUME_UP") -> "Ses artırılıyor"
                actionStr.contains("VOLUME_DOWN") -> "Ses kısılıyor"
                actionStr.contains("OPEN") -> "Açılıyor"
                actionStr.contains("SWIPE") -> "Kaydırılıyor"
                actionStr.contains("TYPE") -> "Yazılıyor"
                actionStr.contains("HOME") -> "Ana ekrana dönülüyor"
                actionStr.contains("BACK") -> "Geri dönülüyor"
                else -> "İnceleniyor"
            }

            val actionType = when (actionStr) {
                "TASK_COMPLETE" -> AgentActionType.TASK_COMPLETE
                "CLICK_NODE" -> AgentActionType.CLICK_NODE
                "CLICK_COORD" -> AgentActionType.CLICK_COORD
                "SWIPE_DOWN" -> AgentActionType.SWIPE_DOWN
                "SWIPE_UP" -> AgentActionType.SWIPE_UP
                "SWIPE_LEFT" -> AgentActionType.SWIPE_LEFT
                "SWIPE_RIGHT" -> AgentActionType.SWIPE_RIGHT
                "OPEN_APP" -> AgentActionType.OPEN_APP
                "TYPE_TEXT" -> AgentActionType.TYPE_TEXT
                "PRESS_BACK" -> AgentActionType.PRESS_BACK
                "PRESS_HOME" -> AgentActionType.PRESS_HOME
                "OPEN_QUICK_SETTINGS" -> AgentActionType.OPEN_QUICK_SETTINGS
                "OPEN_NOTIFICATIONS" -> AgentActionType.OPEN_NOTIFICATIONS
                "VOLUME_UP" -> AgentActionType.VOLUME_UP
                "VOLUME_DOWN" -> AgentActionType.VOLUME_DOWN
                else -> AgentActionType.LEARN_AND_OBSERVE
            }

            var coords: PointF? = null
            if (actionType == AgentActionType.CLICK_NODE && targetIndex in snapshot.clickableNodes.indices) {
                val node = snapshot.clickableNodes[targetIndex]
                coords = PointF(node.bounds.centerX().toFloat(), node.bounds.centerY().toFloat())
            } else if (x > 0 && y > 0) {
                coords = PointF(x.toFloat(), y.toFloat())
            }

            return AgentStepDecision(
                actionType = actionType,
                thought = thought,
                speechStatus = speechStatus,
                targetIndex = targetIndex,
                coordinates = coords,
                appName = appName,
                textToType = textToType,
                completionSummary = completionSummary,
                memoryKey = memoryKey,
                memoryValue = memoryValue
            )
        } catch (e: Exception) {
            Log.e("AIAgentScreenReasoner", "Error parsing LLM decision json", e)
            return null
        }
    }

    private fun computeLocalCuriousDecision(
        snapshot: ScreenSnapshot,
        taskPrompt: String,
        stepNumber: Int,
        visitedElements: Set<String>
    ): AgentStepDecision {
        val activePkg = snapshot.packageName.lowercase(Locale("tr", "TR"))
        val isLauncher = activePkg.contains("launcher") || activePkg.contains("home")
        val visibleTexts = snapshot.texts.filter { it.length in 2..60 }

        val curiosityKeywords = listOf(
            "pil", "ekran", "depolama", "ses", "bağlantı", "uygulamalar",
            "saat", "alarm", "dosyalar", "hesap makinesi", "galeri", "harita",
            "rehber", "ayarlar", "güncelleme", "hakkında", "güvenlik"
        )

        // 1. If on launcher, swipe up or click an unvisited curious app
        if (isLauncher) {
            val candidateNodes = snapshot.clickableNodes.filter { node ->
                val label = node.text.ifBlank { node.contentDescription }.trim()
                label.isNotBlank() && !visitedElements.contains(label.lowercase(Locale("tr", "TR")))
            }

            val chosen = candidateNodes.find { node ->
                val label = node.text.ifBlank { node.contentDescription }.lowercase(Locale("tr", "TR"))
                curiosityKeywords.any { label.contains(it) }
            } ?: candidateNodes.firstOrNull()

            if (chosen != null) {
                val label = chosen.text.ifBlank { chosen.contentDescription }
                return AgentStepDecision(
                    actionType = AgentActionType.CLICK_COORD,
                    thought = "Ana ekrandaki henüz keşfedilmemiş '$label' ögesine parmakla dokunarak içeriğini inceleyeceğim.",
                    speechStatus = "Merak edilen '$label' uygulamasına giriliyor...",
                    targetText = label,
                    coordinates = PointF(chosen.bounds.centerX().toFloat(), chosen.bounds.centerY().toFloat()),
                    memoryKey = "Keşfedilen Uygulama",
                    memoryValue = label
                )
            } else {
                return AgentStepDecision(
                    actionType = AgentActionType.SWIPE_UP,
                    thought = "Ana ekrandaki ögeler incelendi, uygulama çekmecesini yukarı kaydırarak diğer araçları arıyorum.",
                    speechStatus = "Uygulama çekmecesi yukarı kaydırılıyor..."
                )
            }
        }

        // 2. In-app: Alternate between scrolling down to inspect hidden options and clicking sub-menus
        if (stepNumber % 3 == 0) {
            return AgentStepDecision(
                actionType = AgentActionType.SWIPE_DOWN,
                thought = "Uygulama içi daha fazla seçenek ve alt ayarları görmek için ekranı aşağı kaydırıyorum.",
                speechStatus = "Seçenekler taranıyor ve sayfa kaydırılıyor..."
            )
        }

        // Look for unvisited menu items in the app
        val unvisitedNodes = snapshot.clickableNodes.filter { node ->
            val label = node.text.ifBlank { node.contentDescription }.trim()
            label.length in 3..40 && !visitedElements.contains(label.lowercase(Locale("tr", "TR")))
        }

        val curiousNode = unvisitedNodes.find { node ->
            val label = node.text.ifBlank { node.contentDescription }.lowercase(Locale("tr", "TR"))
            curiosityKeywords.any { label.contains(it) }
        } ?: unvisitedNodes.firstOrNull()

        if (curiousNode != null) {
            val label = curiousNode.text.ifBlank { curiousNode.contentDescription }
            val appTitle = snapshot.packageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: "Uygulama"
            return AgentStepDecision(
                actionType = AgentActionType.CLICK_COORD,
                thought = "$appTitle içinde '$label' seçeneğini keşfetmek için parmakla dokunuyorum.",
                speechStatus = "Alt bölüme giriliyor: $label",
                targetText = label,
                coordinates = PointF(curiousNode.bounds.centerX().toFloat(), curiousNode.bounds.centerY().toFloat()),
                memoryKey = "$appTitle: $label",
                memoryValue = visibleTexts.take(4).joinToString(" • ")
            )
        }

        // If no more unvisited items in current app section, press back or return home
        return if (stepNumber % 4 == 0) {
            AgentStepDecision(
                actionType = AgentActionType.PRESS_HOME,
                thought = "Bu bölümdeki tüm içerikleri öğrendim. Başka alanları keşfetmek için ana ekrana dönüyorum.",
                speechStatus = "Yeni bölümler için ana ekrana dönülüyor."
            )
        } else {
            AgentStepDecision(
                actionType = AgentActionType.PRESS_BACK,
                thought = "Mevcut menüyü tamamladım, önceki ekrana geri dönüyorum.",
                speechStatus = "Önceki sayfaya dönülüyor."
            )
        }
    }
}
