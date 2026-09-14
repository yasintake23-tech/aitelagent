package com.example.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.example.agent.core.ActionVerifier
import com.example.agent.core.AgentLifecycleManager
import com.example.agent.core.AgentState
import com.example.agent.core.AgentTaskSession
import com.example.agent.core.IntentRouter
import com.example.agent.core.RecoveryActionType
import com.example.agent.core.RecoveryStrategy
import com.example.agent.core.TaskBudget
import com.example.agent.core.UserIntent
import com.example.agent.core.VerificationResult
import com.example.ai.AIAgentScreenReasoner
import com.example.ai.AgentActionType
import com.example.ai.GroundingAction
import com.example.ai.VisualGroundingEngine
import com.example.agent.brain.AgentBrain
import com.example.agent.brain.ActionProposal
import com.example.agent.brain.AgentActionType as BrainActionType
import com.example.data.security.CredentialStore
import com.example.data.security.AgentLogStore
import com.example.data.local.AssistantDatabase
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

data class AgentExecutionResult(
    val isSuccess: Boolean,
    val actionType: String,
    val speechFeedback: String,
    val technicalLog: String,
    val clickCoordinates: PointF? = null,
    val targetPackage: String? = null
)

object DeviceAgentExecutor {

    private const val TAG = "DeviceAgentExecutor"

    private fun persistLog(context: Context?, level: String, message: String) {
        if (context != null) AgentLogStore.record(context, level, TAG, message)
    }

    /**
     * Aggressively normalizes Turkish app names by removing verbs, suffixes ('a, 'e, 'ı, 'i, 'ye, 'ya, etc.) and slang.
     */
    fun normalizeAppQuery(raw: String): String {
        var text = raw.lowercase(Locale("tr", "TR")).trim()

        // 1. Remove common action phrases / words
        val redundantWords = listOf(
            "uygulamasını", "uygulaması", "uygulamaya", "uygulamayı", "uygulama", "app", "application",
            "açsana", "açar mısın", "açarmısın", "aç", "başlatsana", "başlat",
            "girsene", "girer misin", "gir", "baksana", "bak", "çalıştır", "göster",
            "lütfen", "hadi", "şimdi", "canım", "kanka", "kardeşim", "bize"
        )

        for (w in redundantWords) {
            text = text.replace(Regex("\\b$w\\b"), " ")
        }
        text = text.trim().replace(Regex("\\s+"), " ")

        // 2. Slang / colloquial mappings
        when (text) {
            "wp", "watsap", "vatsap", "whatsappa", "whatsapa", "whatsapp'a", "whatsapp'ı", "whatsappi", "whatsapp'e" -> return "WhatsApp"
            "yt", "yutub", "yutup", "youtube'a", "youtubeye", "youtuba", "youtube'u" -> return "YouTube"
            "insta", "ig", "instagrama", "instagram'a", "instagram'ı", "instagrami" -> return "Instagram"
            "ayarlara", "ayarları", "ayarlari", "ayarlar'a", "settings" -> return "Ayarlar"
            "galeriye", "galeriyi", "galeri'ye", "fotoğraflara", "fotolara", "fotoğraf" -> return "Galeri"
            "kameraya", "kamerayı", "kamera'ya", "fotoğraf çek" -> return "Kamera"
            "rehbere", "rehberi", "kişilere", "kişiler'e", "kontaklar" -> return "Kişiler"
            "mesajlara", "mesajları", "sms'e", "sms" -> return "Mesajlar"
            "haritalara", "haritaya", "haritayı", "maps", "harita'ya" -> return "Haritalar"
            "hesap makinesine", "hesap makinesini", "hesap makinası", "hesap", "calculator" -> return "Hesap Makinesi"
            "internete", "tarayıcıya", "tarayıcıyı", "chrome'a", "google" -> return "Chrome"
            "mağazaya", "mağazayı", "play store'a", "playstore", "play store'u" -> return "Play Store"
            "saate", "saati", "alarma", "alarmı" -> return "Saat"
            "dosyalara", "dosyaları", "belgelere" -> return "Dosyalar"
            "gmail'e", "maillere", "postaya", "e-posta" -> return "Gmail"
            "spotify'a", "spotifay", "müzik" -> return "Spotify"
        }

        // 3. Turkish suffix stripping (apostrophe or attached)
        val suffixPatterns = listOf(
            "'[a-zçğıöşü]+",
            "(ya|ye|yı|yi|yu|yü)$",
            "(da|de|ta|te)$",
            "(dan|den|tan|ten)$",
            "(ın|in|un|ün)$",
            "(na|ne|nda|nde)$",
            "(a|e|ı|i|u|ü)$"
        )

        var stripped = text
        for (pattern in suffixPatterns) {
            val r = Regex(pattern)
            if (r.containsMatchIn(stripped) && stripped.length > 3) {
                val candidate = stripped.replace(r, "").trim()
                if (candidate.length >= 2) {
                    stripped = candidate
                    break
                }
            }
        }

        return (stripped.ifBlank { text }).replaceFirstChar { it.uppercase() }
    }

    /**
     * Checks if a string contains explicit app launching intent.
     */
    fun isAppLaunchIntent(text: String): Boolean {
        val lower = text.lowercase(Locale("tr", "TR"))
        val triggers = listOf("aç", "gir", "başlat", "çalıştır", "uygulama", "uygulaması", "app", "baksana")
        return triggers.any { lower.contains(it) }
    }

    /**
     * Pure Human-Like Visual App Opener:
     * Does NOT use Android background Intent.
     * Searches home screen pages, app drawer, and folders with Gemini Vision & touches screen with verification.
     */
    suspend fun openAppVisually(
        context: Context,
        appQuery: String,
        onStepUpdate: ((String) -> Unit)? = null
    ): AgentExecutionResult = withContext(Dispatchers.Main) {
        val message = "Eski görsel uygulama açma yolu devre dışı; ortak Multi-Brain yürütücüsü kullanılmalı."
        AgentLogStore.record(context, "WARN", TAG, "$message target=$appQuery")
        onStepUpdate?.invoke(message)
        AgentExecutionResult(false, "LEGACY_VISUAL_OPENER_DISABLED", message, message)
    }

