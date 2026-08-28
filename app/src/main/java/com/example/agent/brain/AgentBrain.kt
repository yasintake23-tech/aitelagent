package com.example.agent.brain

import android.util.Log
import com.example.agent.core.AgentLifecycleManager
import com.example.agent.core.UserIntent
import com.example.service.AiDeviceAccessibilityService
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
import java.util.concurrent.TimeUnit

/**
 * Merkezi Otonom Agent Orchestrator.
 * UNDERSTAND -> PLAN -> OBSERVE -> REASON -> PROPOSE -> SAFETY CHECK -> ACT -> VERIFY -> REMEMBER -> REPLAN
 */
class AgentBrain(
    val workingMemory: AgentWorkingMemory = AgentWorkingMemory(),
    private val planner: AgentPlanner = AgentPlanner(),
    val stateGraph: ScreenStateGraph = ScreenStateGraph(),
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "AgentBrain"
        private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        private const val DEFAULT_MODEL = "openai/gpt-oss-120b"
    }

    var currentPlan: AgentPlan? = null
        private set

    var currentTaskSpec: TaskSpec? = null
        private set

    fun setTaskSpecForTesting(taskSpec: TaskSpec) {
        currentTaskSpec = taskSpec
    }

    /**
     * Yeni bir görevi başlatır ve dinamik plan türetir.
     */
    suspend fun initializeTask(
        userPrompt: String,
        snapshot: ScreenSnapshot?,
        apiKey: String,
        intentType: UserIntent = UserIntent.DEVICE_TASK,
        model: String? = null
    ): AgentPlan {
        Log.i(TAG, "Yeni görev başlatılıyor: '$userPrompt'")
        workingMemory.reset(userPrompt)
        stateGraph.reset()

        // 1. TaskSpec oluştur
        val taskSpec = parseTaskSpecFromGoal(userPrompt, intentType, apiKey, model)
        currentTaskSpec = taskSpec

        // 2. Dinamik Plan Oluştur
        val plan = planner.createPlan(
            taskSpec = taskSpec,
            workingMemory = workingMemory,
            snapshot = snapshot,
            apiKey = apiKey,
            model = model ?: DEFAULT_MODEL
        )

        currentPlan = plan
        workingMemory.setPlan(
            plan = plan.subGoals.map { it.description },
            subGoal = plan.currentSubGoal?.description
        )

        return plan
    }

    /**
     * Re-plan çağrısı ile mevcut planı günceller.
     */
    suspend fun replan(
        snapshot: ScreenSnapshot?,
        apiKey: String,
        model: String? = null
    ): AgentPlan {
        val taskSpec = currentTaskSpec ?: TaskSpec(workingMemory.state.originalGoal)
        Log.w(TAG, "Yeniden planlama (REPLAN) tetiklendi. Goal: '${taskSpec.originalGoal}'")

        val newPlan = planner.createPlan(
            taskSpec = taskSpec,
            workingMemory = workingMemory,
            snapshot = snapshot,
            apiKey = apiKey,
            model = model ?: DEFAULT_MODEL
        )

        currentPlan = newPlan
        workingMemory.setPlan(
            plan = newPlan.subGoals.map { it.description },
            subGoal = newPlan.currentSubGoal?.description
        )

        return newPlan
    }

    /**
     * Ekran durumunu gözlemler, çalışma belleğini günceller ve LLM üzerinden yapılandırılmış eylem önerisi (ActionProposal) ister.
     */
    suspend fun proposeNextAction(
        snapshot: ScreenSnapshot,
        screenFingerprint: String,
        apiKey: String,
        model: String = DEFAULT_MODEL
    ): ActionProposal = withContext(Dispatchers.IO) {

        // Working memory güncelle
        workingMemory.updateScreenState(
            fingerprint = screenFingerprint,
            packageName = snapshot.packageName,
            appTitle = snapshot.activityName
        )

        val memoryState = workingMemory.state
        val currentSubGoal = currentPlan?.currentSubGoal?.description ?: "Görev adımını ilerlet"

        // Groq API Key kontrolü
        if (apiKey.isBlank()) {
            Log.e(TAG, "Groq API Key eksik. Rastgele tıklama YAPILMAYACAK. NO_ACTION dönülüyor.")
            return@withContext ActionProposal(
                actionType = AgentActionType.NO_ACTION,
                reason = "API Key tanımlı değil. Güvenlik nedeniyle işlem durduruldu."
            )
        }

        // Anti-Loop Kontrolü 1: Aynı ekran üst üste 3 kez tekrarlandı mı?
        val recentFingerprints = memoryState.screenVisitHistory.takeLast(3)
        if (recentFingerprints.size >= 3 && recentFingerprints.all { it == screenFingerprint }) {
            Log.w(TAG, "Aynı ekran ($screenFingerprint) 3 kez tekrarlandı. Tıkanıklık tespit edildi, REPLAN tetikleniyor.")
            return@withContext ActionProposal(
                actionType = AgentActionType.REPLAN,
                reason = "Aynı ekranda 3 adımdır tıkandık. Alternatif rota yeniden planlanıyor."
            )
        }

        // Anti-Loop Kontrolü 2: 3 üst üste başarısız eylem varsa REPLAN
        if (memoryState.consecutiveFailures >= 3) {
            Log.w(TAG, "3 kez üst üste başarısız eylem kaydı var. REPLAN tetikleniyor.")
            return@withContext ActionProposal(
                actionType = AgentActionType.REPLAN,
                reason = "3 kez üst üste eylem başarısız oldu. Yeniden planlama başlatılıyor."
            )
        }

        // Anti-Loop Kontrolü 3: Application veya Screen loop tespiti
        if (workingMemory.detectApplicationLoop() || workingMemory.detectScreenLoop()) {
            Log.w(TAG, "Döngü tespit edildi (Application veya Screen loop). REPLAN tetikleniyor.")
            return@withContext ActionProposal(
                actionType = AgentActionType.REPLAN,
                reason = "Sonsuz döngü tespit edildi. Başka bir uygulama veya menü için alternatif plan yapılıyor."
            )
        }

        try {
            val isExploration = currentTaskSpec?.intentType == UserIntent.EXPLORATION_TASK
            val systemPrompt = """
                Sen Android Otonom Agent Brain'isin.
                Hedef odaklı ReAct döngüsüyle çalışıyorsun. Görevin, verilen ekran düğümlerini ve hafıza durumunu inceleyip ALT HEDEFE (Current Sub-Goal) ulaşacak TEK bir eylem teklifi (ActionProposal) üretmektir.
                
                ÇIKTI FORMATI (YALNIZCA SAF JSON DÖNDÜR):
                {
                  "actionType": "CLICK_NODE | CLICK_COORD | SWIPE_DOWN | SWIPE_UP | SWIPE_LEFT | SWIPE_RIGHT | TYPE_TEXT | PRESS_BACK | PRESS_HOME | OPEN_APP | COMPLETE | REPLAN | NO_ACTION",
                  "target": "Hedef düğüm metni veya açıklaması",
                  "targetIndex": 0,
                  "textPayload": "Yazılacak metin",
                  "reason": "Bu eylemin alt hedefe nasıl hizmet ettiğinin mantıklı gerekçesi",
                  "expectedOutcome": {
                    "screenChangeExpected": true,
                    "expectedPackage": "com.example.app veya null",
                    "expectedText": ["Beklenen metin 1"]
                  },
                  "memoryKey": "Önemli bir şey keşfedilirse kısa anahtar (opsiyonel)",
                  "memoryValue": "Keşfedilen önemli bilgi/detay (opsiyonel)"
                }
                
                EYLEM KURALLARI:
                1. CLICK_NODE: Listedeki `targetIndex` düğmesine bas.
                2. TYPE_TEXT: Arama/mesaj alanına metin yaz.
                3. PRESS_BACK / PRESS_HOME: Geri git veya ana ekrana dön.
                4. COMPLETE: Alt hedefler ve ana görev TAMAMEN bittiğinde çağır.
                5. REPLAN: Yanlış bir ekrandaysan veya mevcut plan tıkandıysa çağır.
                6. SWIPE_DOWN / SWIPE_UP / SWIPE_LEFT / SWIPE_RIGHT: Sayfayı kaydır.
                7. Asla rastgele işlem önerme. Eğer emin değilsen veya ekran tıkandıysa REPLAN üret.
                
                ${if (isExploration) """
                KEŞİF (EXPLORATION) KURALLARI:
                - Bu bir otonom keşif/gezme görevidir. Cihazın ve yüklü güvenli uygulamaların (Ayarlar, Galeri, Saat, vb.) menülerini, alt ekranlarını gez.
                - Ziyaret edilmemiş paketleri ("VISITED PACKAGES" içinde olmayanları) ve ziyaret edilmemiş ekran parmak izlerini tercih et.
                - Eğer bir ekranda daha önce tıklanmamış güvenli bir buton/öge varsa kesinlikle onu seçerek derinlemesine ilerle.
                - Aynı ekranda takılma durumlarında ("STATE GRAPH" ve "RECENT ACTIONS" kontrol et) hemen SWIPE_DOWN veya PRESS_BACK veya farklı bir uygulamayı açmayı (OPEN_APP) öner.
                - Kesinlikle "silme", "kaldırma", "para transferi", "satın alma", "ödeme", "abonelik", "hesap silme" veya riskli ayarlara (şifre değiştirme, sıfırlama) dokunma!
                """ else ""}
            """.trimIndent()

            val clickableList = snapshot.clickableNodes.take(30).mapIndexed { idx, node ->
                val txt = node.text.takeIf { it.isNotBlank() }?.let { "txt:\"${it.replace("\"", "'").take(20)}\"" }
                val desc = node.contentDescription.takeIf { it.isNotBlank() && it != node.text }?.let { "desc:\"${it.replace("\"", "'").take(20)}\"" }
                val details = listOfNotNull(txt, desc).joinToString(", ")
                if (details.isNotBlank()) "[id:$idx, $details]" else "[id:$idx, class:${node.className.split(".").last()}]"
            }.joinToString("\n")

            val recentHistorySummary = memoryState.actionHistory.takeLast(5).joinToString("\n") {
                "Step ${it.stepIndex}: ${it.actionType}(${it.target ?: ""}) -> Success:${it.isSuccess} (${it.resultSummary})"
            }

            val failureHistorySummary = memoryState.failureHistory.takeLast(5).joinToString("\n") {
                "Failure Step ${it.stepIndex}: ${it.actionType} -> ${it.reason}"
            }

            val blockedRoutesSummary = memoryState.blockedRoutes.joinToString("\n") {
                "BLOCKED ROUTE: Pkg:${it.packageName}, Screen:${it.screenFingerprint}, Target:${it.target} Reason:${it.reason}"
            }

            val orderedScreenHistorySummary = memoryState.screenVisitHistory.joinToString(" -> ")
            val orderedPackageHistorySummary = memoryState.packageVisitHistory.joinToString(" -> ")
            val isAppLoopDetected = workingMemory.detectApplicationLoop()
            val isScreenLoopDetected = workingMemory.detectScreenLoop()

            val userPrompt = """
                ORIGINAL GOAL: ${memoryState.originalGoal}
                CURRENT SUB-GOAL: $currentSubGoal
                CURRENT PLAN: ${memoryState.currentPlan.joinToString(" -> ")}
                STEP INDEX: ${memoryState.currentStepIndex + 1}
                CURRENT SCREEN FINGERPRINT: $screenFingerprint
                CURRENT PACKAGE NAME: ${snapshot.packageName}
                ACTIVE APP TITLE: ${snapshot.activityName ?: "Bilinmiyor"}
                VISITED PACKAGES: ${memoryState.visitedPackages.joinToString(", ")}
                VISITED SCREENS COUNT: ${memoryState.visitedScreenFingerprints.size}
                CONSECUTIVE FAILURES: ${memoryState.consecutiveFailures}
                
                ORDERED SCREEN HISTORY:
                $orderedScreenHistorySummary
                
                ORDERED PACKAGE HISTORY:
                $orderedPackageHistorySummary
                
                LOOP DETECTION:
                Application Loop Detected: $isAppLoopDetected
                Screen Loop Detected: $isScreenLoopDetected
                
                FAILURE HISTORY:
                ${failureHistorySummary.ifBlank { "Yok" }}
                
                RECENT ACTIONS & RESULTS:
                ${recentHistorySummary.ifBlank { "Henüz eylem yapılmadı" }}
                
                ENGELLENEN ROTALAR (BLOCKED ROUTES):
                ${blockedRoutesSummary.ifBlank { "Yok" }}
                
                STATE GRAPH:
                ${stateGraph.getSummaryString(screenFingerprint)}
                
                EKRAN DÜĞMELERİ:
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
                put("model", model.ifBlank { DEFAULT_MODEL })
                put("messages", messages)
                put("temperature", 0.1)
                put("max_tokens", 350)
            }.toString()

            val request = Request.Builder()
                .url(GROQ_ENDPOINT)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Groq Reasoner API hatası: HTTP ${response.code}")
                return@withContext ActionProposal(
                    actionType = AgentActionType.REPLAN,
                    reason = "API bağlantı hatası (HTTP ${response.code}). Yeniden planlanıyor."
                )
            }

            val responseBody = response.body?.string() ?: ""
            val jsonResponse = JSONObject(responseBody)
            val content = jsonResponse.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            val proposal = parseActionProposalJson(content)
            if (proposal != null) {
                // Anti-Loop / Blocked Route Kontrolü
                val isBlocked = workingMemory.isRouteBlocked(
                    packageName = snapshot.packageName,
                    screenFingerprint = screenFingerprint,
                    target = proposal.target
                )

                val isTooFrequent = workingMemory.isAttemptKeyTooFrequent(
                    packageName = snapshot.packageName,
                    screenFingerprint = screenFingerprint,
                    subGoal = currentSubGoal,
                    actionType = proposal.actionType,
                    target = proposal.target ?: ""
                )

                if (isBlocked || isTooFrequent) {
                    Log.w(TAG, "Teklif edilen eylem engellenmiş rotaya veya başarısız deneme limitine takıldı. REPLAN öneriliyor.")
                    return@withContext ActionProposal(
                        actionType = AgentActionType.REPLAN,
                        reason = "Aynı eylem bu ekranda daha önce başarısız oldu veya engellendi. Alternatif rota planlanıyor."
                    )
                }

                return@withContext proposal
            } else {
                Log.w(TAG, "Groq eylem teklifi JSON'ı ayrıştırılamadı. REPLAN öneriliyor.")
                return@withContext ActionProposal(
                    actionType = AgentActionType.REPLAN,
                    reason = "Modül yanıtı geçerli eylem formatında değil."
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "proposeNextAction hatası: ${e.localizedMessage}", e)
            return@withContext ActionProposal(
                actionType = AgentActionType.REPLAN,
                reason = "Eylem üretme sırasında hata oluştu: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Eylemi icra etmeden önce SafetyGuardian denetiminden geçirir.
     */
    fun validateActionSafety(
        proposal: ActionProposal,
        snapshot: ScreenSnapshot,
        node: ScreenNodeData? = null
    ): SafetyDecision {
        val decision = SafetyGuardian.evaluateAction(
            actionType = proposal.actionType,
            targetText = proposal.target ?: proposal.textPayload,
            node = node,
            snapshot = snapshot
        )

        if (!decision.allowed) {
            Log.w(TAG, "SafetyGuardian eylemi ENGELLEDİ: ${decision.reason}")
            // Engellenen rotayı kaydet
            workingMemory.addBlockedRoute(
                BlockedRoute(
                    packageName = snapshot.packageName,
                    screenFingerprint = workingMemory.state.currentScreenFingerprint ?: "",
                    actionType = proposal.actionType,
                    target = proposal.target,
                    reason = decision.reason
                )
            )
        }

        return decision
    }

    /**
     * Eylem gerçekleştirildikten sonra doğrular ve çalışma belleğini günceller.
     */
    fun verifyAndRecordResult(
        proposal: ActionProposal,
        beforeSnapshot: ScreenSnapshot?,
        afterSnapshot: ScreenSnapshot?
    ): VerificationResult {
        val verification = ActionOutcomeVerifier.verifyOutcome(
            beforeSnapshot = beforeSnapshot,
            afterSnapshot = afterSnapshot,
            expectedOutcome = proposal.expectedOutcome
        )

        val currentStep = workingMemory.state.currentStepIndex + 1

        val beforeFp = workingMemory.state.currentScreenFingerprint ?: ""
        val afterFp = if (afterSnapshot != null) com.example.agent.core.ScreenFingerprintGenerator.generateFingerprint(afterSnapshot).value else beforeFp

        workingMemory.recordActionResult(
            stepIndex = currentStep,
            actionType = proposal.actionType,
            target = proposal.target ?: proposal.textPayload,
            isSuccess = verification.isVerified,
            resultSummary = verification.reason,
            fromScreenFingerprint = beforeFp,
            toScreenFingerprint = afterFp
        )

        if (beforeSnapshot != null && afterSnapshot != null) {
            val beforeFp = workingMemory.state.currentScreenFingerprint ?: ""
            val afterFp = com.example.agent.core.ScreenFingerprintGenerator.generateFingerprint(afterSnapshot).value
            stateGraph.recordTransition(
                fromPkg = beforeSnapshot.packageName,
                fromFingerprint = beforeFp,
                fromTitle = beforeSnapshot.activityName,
                toPkg = afterSnapshot.packageName,
                toFingerprint = afterFp,
                toTitle = afterSnapshot.activityName,
                actionType = proposal.actionType,
                target = proposal.target ?: proposal.textPayload,
                isSuccess = verification.isVerified
            )
        }

        if (verification.isVerified) {
            // SubGoal ilerletme kontrolü
            advanceSubGoalIfCompleted()
        }

        return verification
    }

    /**
     * Görevin tamamlandığını deterministik olarak doğrular.
     */
    fun verifyTaskCompletion(
        snapshot: ScreenSnapshot?
    ): Boolean {
        val spec = currentTaskSpec ?: return false
        if (snapshot == null) return false

        // 1. Eğer hedef uygulama tanımlıysa mevcut paket ile eşleşmeli
        if (!spec.targetApp.isNull_or_blank_and_null_str()) {
            val targetPkg = spec.targetApp!!.lowercase()
            val currentPkg = snapshot.packageName.lowercase()
            if (!currentPkg.contains(targetPkg) && !targetPkg.contains(currentPkg)) {
                Log.w(TAG, "Task complete doğrulaması başarısız: Hedef paket '$targetPkg' ekranda değil.")
                return false
            }
        }

        // 2. WhatsApp spesifik doğrulamaları
        val isWhatsApp = spec.targetApp?.lowercase() == "whatsapp" || snapshot.packageName.lowercase().contains("whatsapp")
        if (isWhatsApp) {
            // Canım Annem sohbet ekranında mıyız? Ekranda "Canım Annem" metni bulunmalı (sohbet başlığı veya ögelerden biri)
            val entity = spec.targetEntity
            if (!entity.isNullOrBlank()) {
                val hasEntityInTexts = snapshot.texts.any { it.contains(entity, ignoreCase = true) }
                val hasEntityInClickable = snapshot.clickableNodes.any {
                    it.text.contains(entity, ignoreCase = true) || it.contentDescription.contains(entity, ignoreCase = true)
                }
                if (!hasEntityInTexts && !hasEntityInClickable) {
                    Log.w(TAG, "Task complete doğrulaması başarısız: Canım Annem sohbet/kişi adı ekranda bulunamadı.")
                    return false
                }
            }

            // Gönderilen mesaj metni ekranda mı? (payloadText)
            val payload = spec.payloadText
            if (!payload.isNullOrBlank()) {
                val hasPayloadInTexts = snapshot.texts.any { it.contains(payload, ignoreCase = true) }
                val hasPayloadInClickable = snapshot.clickableNodes.any {
                    it.text.contains(payload, ignoreCase = true) || it.contentDescription.contains(payload, ignoreCase = true)
                }
                if (!hasPayloadInTexts && !hasPayloadInClickable) {
                    Log.w(TAG, "Task complete doğrulaması başarısız: Gönderilmek istenen mesaj metni '$payload' ekranda doğrulanmadı.")
                    return false
                }
            }
        }

        return true
    }

    private fun advanceSubGoalIfCompleted() {
        val plan = currentPlan ?: return
        if (plan.currentSubGoalIndex < plan.subGoals.size - 1) {
            val nextIndex = plan.currentSubGoalIndex + 1
            currentPlan = plan.copy(currentSubGoalIndex = nextIndex)
            val nextSubGoal = plan.subGoals[nextIndex]
            workingMemory.updateSubGoal(nextSubGoal.description, stepIndex = nextIndex)
            Log.i(TAG, "SubGoal başarıyla ilerletildi [${nextIndex + 1}/${plan.subGoals.size}]: '${nextSubGoal.description}'")
        } else {
            currentPlan = plan.copy(planStatus = PlanStatus.COMPLETED)
            Log.i(TAG, "Tüm SubGoal'ler tamamlandı. Görev tamamlanmaya hazır.")
        }
    }

    suspend fun parseTaskSpecFromGoal(
        goal: String,
        intentType: UserIntent,
        apiKey: String = "",
        model: String? = null
    ): TaskSpec = withContext(Dispatchers.IO) {
        if (apiKey.isNotBlank()) {
            try {
                val systemPrompt = """
                    Sen bir Android TaskSpec ayrıştırıcısısın. Kullanıcının amacını (goal) analiz etmeli ve bir TaskSpec JSON nesnesi döndürmelisin.
                    
                    Girdi örneği: "WhatsApp'tan Canım Anneme merhaba yaz"
                    Çıktı örneği:
                    {
                      "targetApp": "WhatsApp",
                      "targetEntity": "Canım Annem",
                      "requestedAction": "SEND_MESSAGE",
                      "payloadText": "merhaba",
                      "safetyLevel": "NORMAL",
                      "completionCriteria": "Canım Annem sohbetinde 'merhaba' mesajının gönderildiği doğrulanmalıdır."
                    }
                    
                    Kurallar:
                    - targetApp: Açılacak veya işlem yapılacak uygulamanın adı (örn: WhatsApp, Galeri, Ayarlar vb.). Uygulama belirtilmemişse null.
                    - targetEntity: İşlemin yapılacağı kişi, grup veya nesne (örn: Canım Annem, Annem vb.). Yoksa null.
                    - requestedAction: Yapılacak ana eylem (örn: SEND_MESSAGE, OPEN_CHAT, OPEN_APP, EXPLORE vb.).
                    - payloadText: Gönderilecek mesaj içeriği veya yazılacak metin. Yoksa null.
                    - safetyLevel: NORMAL, STRICT_FINANCIAL veya HIGH_RISK.
                    - completionCriteria: Görevin başarıyla tamamlandığını doğrulamak için ekran görüntüsünde aranacak kriter.
                    
                    Kesinlikle sadece saf JSON döndür, açıklama veya markdown tırnakları ekleme.
                """.trimIndent()

                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", goal)
                    })
                }

                val requestBodyJson = JSONObject().apply {
                    put("model", model?.ifBlank { DEFAULT_MODEL } ?: DEFAULT_MODEL)
                    put("messages", messages)
                    put("temperature", 0.0)
                    put("max_tokens", 250)
                }.toString()

                val request = Request.Builder()
                    .url(GROQ_ENDPOINT)
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .post(requestBodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    val jsonResponse = JSONObject(responseBody)
                    val content = jsonResponse.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                    
                    val cleanJson = content.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
                    val root = JSONObject(cleanJson)
                    
                    val parsedTargetApp = root.optString("targetApp", null).takeIf { !it.isNull_or_blank_and_null_str() }
                    val parsedTargetEntity = root.optString("targetEntity", null).takeIf { !it.isNull_or_blank_and_null_str() }
                    val parsedRequestedAction = root.optString("requestedAction", null).takeIf { !it.isNull_or_blank_and_null_str() }
                    val parsedPayloadText = root.optString("payloadText", null).takeIf { !it.isNull_or_blank_and_null_str() }
                    val parsedSafetyStr = root.optString("safetyLevel", "NORMAL")
                    val parsedSafetyLevel = try { SafetyLevel.valueOf(parsedSafetyStr.uppercase()) } catch(e: Exception) { SafetyLevel.NORMAL }
                    val parsedCompletionCriteria = root.optString("completionCriteria", "Hedef eylem gerçekleştirildi")

                    Log.i(TAG, "TaskSpec LLM ile başarıyla ayrıştırıldı: App=$parsedTargetApp, Entity=$parsedTargetEntity, Action=$parsedRequestedAction, Payload=$parsedPayloadText")
                    return@withContext TaskSpec(
                        originalGoal = goal,
                        intentType = intentType,
                        targetApp = parsedTargetApp,
                        targetEntity = parsedTargetEntity,
                        requestedAction = parsedRequestedAction,
                        payloadText = parsedPayloadText,
                        safetyLevel = parsedSafetyLevel,
                        completionCriteria = parsedCompletionCriteria
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "TaskSpec LLM ayrıştırma hatası, fallback kullanılacak: ${e.localizedMessage}")
            }
        }

        // Deterministic Fallback
        val lowerGoal = goal.lowercase()
        var targetApp: String? = null
        var targetEntity: String? = null
        var payloadText: String? = null
        var requestedAction: String? = null
        var completionCriteria = "Hedef eylem gerçekleştirildi"

        if (lowerGoal.contains("whatsapp") || lowerGoal.contains("annemle konuşma") || lowerGoal.contains("annemle konuşmayı")) {
            targetApp = "WhatsApp"
        }
        if (lowerGoal.contains("ayarlar")) targetApp = "Ayarlar"
        if (lowerGoal.contains("galeri")) targetApp = "Galeri"

        if (lowerGoal.contains("annem") || lowerGoal.contains("anneme")) {
            targetEntity = "Canım Annem"
        }

        if (lowerGoal.contains("mesaj") || lowerGoal.contains("yaz") || lowerGoal.contains("gönder")) {
            requestedAction = "SEND_MESSAGE"
        } else if (lowerGoal.contains("sohbet") || lowerGoal.contains("konuşma") || (lowerGoal.contains("aç") && targetEntity != null)) {
            requestedAction = "OPEN_CHAT"
        } else if (lowerGoal.contains("keşfet") || lowerGoal.contains("gez")) {
            requestedAction = "EXPLORE"
        } else {
            requestedAction = "OPEN_APP"
        }

        // Helper to extract message payload
        val quoteMatch = Regex("""['"]([^'"]+)['"]""").find(goal)
        if (quoteMatch != null) {
            payloadText = quoteMatch.groupValues[1]
        } else {
            if (lowerGoal.endsWith(" yaz") || lowerGoal.endsWith(" yazın") || lowerGoal.endsWith(" gönder") || lowerGoal.endsWith(" at")) {
                var clean = goal
                clean = clean.replace(Regex("(?i)\\s+(yaz|yazın|gönder|at)$"), "").trim()
                
                val prefixesToRemove = listOf(
                    "whatsapp'tan", "whatsapptan", "whatsapp'ta", "whatsappta",
                    "canım anneme", "canım annem", "anneme", "annem",
                    "canım babama", "babama"
                )
                var lowercaseClean = clean.lowercase()
                var changed = true
                while (changed) {
                    changed = false
                    for (prefix in prefixesToRemove) {
                        if (lowercaseClean.startsWith(prefix)) {
                            clean = clean.substring(prefix.length).trim()
                            lowercaseClean = clean.lowercase()
                            changed = true
                        }
                    }
                    val prepList = listOf("'dan", "dan", "'tan", "tan", "'a", "a", "'e", "e", "'ı", "ı", "'i", "i")
                    for (prep in prepList) {
                        if (lowercaseClean.startsWith(prep)) {
                            clean = clean.substring(prep.length).trim()
                            lowercaseClean = clean.lowercase()
                            changed = true
                        }
                    }
                }
                if (clean.isNotBlank()) payloadText = clean
            }
            if (payloadText == null && lowerGoal.contains("merhaba")) {
                payloadText = "merhaba"
            }
        }

        // Refine completion criteria
        if (requestedAction == "SEND_MESSAGE" && targetEntity != null && payloadText != null) {
            completionCriteria = "$targetEntity sohbetinde '$payloadText' mesajının gönderildiği doğrulanmalıdır."
        } else if (requestedAction == "OPEN_CHAT" && targetEntity != null) {
            completionCriteria = "$targetEntity sohbet ekranı açılmalı"
        } else if (requestedAction == "EXPLORE") {
            completionCriteria = "Cihaz güvenli şekilde keşfedilmeli"
        } else if (targetApp != null) {
            completionCriteria = "$targetApp uygulaması açılmalı"
        }

        Log.i(TAG, "Deterministic fallback ile TaskSpec ayrıştırıldı: App=$targetApp, Entity=$targetEntity, Action=$requestedAction, Payload=$payloadText")
        TaskSpec(
            originalGoal = goal,
            intentType = intentType,
            targetApp = targetApp,
            targetEntity = targetEntity,
            requestedAction = requestedAction,
            payloadText = payloadText,
            safetyLevel = SafetyLevel.NORMAL,
            completionCriteria = completionCriteria
        )
    }

    private fun parseActionProposalJson(jsonText: String): ActionProposal? {
        return try {
            val cleanJson = jsonText.substringAfter("{").substringBeforeLast("}").let { "{$it}" }
            val root = JSONObject(cleanJson)
            val type = root.optString("actionType", AgentActionType.NO_ACTION)
            val target = root.optString("target", null).takeIf { !it.isNull_or_blank_and_null_str() }
            val targetIndex = if (root.has("targetIndex")) root.optInt("targetIndex", -1).takeIf { it >= 0 } else null
            val payload = root.optString("textPayload", null).takeIf { !it.isNull_or_blank_and_null_str() }
            val reason = root.optString("reason", "")

            var expectedSpec: ExpectedOutcomeSpec? = null
            if (root.has("expectedOutcome")) {
                val expObj = root.optJSONObject("expectedOutcome")
                if (expObj != null) {
                    val screenChange = expObj.optBoolean("screenChangeExpected", true)
                    val expPkg = expObj.optString("expectedPackage", null).takeIf { !it.isNull_or_blank_and_null_str() }
                    val expTextArray = expObj.optJSONArray("expectedText")
                    val textList = mutableListOf<String>()
                    if (expTextArray != null) {
                        for (i in 0 until expTextArray.length()) {
                            textList.add(expTextArray.getString(i))
                        }
                    }
                    expectedSpec = ExpectedOutcomeSpec(
                        screenChangeExpected = screenChange,
                        expectedPackage = expPkg,
                        expectedText = textList
                    )
                }
            }

            val memoryKey = root.optString("memoryKey", null).takeIf { !it.isNull_or_blank_and_null_str() }
            val memoryValue = root.optString("memoryValue", null).takeIf { !it.isNull_or_blank_and_null_str() }

            ActionProposal(
                actionType = type,
                target = target,
                targetIndex = targetIndex,
                textPayload = payload,
                reason = reason,
                expectedOutcome = expectedSpec,
                memoryKey = memoryKey,
                memoryValue = memoryValue
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseActionProposalJson hatası: ${e.localizedMessage}")
            null
        }
    }

    private fun String?.isNull_or_blank_and_null_str(): Boolean {
        if (this == null) return true
        val trimmed = this.trim()
        return trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)
    }
}
