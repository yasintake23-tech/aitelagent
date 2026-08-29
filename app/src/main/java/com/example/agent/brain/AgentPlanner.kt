package com.example.agent.brain

import android.util.Log
import com.example.agent.core.UserIntent
import com.example.service.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Groq API'sini kullanarak kullanıcı hedefinden ve TaskSpec'ten dinamik çok adımlı AgentPlan üreten planlama bileşeni.
 */
class AgentPlanner(
    private val aiProviderManager: com.example.ai.AIProviderManager? = null
) {

    companion object {
        private const val TAG = "AgentPlanner"
        private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        private const val DEFAULT_MODEL = "openai/gpt-oss-120b"
    }

    var lastUsedModel: String? = null
        private set

    /**
     * Gerçek LLM çağrısı ile dinamik bir AgentPlan oluşturur.
     */
    suspend fun createPlan(
        taskSpec: TaskSpec,
        workingMemory: AgentWorkingMemory,
        snapshot: ScreenSnapshot?,
        apiKey: String,
        providerId: String = "groq",
        model: String = DEFAULT_MODEL
    ): AgentPlan = withContext(Dispatchers.IO) {
        lastUsedModel = model

        if (apiKey.isBlank()) {
            Log.w(TAG, "API key bulunamadı. Güvenli varsayılan plan türetiliyor.")
            return@withContext createFallbackPlan(taskSpec)
        }

        try {
            val systemPrompt = """
                Sen Android Otonom Agent için Dinamik Görev Planlayıcısısın.
                Kullanıcının amacını (Goal) ve TaskSpec bilgilerini analiz ederek adımsal bir alt hedefler (sub-goals) planı oluştur.
                
                ÇIKTI FORMATI (YALNIZCA GEÇERLİ JSON DÖNDÜR, EK METİN YAZMA):
                {
                  "subGoals": [
                    {
                      "id": 1,
                      "description": "Alt hedef açıklaması",
                      "expectedPackage": "com.example.app veya null",
                      "expectedOutcome": "Beklenen ekran/durum açıklaması"
                    }
                  ],
                  "completionCriteria": "Görevin tamamlandığını doğrulama kriteri"
                }
                
                KURALLAR:
                1. Plan en fazla 5-7 net ve mantıklı adımdan oluşmalıdır.
                2. Her adım tek bir amaca hizmet etmelidir (örn: Uygulamayı Aç -> Kişi Ara -> Sohbet Aç -> Mesaj Yaz).
                3. Eğer görev otonom bir keşif/gezme göreviyse (EXPLORATION_TASK), planı cihazın farklı menülerini, ayarlarını veya güvenli uygulamalarını keşfetmek üzere 3-5 adımdan oluşan mantıklı bir strateji olarak hazırla (örneğin: Ana ekranı gözlemle -> Ayarlar menüsünü aç -> Ekran ayarlarını incele -> Ana ekrana dön -> Güvenli bir uygulamayı gez).
                4. Kesinlikle kural dışı metin veya markdown tırnakları ekleme, sadece saf JSON döndür.
                5. Previously blocked or failed routes must NOT be retried unless the environment has materially changed. Do not recommend the same strategy/plan if it failed before.
            """.trimIndent()

            val state = workingMemory.state
            val appInfo = snapshot?.packageName ?: state.currentPackageName ?: "Bilinmeyen"
            val blockedRoutesSummary = state.blockedRoutes.joinToString("\n") {
                "BLOCKED: Pkg:${it.packageName}, Fingerprint:${it.screenFingerprint}, Target:${it.target}"
            }
            val recentActionsSummary = state.actionHistory.takeLast(5).joinToString("\n") {
                "Step ${it.stepIndex}: ${it.actionType}(${it.target ?: ""}) -> Success:${it.isSuccess}"
            }
            val failuresSummary = state.failureHistory.takeLast(5).joinToString("\n") {
                "Failure ${it.stepIndex}: ${it.actionType} -> ${it.reason}"
            }
            val userPrompt = """
                HEDEF: ${taskSpec.originalGoal}
                TARGET APP: ${taskSpec.targetApp ?: "Bilinmiyor"}
                TARGET ENTITY: ${taskSpec.targetEntity ?: "Yok"}
                REQUESTED ACTION: ${taskSpec.requestedAction ?: "Yok"}
                CURRENT PACKAGE: $appInfo
                CURRENT SCREEN FINGERPRINT: ${state.currentScreenFingerprint ?: "Bilinmiyor"}
                VISITED PACKAGES: ${state.visitedPackages.joinToString(", ")}
                VISITED SCREENS: ${state.visitedScreenFingerprints.size}
                CONSECUTIVE FAILURES: ${state.consecutiveFailures}
                
                FAILURE HISTORY:
                ${failuresSummary.ifBlank { "Yok" }}
                
                RECENT ACTIONS & RESULTS:
                ${recentActionsSummary.ifBlank { "Yok" }}
                
                ENGELLENEN ROTALAR (BLOCKED ROUTES):
                ${blockedRoutesSummary.ifBlank { "Yok" }}
                
                SAFETY CONSTRAINTS: ${taskSpec.constraints.joinToString(", ")}
            """.trimIndent()

            val content = if (aiProviderManager != null) {
                try {
                    aiProviderManager.generateStructuralContent(
                        providerId = providerId,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        onError = { err -> Log.e(TAG, "Provider error: $err") }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Provider exception: ${e.message}")
                    ""
                }
            } else {
                // Fallback to old behavior if no provider manager
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
                    put("model", model.ifBlank { DEFAULT_MODEL })
                    put("messages", messages)
                    put("temperature", 0.1)
                    put("max_tokens", 500)
                }.toString()
                val request = Request.Builder()
                    .url(GROQ_ENDPOINT)
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val response = OkHttpClient().newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "API başarısız: HTTP ${response.code}")
                    return@withContext createFallbackPlan(taskSpec)
                }
                val responseBody = response.body?.string() ?: ""
                JSONObject(responseBody).getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            }

            if (content.isBlank()) return@withContext createFallbackPlan(taskSpec)

            val parsedPlan = parsePlanJson(content, taskSpec)
            if (parsedPlan != null && parsedPlan.subGoals.isNotEmpty()) {
                Log.i(TAG, "Dinamik plan Groq üzerinden başarıyla oluşturuldu: ${parsedPlan.subGoals.size} adım")
                return@withContext parsedPlan
            } else {
                Log.w(TAG, "Groq'tan gelen plan JSON'ı ayrıştırılamadı. Fallback plana geçiliyor.")
                return@withContext createFallbackPlan(taskSpec)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Groq Planner hatası: ${e.localizedMessage}", e)
            return@withContext createFallbackPlan(taskSpec)
        }
    }

    private fun parsePlanJson(jsonText: String, taskSpec: TaskSpec): AgentPlan? {
        return try {
            val cleanJson = jsonText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
            val root = JSONObject(cleanJson)
            val subGoalsArray = root.optJSONArray("subGoals") ?: return null
            val completionCriteria = root.optString("completionCriteria", taskSpec.completionCriteria ?: "")

            val subGoals = mutableListOf<SubGoal>()
            for (i in 0 until subGoalsArray.length()) {
                val item = subGoalsArray.getJSONObject(i)
                val id = item.optInt("id", i + 1)
                val desc = item.optString("description", "")
                val pkg = item.optString("expectedPackage", null).takeIf { !it.isNull_or_blank_and_null_str() }
                val outcome = item.optString("expectedOutcome", "")

                if (desc.isNotBlank()) {
                    subGoals.add(
                        SubGoal(
                            id = id,
                            description = desc,
                            expectedPackage = pkg,
                            expectedOutcome = outcome,
                            status = SubGoalStatus.PENDING
                        )
                    )
                }
            }

            AgentPlan(
                originalGoal = taskSpec.originalGoal,
                targetApp = taskSpec.targetApp,
                targetEntity = taskSpec.targetEntity,
                requestedAction = taskSpec.requestedAction,
                subGoals = subGoals,
                currentSubGoalIndex = 0,
                completionCriteria = completionCriteria,
                safetyConstraints = taskSpec.constraints,
                planStatus = if (subGoals.isNotEmpty()) PlanStatus.IN_PROGRESS else PlanStatus.FAILED
            )
        } catch (e: Exception) {
            Log.e(TAG, "parsePlanJson Exception: ${e.localizedMessage}")
            null
        }
    }

    fun createFallbackPlan(taskSpec: TaskSpec): AgentPlan {
        val subGoals = mutableListOf<SubGoal>()
        var id = 1

        if (taskSpec.intentType == UserIntent.EXPLORATION_TASK) {
            subGoals.add(SubGoal(id = id++, description = "Cihazın ana ekranını gözlemle ve durumunu analiz et", expectedOutcome = "Ana ekran inceleniyor"))
            subGoals.add(SubGoal(id = id++, description = "Ayarlar veya diğer yüklü sistem uygulamalarını aç", expectedOutcome = "Güvenli uygulama ekranı açılır"))
            subGoals.add(SubGoal(id = id++, description = "Uygulama içi güvenli menüleri ve alt ekranları adım adım keşfet", expectedOutcome = "Yeni ekranlar ziyaret edilir"))
            subGoals.add(SubGoal(id = id++, description = "Ana ekrana güvenli şekilde geri dönerek farklı bir rotayı gez", expectedOutcome = "Farklı bir rota keşfedilir"))
            return AgentPlan(
                originalGoal = taskSpec.originalGoal,
                targetApp = null,
                targetEntity = null,
                requestedAction = "EXPLORE",
                subGoals = subGoals,
                currentSubGoalIndex = 0,
                completionCriteria = "Cihaz belirtilen süre boyunca otonom ve güvenli şekilde keşfedildi",
                safetyConstraints = taskSpec.constraints,
                planStatus = PlanStatus.IN_PROGRESS
            )
        }

        taskSpec.targetApp?.let { app ->
            subGoals.add(
                SubGoal(
                    id = id++,
                    description = "$app uygulamasını aç",
                    expectedPackage = null,
                    expectedOutcome = "$app ana ekranı açılır"
                )
            )
        }

        taskSpec.targetEntity?.let { entity ->
            subGoals.add(
                SubGoal(
                    id = id++,
                    description = "$entity kişisini/öğesini ara ve seç",
                    expectedOutcome = "$entity sohbeti veya detay ekranı açılır"
                )
            )
        }

        taskSpec.payloadText?.let { payload ->
            subGoals.add(
                SubGoal(
                    id = id++,
                    description = "'$payload' metnini yaz ve gönder",
                    expectedOutcome = "Metin girilir ve gönderilir"
                )
            )
        }

        if (subGoals.isEmpty()) {
            subGoals.add(
                SubGoal(
                    id = 1,
                    description = taskSpec.originalGoal,
                    expectedOutcome = "Hedef gerçekleştirilir"
                )
            )
        }

        return AgentPlan(
            originalGoal = taskSpec.originalGoal,
            targetApp = taskSpec.targetApp,
            targetEntity = taskSpec.targetEntity,
            requestedAction = taskSpec.requestedAction,
            subGoals = subGoals,
            currentSubGoalIndex = 0,
            completionCriteria = taskSpec.completionCriteria ?: "Hedef tamamlanır",
            safetyConstraints = taskSpec.constraints,
            planStatus = PlanStatus.IN_PROGRESS
        )
    }

    private fun String?.isNull_or_blank_and_null_str(): Boolean {
        if (this == null) return true
        val trimmed = this.trim()
        return trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)
    }
}