    /**
     * Legacy WhatsApp-specific automation is intentionally removed from the runtime.
     * All device tasks must go through the shared Multi-Brain AgentBrain pipeline.
     */
    @Deprecated("Use executeSmartAutonomousTask with the Multi-Brain AgentBrain")
    suspend fun executeWhatsAppMessageWorkflow(
        context: Context,
        contactName: String,
        message: String,
        onStepUpdate: ((String) -> Unit)? = null
    ): AgentExecutionResult = withContext(Dispatchers.Main) {
        onStepUpdate?.invoke("Legacy WhatsApp akışı devre dışı; ortak Multi-Brain yürütücüsü kullanılmalı.")
        AgentExecutionResult(
            isSuccess = false,
            actionType = "LEGACY_WORKFLOW_DISABLED",
            speechFeedback = "Bu görev ortak otonom ajan akışı üzerinden yürütülmeli.",
            technicalLog = "Legacy WhatsApp workflow is intentionally disabled."
        )
    }

    suspend fun executeAutonomousReActLoop(
        context: Context,
        goalPrompt: String,
        reasoner: AIAgentScreenReasoner,
        maxSteps: Int = 10,
        onStatusUpdate: ((String) -> Unit)? = null
    ): AgentExecutionResult = withContext(Dispatchers.Main) {
        val service = AiDeviceAccessibilityService.instance
        if (service == null) {
            return@withContext AgentExecutionResult(
                isSuccess = false,
                actionType = "ACCESSIBILITY_UNAVAILABLE",
                speechFeedback = "Otonom görevi yürütmek için Erişilebilirlik iznine ihtiyacım var.",
                technicalLog = "AiDeviceAccessibilityService is not running"
            )
        }

        val budget = TaskBudget(
            maxSteps = maxSteps,
            maxRetriesPerStep = 3,
            overallTimeoutMs = 180_000L,
            perStepTimeoutMs = 20_000L,
            maxConsecutiveFailures = 3
        )

        var taskSession = AgentLifecycleManager.startSession(
            taskGoal = goalPrompt,
            budget = budget,
            initialState = AgentState.PLANNING
        )

        val visitedElements = mutableSetOf<String>()
        val database = AssistantDatabase.getDatabase(context)
        val profile = withContext(Dispatchers.IO) { database.userProfileDao().getUserProfileOnce() }
        val memories = withContext(Dispatchers.IO) { database.memoryDao().getAllMemoriesOnce() }

        var consecutiveFailures = 0
        var currentStep = 1
        var finalSummary = ""
        var isSuccess = false

        onStatusUpdate?.invoke("Ekran inceleniyor ve adımlar planlanıyor...")

        while (!taskSession.isFinished && currentStep <= budget.maxSteps) {
            // Check if cancelled externally
            val loopStartSession = AgentLifecycleManager.currentSession.value
            if (loopStartSession == null || loopStartSession.taskId != taskSession.taskId || loopStartSession.isCancelled) {
                taskSession = taskSession.copy(currentState = AgentState.CANCELLED, isCancelled = true)
                break
            }

            // Overall timeout check
            if (taskSession.isTimedOut()) {
                Log.w(TAG, "Task timed out after ${System.currentTimeMillis() - taskSession.startTimeMs} ms")
                val timeoutMsg = "Görev toplam zaman aşımına ulaştı (${budget.overallTimeoutMs / 1000} sn)."
                taskSession = taskSession.copy(
                    currentState = AgentState.FAILED,
                    errorMessage = timeoutMsg
                )
                AgentLifecycleManager.failSession(taskSession.taskId, timeoutMsg)
                break
            }

            // 1. OBSERVING
            taskSession = taskSession.copy(currentState = AgentState.OBSERVING, currentStep = currentStep)
            AgentLifecycleManager.transitionState(taskSession.taskId, AgentState.OBSERVING, currentStep, "Ekran inceleniyor...")
            val screenshot = service.captureLiveScreenshotAsync()
            val snapshot = service.updateLiveSnapshot()

            // 2. PLANNING
            taskSession = taskSession.copy(currentState = AgentState.PLANNING)
            AgentLifecycleManager.transitionState(taskSession.taskId, AgentState.PLANNING, currentStep, "Planlanıyor...")
            val decision = reasoner.decideNextScreenAction(
                snapshot = snapshot,
                taskPrompt = goalPrompt,
                stepNumber = currentStep,
                visitedElements = visitedElements,
                memories = memories,
                profile = profile,
                liveScreenshot = screenshot
            )

            val displayStatus = if (decision.thought.isNotBlank()) decision.thought else decision.speechStatus
            onStatusUpdate?.invoke("Adım $currentStep (${taskSession.currentState.name}): $displayStatus")

            // Check completion signal
            if (decision.actionType == AgentActionType.TASK_COMPLETE) {
                isSuccess = true
                finalSummary = decision.completionSummary.ifBlank { "Görev başarıyla tamamlandı." }
                taskSession = taskSession.copy(
                    currentState = AgentState.COMPLETED,
                    resultSummary = finalSummary
                )
                AgentLifecycleManager.completeSession(taskSession.taskId, finalSummary)
                break
            }

            // 3. ACTING
            taskSession = taskSession.copy(currentState = AgentState.ACTING)
            val actingStatus = if (decision.thought.isNotBlank()) decision.thought else "Eylem gerçekleştiriliyor..."
            val actingOk = AgentLifecycleManager.transitionState(taskSession.taskId, AgentState.ACTING, currentStep, actingStatus)
            val preActSession = AgentLifecycleManager.currentSession.value

            if (!actingOk || preActSession == null || preActSession.taskId != taskSession.taskId || preActSession.isCancelled || preActSession.isFinished) {
                Log.w(TAG, "Task cancelled/invalid before physical action execution. Aborting physical action.")
                taskSession = taskSession.copy(currentState = AgentState.CANCELLED, isCancelled = true)
                break
            }

            var verificationResult: VerificationResult? = null
            var targetNode = if (decision.targetIndex in snapshot.clickableNodes.indices) {
                snapshot.clickableNodes[decision.targetIndex]
            } else null

            when (decision.actionType) {
                AgentActionType.CLICK_NODE -> {
                    val coords = decision.coordinates ?: targetNode?.let {
                        PointF(it.bounds.centerX().toFloat(), it.bounds.centerY().toFloat())
                    }

                    if (coords != null) {
                        val nodeText = targetNode?.text?.ifBlank { null }
                        val label = if (decision.targetText.isNotBlank()) decision.targetText else (nodeText ?: "Node_${decision.targetIndex}")
                        visitedElements.add(label)
                        verificationResult = service.clickAtWithVerificationResult(
                            x = coords.x,
                            y = coords.y,
                            label = label,
                            targetNode = targetNode
                        )
                    } else {
                        verificationResult = VerificationResult.failed("Geçersiz hedef veya koordinat.")
                    }
                }
                AgentActionType.CLICK_COORD -> {
                    if (decision.coordinates != null) {
                        verificationResult = service.clickAtWithVerificationResult(
                            x = decision.coordinates.x,
                            y = decision.coordinates.y,
                            label = "coord"
                        )
                    } else {
                        verificationResult = VerificationResult.failed("Koordinat bulunamadı.")
                    }
                }
                AgentActionType.TYPE_TEXT -> {
                    if (decision.textToType.isNotBlank()) {
                        service.typeTextIntoNode(decision.textToType)
                        service.awaitScreenSettled(800L, 200L)
                        val afterSnapshot = service.updateLiveSnapshot()
                        verificationResult = ActionVerifier.verifyTextOutcome(
                            beforeSnapshot = snapshot,
                            afterSnapshot = afterSnapshot,
                            typedText = decision.textToType
                        )
                    }
                }
                AgentActionType.SWIPE_DOWN -> {
                    service.swipeDownAsync()
                    service.awaitScreenSettled(800L, 200L)
                    val afterSnapshot = service.updateLiveSnapshot()
                    verificationResult = ActionVerifier.verifyScrollOutcome(snapshot, afterSnapshot)
                }
                AgentActionType.SWIPE_UP -> {
                    service.swipeUpAsync()
                    service.awaitScreenSettled(800L, 200L)
                    val afterSnapshot = service.updateLiveSnapshot()
                    verificationResult = ActionVerifier.verifyScrollOutcome(snapshot, afterSnapshot)
                }
                AgentActionType.SWIPE_LEFT -> {
                    service.swipeLeftAsync()
                    service.awaitScreenSettled(800L, 200L)
                    val afterSnapshot = service.updateLiveSnapshot()
                    verificationResult = ActionVerifier.verifyScrollOutcome(snapshot, afterSnapshot)
                }
                AgentActionType.SWIPE_RIGHT -> {
                    service.swipeRightAsync()
                    service.awaitScreenSettled(800L, 200L)
                    val afterSnapshot = service.updateLiveSnapshot()
                    verificationResult = ActionVerifier.verifyScrollOutcome(snapshot, afterSnapshot)
                }
                AgentActionType.OPEN_APP -> {
                    if (decision.appName.isNotBlank()) {
                        openAppVisually(context, decision.appName)
                        service.awaitScreenSettled(1000L, 200L)
                        val afterSnapshot = service.updateLiveSnapshot()
                        verificationResult = ActionVerifier.verifyAppLaunchOutcome(
                            expectedAppName = decision.appName,
                            currentPackage = afterSnapshot.packageName,
                            afterSnapshot = afterSnapshot
                        )
                    }
                }
                AgentActionType.OPEN_QUICK_SETTINGS -> {
                    service.openQuickSettings()
                    service.awaitScreenSettled(600L, 200L)
                    verificationResult = VerificationResult.verified("Hızlı ayarlar açıldı.")
                }
                AgentActionType.OPEN_NOTIFICATIONS -> {
                    service.openNotifications()
                    service.awaitScreenSettled(600L, 200L)
                    verificationResult = VerificationResult.verified("Bildirimler açıldı.")
                }
                AgentActionType.VOLUME_UP -> {
                    service.volumeUp()
                    verificationResult = VerificationResult.verified("Ses artırıldı.")
                }
                AgentActionType.VOLUME_DOWN -> {
                    service.volumeDown()
                    verificationResult = VerificationResult.verified("Ses azaltıldı.")
                }
                AgentActionType.PRESS_BACK -> {
                    service.goBack()
                    service.awaitScreenSettled(600L, 200L)
                    verificationResult = VerificationResult.verified("Geri tuşuna basıldı.")
                }
                AgentActionType.PRESS_HOME -> {
                    service.goHome()
                    service.awaitScreenSettled(600L, 200L)
                    verificationResult = VerificationResult.verified("Ana sayfa tuşuna basıldı.")
                }
                else -> {
                    verificationResult = VerificationResult.verified("Eylem gerçekleştirildi.")
                }
            }

            // 4. VERIFYING & RECOVERING
            val vResult = verificationResult ?: VerificationResult.unchanged("Aksiyon sonrası ekran durumu değerlendirilemedi.")

            if (vResult.isSuccess) {
                consecutiveFailures = 0
                taskSession = taskSession.copy(currentState = AgentState.VERIFYING)
                AgentLifecycleManager.transitionState(taskSession.taskId, AgentState.VERIFYING, currentStep, "Sonuç kontrol ediliyor...")
                Log.d(TAG, "Adım $currentStep Doğrulandı: ${vResult.reason}")
            } else {
                taskSession = taskSession.copy(currentState = AgentState.RECOVERING)
                AgentLifecycleManager.transitionState(taskSession.taskId, AgentState.RECOVERING, currentStep, "Alternatif yol aranıyor...")
                Log.w(TAG, "Adım $currentStep Doğrulanamadı/Değişmedi: ${vResult.reason}")

                val recoveryPlan = RecoveryStrategy.evaluateRecovery(
                    verificationResult = vResult,
                    attemptCount = 1,
                    consecutiveFailures = consecutiveFailures,
                    budget = budget,
                    targetNode = targetNode
                )

                onStatusUpdate?.invoke("Kurtarma Denemesi: ${recoveryPlan.explanation}")
                Log.i(TAG, "Kurtarma Adımı Executing: ${recoveryPlan.actionType} (${recoveryPlan.explanation})")

                when (recoveryPlan.actionType) {
                    RecoveryActionType.RETRY_WITH_JITTER -> {
                        if (decision.coordinates != null) {
                            val newX = decision.coordinates.x + recoveryPlan.suggestedOffsetX
                            val newY = decision.coordinates.y + recoveryPlan.suggestedOffsetY
                            service.clickAtWithVerification(newX, newY, label = "jitter_retry", targetNode = targetNode)
                        }
                    }
                    RecoveryActionType.SWIPE_TO_UNBLOCK -> {
                        service.swipeUpAsync()
                        service.awaitScreenSettled(800L, 200L)
                    }
                    RecoveryActionType.PRESS_BACK_AND_RETRY -> {
                        service.goBack()
                        service.awaitScreenSettled(800L, 200L)
                    }
                    RecoveryActionType.REPLAN_REQUIRED -> {
                        // Let loop proceed to next step for AI model to receive updated screen snapshot and re-plan
                    }
                    RecoveryActionType.ABORT_TASK -> {
                        taskSession = taskSession.copy(
                            currentState = AgentState.FAILED,
                            errorMessage = recoveryPlan.explanation,
                            isCancelled = true
                        )
                        AgentLifecycleManager.failSession(taskSession.taskId, recoveryPlan.explanation)
                        Log.e(TAG, "Sonsuz döngü koruması tetiklendi: ${recoveryPlan.explanation}")
                        break
                    }
                }

                consecutiveFailures = recoveryPlan.consecutiveFailures
            }

            // Save discovered insights if any
            if (!decision.memoryKey.isNullOrBlank() && !decision.memoryValue.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    database.memoryDao().insertMemory(
                        MemoryEntryEntity(
                            category = MemoryCategory.PREFERENCE.name,
                            key = decision.memoryKey,
                            value = decision.memoryValue,
                            importance = 1,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }

            service.awaitScreenSettled(1000L, 200L)
            currentStep++
        }

        val isFinishedSuccessfully = isSuccess || (taskSession.currentState == AgentState.COMPLETED)
        if (isFinishedSuccessfully) {
            AgentLifecycleManager.completeSession(taskSession.taskId, finalSummary.ifBlank { "Görev başarıyla tamamlandı." })
        } else if (taskSession.currentState != AgentState.FAILED && taskSession.currentState != AgentState.CANCELLED) {
            AgentLifecycleManager.failSession(taskSession.taskId, taskSession.errorMessage ?: "Görev tamamlanamadı.")
        }

        return@withContext AgentExecutionResult(
            isSuccess = isFinishedSuccessfully,
            actionType = if (isFinishedSuccessfully) "AUTONOMOUS_TASK_COMPLETE" else "AUTONOMOUS_LOOP_FINISHED",
            speechFeedback = if (finalSummary.isNotBlank()) finalSummary else (taskSession.errorMessage ?: "İşlem tamamlandı."),
            technicalLog = "Autonomous ReAct loop executed ${currentStep - 1} steps (State=${taskSession.currentState}, SessionId=${taskSession.taskId})"
        )
    }

    /**
     * Handles explicit navigation/system commands before they enter the AI planner.
     * Returns a successful result only when a concrete physical/system action was dispatched.
     */
    suspend fun performNavigation(command: String): AgentExecutionResult = withContext(Dispatchers.Main) {
        val service = AiDeviceAccessibilityService.instance
            ?: return@withContext AgentExecutionResult(
                isSuccess = false,
                actionType = "ACCESSIBILITY_UNAVAILABLE",
                speechFeedback = "Cihaz kontrolü için Erişilebilirlik izni gerekli.",
                technicalLog = "performNavigation: AccessibilityService is null"
            )

        val lower = command.lowercase(Locale("tr", "TR")).trim()

        suspend fun swipeResult(action: String, block: suspend () -> Unit): AgentExecutionResult {
            block()
            return AgentExecutionResult(
                isSuccess = true,
                actionType = action,
                speechFeedback = "Tamam, $action yapıldı.",
                technicalLog = "performNavigation dispatched: $action"
            )
        }

        when {
            lower in setOf("geri", "geri dön", "geri git", "back", "önceki") ||
                    lower.contains("geri dön") || lower.contains("geri git") -> {
                val ok = service.goBack()
                return@withContext AgentExecutionResult(
                    isSuccess = ok,
                    actionType = "PRESS_BACK",
                    speechFeedback = if (ok) "Geri gidildi." else "Geri işlemi gerçekleştirilemedi.",
                    technicalLog = "performNavigation PRESS_BACK=$ok"
                )
            }

            lower in setOf("ana sayfa", "ana ekrana dön", "eve git", "home") ||
                    lower.contains("ana sayfaya git") || lower.contains("ana ekrana dön") -> {
                val ok = service.goHome()
                return@withContext AgentExecutionResult(
                    isSuccess = ok,
                    actionType = "PRESS_HOME",
                    speechFeedback = if (ok) "Ana ekrana gidildi." else "Ana ekrana gidilemedi.",
                    technicalLog = "performNavigation PRESS_HOME=$ok"
                )
            }

            lower.contains("aşağı kaydır") || lower.contains("aşağıya kaydır") ||
                    lower.contains("aşağı kaydırma") -> return@withContext swipeResult("SWIPE_DOWN") {
                service.swipeDownAsync()
            }

            lower.contains("yukarı kaydır") || lower.contains("yukarıya kaydır") ||
                    lower.contains("yukarı kaydırma") -> return@withContext swipeResult("SWIPE_UP") {
                service.swipeUpAsync()
            }

            lower.contains("sola kaydır") || lower.contains("sola sürükle") -> return@withContext swipeResult("SWIPE_LEFT") {
                service.swipeLeftAsync()
            }

            lower.contains("sağa kaydır") || lower.contains("sağa sürükle") -> return@withContext swipeResult("SWIPE_RIGHT") {
                service.swipeRightAsync()
            }

            lower.contains("bildirimleri aç") || lower.contains("bildirim paneli") ||
                    lower == "bildirimler" -> {
                val ok = service.openNotifications()
                return@withContext AgentExecutionResult(
                    isSuccess = ok,
                    actionType = "OPEN_NOTIFICATIONS",
                    speechFeedback = if (ok) "Bildirimler açıldı." else "Bildirimler açılamadı.",
                    technicalLog = "performNavigation OPEN_NOTIFICATIONS=$ok"
                )
            }

            lower.contains("hızlı ayarları aç") || lower.contains("hızlı ayarlar") -> {
                val ok = service.openQuickSettings()
                return@withContext AgentExecutionResult(
                    isSuccess = ok,
                    actionType = "OPEN_QUICK_SETTINGS",
                    speechFeedback = if (ok) "Hızlı ayarlar açıldı." else "Hızlı ayarlar açılamadı.",
                    technicalLog = "performNavigation OPEN_QUICK_SETTINGS=$ok"
                )
            }

            lower.contains("son uygulamalar") || lower.contains("uygulama geçmişi") || lower == "recents" -> {
                val ok = service.pressRecents()
                return@withContext AgentExecutionResult(
                    isSuccess = ok,
                    actionType = "PRESS_RECENTS",
                    speechFeedback = if (ok) "Son uygulamalar açıldı." else "Son uygulamalar açılamadı.",
                    technicalLog = "performNavigation PRESS_RECENTS=$ok"
                )
            }

            lower.contains("sesi aç") || lower.contains("sesi yükselt") || lower.contains("ses artır") -> {
                val ok = service.volumeUp()
                return@withContext AgentExecutionResult(
                    isSuccess = ok,
                    actionType = "VOLUME_UP",
                    speechFeedback = if (ok) "Ses yükseltildi." else "Ses yükseltilemedi.",
                    technicalLog = "performNavigation VOLUME_UP=$ok"
                )
            }

            lower.contains("sesi kıs") || lower.contains("sesi azalt") || lower.contains("ses kıs") -> {
                val ok = service.volumeDown()
                return@withContext AgentExecutionResult(
                    isSuccess = ok,
                    actionType = "VOLUME_DOWN",
                    speechFeedback = if (ok) "Ses kısıldı." else "Ses kısılamadı.",
                    technicalLog = "performNavigation VOLUME_DOWN=$ok"
                )
            }
        }

        return@withContext AgentExecutionResult(
            isSuccess = false,
            actionType = "NOT_A_NAVIGATION_COMMAND",
            speechFeedback = "",
            technicalLog = "performNavigation: no navigation match for '$command'"
        )
    }

    /**
     * AgentBrain tabanlı otonom görev yürütücü.
     * TaskSpec -> Dynamic Plan -> Observe -> Reason -> Safety Gate -> Physical Act -> Verification -> Memory -> RePlan
     */
    suspend fun executeAgentBrainAutonomousLoop(
        context: Context,
        goalPrompt: String,
        brain: AgentBrain,
        maxSteps: Int = 10,
        onStatusUpdate: ((String) -> Unit)? = null
    ): AgentExecutionResult = withContext(Dispatchers.Main) {
        if (!brain.isMultiBrainEnabled()) {
            val message = "Multi-Brain AgentBrain etkin değil; eski tek-AI yürütme yolu kapatıldı."
            AgentLogStore.record(context, "ERROR", TAG, message)
            return@withContext AgentExecutionResult(false, "MULTI_BRAIN_NOT_ENABLED", message, message)
        }
        val service = AiDeviceAccessibilityService.instance
        if (service == null) {
            val errMsg = "Otonom görevi yürütmek için Erişilebilirlik iznine ihtiyacım var."
            return@withContext AgentExecutionResult(
                isSuccess = false,
                actionType = "ACCESSIBILITY_UNAVAILABLE",
                speechFeedback = errMsg,
                technicalLog = "AiDeviceAccessibilityService is null"
            )
        }

        val credentialStore = CredentialStore(context)
        val database = AssistantDatabase.getDatabase(context)
        val profile = withContext(Dispatchers.IO) { database.userProfileDao().getUserProfileOnce() }

        val aiProviderManager = com.example.ai.AIProviderManager(
            com.example.data.repository.MemoryRepository(database.userProfileDao(), database.memoryDao()),
            credentialStore
        )

        val multiBrainActive = brain.isMultiBrainEnabled()
        val architecture = if (multiBrainActive) {
            credentialStore.getMultiBrainArchitecture()
        } else {
            "SINGLE"
        }
        val activeProviderId = if (multiBrainActive) "groq" else (profile?.preferredAiProvider?.lowercase(Locale.ROOT) ?: "gemini")
        val activeProvider = aiProviderManager.getProvider(activeProviderId)
        val apiKey = aiProviderManager.getApiKey(activeProviderId)

        if (multiBrainActive) {
            val missing = aiProviderManager.getMissingMultiBrainProviders(architecture)
            if (missing.isNotEmpty()) {
                val names = missing.joinToString(", ") { aiProviderManager.getProvider(it).displayName }
                val noKeyMsg = "Multi-Brain hazır değil. Eksik API anahtarları: $names. Ayarlar'dan her sağlayıcının anahtarını ayrı kaydedin."
                AgentLifecycleManager.failSession("session_multibrain_nokey", noKeyMsg)
                return@withContext AgentExecutionResult(
                    isSuccess = false,
                    actionType = "MULTI_BRAIN_API_KEYS_MISSING",
                    speechFeedback = noKeyMsg,
                    technicalLog = "Missing Multi-Brain keys: $missing architecture=$architecture"
                )
            }
        }

        if (apiKey.isBlank()) {
            val noKeyMsg = "API Anahtarı bulunamadı. Lütfen Ayarlar'dan ${activeProvider.displayName} API Key tanımlayın."
            AgentLifecycleManager.failSession("session_nokey", noKeyMsg)
            return@withContext AgentExecutionResult(
                isSuccess = false,
                actionType = "MISSING_API_KEY",
                speechFeedback = noKeyMsg,
                technicalLog = "No valid API key in CredentialStore or UserProfile for $activeProviderId"
            )
        }

        val selectedModel = aiProviderManager.getSelectedModel(activeProviderId)

        val budget = TaskBudget(
            maxSteps = maxSteps.coerceIn(1, 16),
            maxRetriesPerStep = 2,
            overallTimeoutMs = 300_000L,
            perStepTimeoutMs = 30_000L,
            maxConsecutiveFailures = 3
        )

        var taskSession = AgentLifecycleManager.startSession(
            taskGoal = goalPrompt,
            budget = budget,
            initialState = AgentState.PLANNING
        )

        val intentType = IntentRouter.classifyIntent(goalPrompt).intent

        onStatusUpdate?.invoke("Ekran inceleniyor ve plan oluşturuluyor...")
        val initialSnapshot = service.extractLiveScreenSnapshot()
        val plan = try {
            brain.initializeTask(
                userPrompt = goalPrompt,
                snapshot = initialSnapshot,
                apiKey = apiKey,
                providerId = activeProviderId,
                intentType = intentType,
                model = selectedModel
            )
        } catch (e: Exception) {
            val message = "Agent planlama başarısız: ${e.localizedMessage ?: "Bilinmeyen hata"}"
            persistLog(context, "ERROR", message)
            AgentLifecycleManager.failSession(taskSession.taskId, message)
            return@withContext AgentExecutionResult(false, "AGENT_PLANNING_ERROR", message, message)
        }

        val planDesc = plan.currentSubGoal?.description ?: "Görev başlatıldı."
        AgentLifecycleManager.transitionState(
            taskSession.taskId,
            AgentState.PLANNING,
            1,
            "Plan: $planDesc"
        )
        onStatusUpdate?.invoke("Plan: $planDesc")

        var currentStep = 1
        var finalSummary = ""
        var isSuccess = false

        while (!taskSession.isFinished && currentStep <= budget.maxSteps) {
            val currentSess = AgentLifecycleManager.currentSession.value
            if (currentSess == null || currentSess.taskId != taskSession.taskId || currentSess.isCancelled) {
                Log.w(TAG, "Otonom görev kullanıcı veya sistem tarafından iptal edildi.")
                taskSession = taskSession.copy(currentState = AgentState.CANCELLED, isCancelled = true)
                break
            }

            if (taskSession.isTimedOut()) {
                val timeoutMsg = "Görev zaman aşımına ulaştı (${budget.overallTimeoutMs / 1000} sn)."
                AgentLifecycleManager.failSession(taskSession.taskId, timeoutMsg)
                finalSummary = timeoutMsg
                break
            }

            // 1. OBSERVE
            taskSession = taskSession.copy(currentState = AgentState.OBSERVING, currentStep = currentStep)
            AgentLifecycleManager.transitionState(taskSession.taskId, AgentState.OBSERVING, currentStep, "Ekran inceleniyor...")

            val beforeSnapshot = service.updateLiveSnapshot()
            val screenFingerprint = com.example.agent.core.ScreenFingerprintGenerator.generateFingerprint(beforeSnapshot).value

            // 2. REASON & PROPOSE
            taskSession = taskSession.copy(currentState = AgentState.PLANNING)
            AgentLifecycleManager.transitionState(
                taskSession.taskId,
                AgentState.PLANNING,
                currentStep,
                "Karar veriliyor..."
            )
            onStatusUpdate?.invoke("Adım $currentStep: Karar veriliyor...")

            val proposal = try {
                brain.proposeNextAction(
                    snapshot = beforeSnapshot,
                    screenFingerprint = screenFingerprint,
                    apiKey = apiKey,
                    providerId = activeProviderId,
                    model = selectedModel
                )
            } catch (e: Exception) {
                val message = "Multi-Brain karar turu başarısız: ${e.localizedMessage ?: "Bilinmeyen hata"}"
                persistLog(context, "ERROR", message)
                onStatusUpdate?.invoke(message)
                brain.workingMemory.recordFailure(currentStep, "MULTI_BRAIN", message)
                if (brain.workingMemory.state.consecutiveFailures >= 2) {
                    AgentLifecycleManager.failSession(taskSession.taskId, message)
                    finalSummary = message
                    break
                }
                currentStep++
                continue
            }

            Log.i(TAG, "Brain Proposal: type=${proposal.actionType}, target=${proposal.target}, reason=${proposal.reason}")
            persistLog(context, "INFO", "Proposal type=${proposal.actionType}; target=${proposal.target}; reason=${proposal.reason}")

            if (proposal.actionType == BrainActionType.COMPLETE) {
                val isVerified = brain.verifyTaskCompletion(beforeSnapshot)
                if (isVerified) {
                    isSuccess = true
                    finalSummary = proposal.reason.ifBlank { "Görev başarıyla tamamlandı." }
                    AgentLifecycleManager.completeSession(taskSession.taskId, finalSummary)
                    break
                } else {
                    Log.w(TAG, "Task COMPLETE teklifi ekran doğrulamasından geçemedi. RePlan yapılıyor.")
                    brain.workingMemory.recordFailure(currentStep, "COMPLETE", "Ekran tamamlanma kriterini doğrulamıyor.")
                    brain.replan(beforeSnapshot, apiKey, activeProviderId, selectedModel)
                    currentStep++
                    continue
                }
            }

            if (proposal.actionType == BrainActionType.REPLAN) {
                val lowerReason = proposal.reason.lowercase(Locale("tr", "TR"))
                val providerFailure = lowerReason.contains("provider") ||
                    lowerReason.contains("api_key") ||
                    lowerReason.contains("multi_brain_error") ||
                    lowerReason.contains("vision") ||
                    lowerReason.contains("screenshot")

                if (providerFailure) {
                    brain.workingMemory.recordFailure(currentStep, "PLANNING", proposal.reason)
                    val failures = brain.workingMemory.state.consecutiveFailures
                    if (failures >= 2) {
                        val terminal = "AI karar zinciri iki kez başarısız oldu: ${proposal.reason}"
                        AgentLifecycleManager.failSession(taskSession.taskId, terminal)
                        finalSummary = terminal
                        break
                    }
                    onStatusUpdate?.invoke("AI sağlayıcısı yanıt vermedi; kontrollü yeniden deneniyor (${failures}/2)...")
                    AgentLifecycleManager.transitionState(taskSession.taskId, AgentState.RECOVERING, currentStep, "Sağlayıcı hatası sonrası kontrollü yeniden deneme")
                    delay(1500L)
                } else {
                    onStatusUpdate?.invoke("Yeniden planlanıyor...")
                    AgentLifecycleManager.transitionState(taskSession.taskId, AgentState.RECOVERING, currentStep, "Yeniden planlanıyor...")
                    try {
                        brain.replan(beforeSnapshot, apiKey, activeProviderId, selectedModel)
                    } catch (e: Exception) {
                        val terminal = "Yeniden planlama başarısız: ${e.localizedMessage ?: "Bilinmeyen hata"}"
                        AgentLifecycleManager.failSession(taskSession.taskId, terminal)
                        finalSummary = terminal
                        break
                    }
                    delay(700L)
                }
                currentStep++
                continue
            }

            if (proposal.actionType == BrainActionType.NO_ACTION) {
                val noActMsg = proposal.reason.ifBlank { "Güvenli eylem bulunamadı." }
                AgentLifecycleManager.failSession(taskSession.taskId, noActMsg)
                finalSummary = noActMsg
                break
            }

            // 3. SAFETY GUARDIAN GATE
            val targetNode = if (proposal.targetIndex != null && proposal.targetIndex in beforeSnapshot.clickableNodes.indices) {
                beforeSnapshot.clickableNodes[proposal.targetIndex]
            } else if (!proposal.target.isNullOrBlank()) {
                beforeSnapshot.clickableNodes.find { node ->
                    val txt = node.text.ifBlank { node.contentDescription }
                    txt.isNotBlank() && txt.contains(proposal.target, ignoreCase = true)
                }
            } else null

            // RE-CHECK: If action requires a target but none found, we MUST REPLAN
            val requiresTarget = proposal.actionType in listOf(BrainActionType.CLICK_NODE, BrainActionType.CLICK_COORD, BrainActionType.TYPE_TEXT)
            if (requiresTarget && targetNode == null && proposal.x == null && proposal.y == null) {
                val failMsg = "Hedef öge bulunamadı: ${proposal.target}. Yeniden planlanıyor..."
                Log.w(TAG, failMsg)
                onStatusUpdate?.invoke(failMsg)
                brain.workingMemory.recordFailure(currentStep, proposal.actionType, "Hedef öge ekranda yok.")
                brain.replan(beforeSnapshot, apiKey, activeProviderId, selectedModel)
                currentStep++
                continue
            }

            val safetyDecision = brain.validateActionSafety(
                proposal = proposal,
                snapshot = beforeSnapshot,
                node = targetNode
            )

            if (!safetyDecision.allowed) {
                val blockedMsg = "Güvenlik Engeli: ${safetyDecision.reason}"
                Log.w(TAG, "SafetyGuardian eylemi engelledi: $blockedMsg")
                persistLog(context, "WARN", blockedMsg)
                AgentLifecycleManager.transitionState(taskSession.taskId, AgentState.RECOVERING, currentStep, blockedMsg)
                onStatusUpdate?.invoke(blockedMsg)

                brain.workingMemory.recordFailure(currentStep, proposal.actionType, "ENGELENDİ: ${safetyDecision.reason}")
                brain.replan(beforeSnapshot, apiKey, activeProviderId, selectedModel)
                currentStep++
                continue
            }

            // 4. PHYSICAL EXECUTION
            val prePhysicalSession = AgentLifecycleManager.currentSession.value
            if (prePhysicalSession == null || prePhysicalSession.taskId != taskSession.taskId || prePhysicalSession.isCancelled) {
                Log.w(TAG, "Fiziksel eylem öncesi görev iptal edildi. Aksiyon durduruldu.")
                taskSession = taskSession.copy(currentState = AgentState.CANCELLED, isCancelled = true)
                break
            }

            taskSession = taskSession.copy(currentState = AgentState.ACTING)
            val actDesc = proposal.reason.ifBlank { "Eylem uygulanıyor: ${proposal.actionType}" }
            AgentLifecycleManager.transitionState(taskSession.taskId, AgentState.ACTING, currentStep, actDesc)
            onStatusUpdate?.invoke("Adım $currentStep: $actDesc")

            executePhysicalActionProposal(service, context, proposal, targetNode, beforeSnapshot)
            service.awaitScreenSettled(800L, 200L)

            // 5. VERIFY OUTCOME & RECORD MEMORY
            taskSession = taskSession.copy(currentState = AgentState.VERIFYING)
            AgentLifecycleManager.transitionState(taskSession.taskId, AgentState.VERIFYING, currentStep, "Doğrulanıyor...")

            val afterSnapshot = service.updateLiveSnapshot()
            val verification = brain.verifyAndRecordResult(proposal, beforeSnapshot, afterSnapshot)

            Log.i(TAG, "Verification: isVerified=${verification.isVerified}, reason=${verification.reason}")
            persistLog(context, if (verification.isVerified) "INFO" else "WARN", "Verification=${verification.isVerified}; ${verification.reason}")

            if (!verification.isVerified && brain.workingMemory.state.consecutiveFailures >= 3) {
                Log.w(TAG, "3 üst üste başarısız eylem. REPLAN tetikleniyor.")
                brain.replan(afterSnapshot, apiKey, activeProviderId, selectedModel)
            }

            // Pace the loop so one task cannot fire dozens of actions back-to-back while
            // Android is still animating or Accessibility events are still settling.
            delay(650L)
            currentStep++
        }

        val finalIsSuccess = isSuccess || (taskSession.currentState == AgentState.COMPLETED)
        if (finalIsSuccess) {
            AgentExecutionResult(
                isSuccess = true,
                actionType = "AGENT_BRAIN_SUCCESS",
                speechFeedback = finalSummary.ifBlank { "Görev başarıyla tamamlandı." },
                technicalLog = "AgentBrain completed task successfully in ${currentStep - 1} steps."
            )
        } else {
            if (taskSession.currentState != AgentState.FAILED && taskSession.currentState != AgentState.CANCELLED) {
                AgentLifecycleManager.failSession(taskSession.taskId, finalSummary.ifBlank { "Görev tamamlanamadı." })
            }
            AgentExecutionResult(
                isSuccess = false,
                actionType = "AGENT_BRAIN_FAILED",
                speechFeedback = finalSummary.ifBlank { "Görev AgentBrain tarafından tamamlanamadı." },
                technicalLog = "AgentBrain loop finished without completion verification."
            )
        }
    }

    private suspend fun executePhysicalActionProposal(
        service: AiDeviceAccessibilityService,
        context: Context,
        proposal: ActionProposal,
        targetNode: ScreenNodeData?,
        snapshot: ScreenSnapshot
    ) {
        when (proposal.actionType) {
            BrainActionType.CLICK_NODE -> {
                if (targetNode != null) {
                    val cx = targetNode.bounds.centerX().toFloat()
                    val cy = targetNode.bounds.centerY().toFloat()
                    service.clickAtWithVerificationResult(cx, cy, label = proposal.target ?: "düğme", targetNode = targetNode)
                } else if (!proposal.target.isNullOrBlank()) {
                    val matched = snapshot.clickableNodes.find {
                        val label = it.text.ifBlank { it.contentDescription }
                        label.isNotBlank() && label.contains(proposal.target, ignoreCase = true)
                    }
                    if (matched != null) {
                        service.clickAtWithVerificationResult(
                            matched.bounds.centerX().toFloat(),
                            matched.bounds.centerY().toFloat(),
                            label = proposal.target,
                            targetNode = matched
                        )
                    }
                }
            }
            BrainActionType.CLICK_COORD -> {
                val x = proposal.x
                val y = proposal.y
                if (x != null && y != null) {
                    service.clickAtWithVerificationResult(
                        x = x.toFloat(),
                        y = y.toFloat(),
                        label = proposal.target ?: "Vision adayı",
                        targetNode = targetNode
                    )
                }
            }
            BrainActionType.TYPE_TEXT -> {
                if (!proposal.textPayload.isNullOrBlank()) {
                    service.typeTextIntoNode(proposal.textPayload, proposal.target.orEmpty())
                }
            }
            BrainActionType.PRESS_BACK -> {
                service.goBack()
            }
            BrainActionType.PRESS_HOME -> {
                service.goHome()
            }
            BrainActionType.SWIPE_DOWN -> {
                service.swipeDownAsync()
            }
            BrainActionType.SWIPE_UP -> {
                service.swipeUpAsync()
            }
            BrainActionType.SWIPE_LEFT -> {
                service.swipeLeftAsync()
            }
            BrainActionType.SWIPE_RIGHT -> {
                service.swipeRightAsync()
            }
            BrainActionType.OPEN_APP -> {
                // Multi-Brain physical execution must stay grounded in the current
                // Accessibility tree. Never fall back to the legacy Gemini visual
                // app-opening path from an ActionProposal.
                if (targetNode != null) {
                    service.clickAtWithVerificationResult(
                        targetNode.bounds.centerX().toFloat(),
                        targetNode.bounds.centerY().toFloat(),
                        label = proposal.target ?: "uygulama",
                        targetNode = targetNode
                    )
                } else if (proposal.x != null && proposal.y != null) {
                    service.clickAtWithVerificationResult(
                        proposal.x.toFloat(),
                        proposal.y.toFloat(),
                        label = proposal.target ?: "Vision uygulama adayı",
                        targetNode = null
                    )
                } else {
                    Log.w("DeviceAgentExecutor", "Rejected ungrounded OPEN_APP proposal: ${proposal.target}")
                    persistLog(context, "WARN", "OPEN_APP without grounded target rejected: ${proposal.target}")
                }
            }
            else -> {
                service.awaitScreenSettled(500L)
            }
        }
    }

    /**
     * Executes multi-step intelligent user commands with visual grounding and human gestures.
     */
    suspend fun executeSmartAutonomousTask(
        context: Context,
        command: String,
        brain: AgentBrain,
        onStatusUpdate: ((String) -> Unit)? = null
    ): AgentExecutionResult = withContext(Dispatchers.Main) {
        val lower = command.lowercase(Locale("tr", "TR")).trim()

        // 0. Screen reading command ("ekranı oku", "ekranda ne var", "ekranı incele")
        if (lower.contains("ekranı oku") || lower.contains("ekranda ne var") || lower.contains("ekranı incele") || lower.contains("ekrana bak") || lower.contains("ekranı tara")) {
            val shortSession = AgentLifecycleManager.startSession(
                taskGoal = command,
                budget = TaskBudget(maxSteps = 1, overallTimeoutMs = 10_000L),
                initialState = AgentState.OBSERVING
            )
            AgentLifecycleManager.transitionState(shortSession.taskId, AgentState.OBSERVING, 1, "Ekran metinleri okunuyor...")
            val service = AiDeviceAccessibilityService.instance
            if (service == null) {
                val errMsg = "Ekranı okuyabilmek için Erişilebilirlik iznine ihtiyacım var."
                AgentLifecycleManager.failSession(shortSession.taskId, errMsg)
                return@withContext AgentExecutionResult(
                    isSuccess = false,
                    actionType = "SCREEN_READ_NO_SERVICE",
                    speechFeedback = errMsg,
                    technicalLog = "Accessibility service is null"
                )
            }
            val snapshot = service.extractLiveScreenSnapshot()
            val minified = snapshot.toUltraMinifiedString(10)
            val visibleTexts = snapshot.texts.take(5).joinToString(", ")
            val appName = snapshot.packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
            val feedback = if (visibleTexts.isNotBlank()) {
                "Şu an $appName ekranındasınız. Görünür metinler: $visibleTexts."
            } else {
                "Şu an $appName ekranındasınız. Tıklanabilir ${snapshot.clickableNodes.size} öğe mevcut."
            }
            AgentLifecycleManager.completeSession(shortSession.taskId, feedback)
            return@withContext AgentExecutionResult(
                isSuccess = true,
                actionType = "READ_SCREEN_SUCCESS",
                speechFeedback = feedback,
                technicalLog = "Screen minified dump: $minified"
            )
        }

        // 1. Gesture / Navigation commands (Swipe, Home, Back, System & Audio)
        val navResult = performNavigation(lower)
        if (navResult.isSuccess) {
            val navSession = AgentLifecycleManager.startSession(
                taskGoal = command,
                budget = TaskBudget(maxSteps = 1, overallTimeoutMs = 10_000L),
                initialState = AgentState.ACTING
            )
            AgentLifecycleManager.transitionState(navSession.taskId, AgentState.ACTING, 1, navResult.speechFeedback)
            AgentLifecycleManager.completeSession(navSession.taskId, navResult.speechFeedback)
            return@withContext navResult
        }

        // 2. PRIMARY: Execute via AgentBrain Orchestrator
        persistLog(context, "INFO", "Autonomous task started: $command")
        val brainResult = executeAgentBrainAutonomousLoop(
            context = context,
            goalPrompt = command,
            brain = brain,
            maxSteps = 10,
            onStatusUpdate = onStatusUpdate
        )

        if (brainResult.isSuccess) {
            return@withContext brainResult
        }

        persistLog(context, if (brainResult.isSuccess) "INFO" else "ERROR", "Autonomous task finished: success=${brainResult.isSuccess}; ${brainResult.speechFeedback}")
        return@withContext brainResult
    }

    /**
     * Inspects the current device and saves real hardware, battery, and installed apps into memory.
     */
    suspend fun inspectDeviceAndLearn(context: Context): List<MemoryEntryEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MemoryEntryEntity>()
        val pm = context.packageManager

        // 1. Device Info
        list.add(
            MemoryEntryEntity(
                category = MemoryCategory.SYSTEM.name,
                key = "Cihaz Modeli",
                value = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
                importance = 2,
                timestamp = System.currentTimeMillis()
            )
        )

        // 2. Battery Status
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (batteryLevel > 0) {
                list.add(
                    MemoryEntryEntity(
                        category = MemoryCategory.SYSTEM.name,
                        key = "Pil Seviyesi",
                        value = "%$batteryLevel",
                        importance = 1,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore
        }

        // 3. Installed Apps Scan
        try {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { pm.getApplicationLabel(it).toString() }
                .take(15)

            if (installedApps.isNotEmpty()) {
                list.add(
                    MemoryEntryEntity(
                        category = MemoryCategory.SYSTEM.name,
                        key = "Yüklü Uygulamalar",
                        value = installedApps.joinToString(", "),
                        importance = 2,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore
        }

        // Save to Database
        val db = AssistantDatabase.getDatabase(context)
        for (item in list) {
            db.memoryDao().insertMemory(item)
        }

        return@withContext list
    }
}
