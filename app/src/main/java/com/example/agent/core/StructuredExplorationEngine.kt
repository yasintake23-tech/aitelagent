package com.example.agent.core

import android.content.Context
import android.graphics.PointF
import android.util.Log
import com.example.agent.brain.AgentBrain
import com.example.agent.brain.AgentActionType as BrainActionType
import com.example.ai.AIAgentScreenReasoner
import com.example.ai.AgentActionType
import com.example.data.local.AssistantDatabase
import com.example.data.local.MemoryFileManager
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.UserProfileEntity
import com.example.data.security.CredentialStore
import com.example.service.AiDeviceAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Yapısal ve hedef odaklı Otonom Keşif Motoru.
 * Rastgele hareketleri ve kör döngüleri engeller.
 * Ekran parmak izi (ScreenFingerprint), ActionVerifier, RecoveryStrategy ve bütçe modellerini
 * entegre ederek cihazı ve uygulamaları anlamlı şekilde keşfeder.
 */
object StructuredExplorationEngine {
    private const val TAG = "StructuredExploration"

    /**
     * Süre kısıtlı ve hedef odaklı yapısal keşif oturumunu çalıştırır.
     */
    suspend fun executeExploration(
        context: Context,
        service: AiDeviceAccessibilityService,
        durationMinutes: Int,
        taskPrompt: String = "Cihazı ve uygulamaları keşfet",
        reasoner: AIAgentScreenReasoner? = null,
        profile: UserProfileEntity? = null,
        onCountdownTick: ((remainingSeconds: Int) -> Unit)? = null,
        onStatusUpdate: (String) -> Unit,
        onFinished: (learnedCount: Int) -> Unit
    ) = coroutineScope {
        val totalSeconds = if (durationMinutes <= 0) 120 else durationMinutes * 60
        val timeoutMs = totalSeconds * 1000L
        val maxSteps = (totalSeconds / 10).coerceIn(15, 120) // Her adım ortalama ~10-15 sn

        val taskBudget = TaskBudget(
            maxSteps = maxSteps,
            maxRetriesPerStep = 3,
            overallTimeoutMs = timeoutMs,
            perStepTimeoutMs = 25_000L,
            maxConsecutiveFailures = 3
        )

        val session = ExplorationTaskSession(
            explorationObjective = taskPrompt,
            startTimeMs = System.currentTimeMillis(),
            deadlineMs = System.currentTimeMillis() + timeoutMs,
            budget = taskBudget,
            currentState = AgentState.PLANNING
        )

        // Register central session in AgentLifecycleManager
        val centralSession = AgentLifecycleManager.startSession(
            taskGoal = taskPrompt,
            budget = taskBudget,
            initialState = AgentState.PLANNING
        )

        val database = AssistantDatabase.getDatabase(context)
        val displayMetrics = context.resources.displayMetrics
        val credentialStore = CredentialStore(context)
        val dbProfile = withContext(Dispatchers.IO) { database.userProfileDao().getUserProfileOnce() }

        val groqKey = credentialStore.getApiKey("groq").ifBlank {
            if (dbProfile?.preferredAiProvider?.lowercase(Locale.ROOT) == "groq") dbProfile?.customApiKey ?: "" else ""
        }
        val selectedModel = credentialStore.getSelectedModel("groq", "openai/gpt-oss-120b")

        val brain = AgentBrain()
        val initialSnapshot = service.extractLiveScreenSnapshot()
        brain.initializeTask(
            userPrompt = taskPrompt,
            snapshot = initialSnapshot,
            apiKey = groqKey,
            intentType = UserIntent.EXPLORATION_TASK,
            model = selectedModel
        )

        // 1. Ekran donanım bilgisini ilk hafıza kaydı olarak kaydet
        withContext(Dispatchers.IO) {
            database.memoryDao().insertMemory(
                MemoryEntryEntity(
                    category = MemoryCategory.SYSTEM.name,
                    key = "Ekran Yapısı ve Çözünürlük",
                    value = "${displayMetrics.widthPixels}x${displayMetrics.heightPixels} @ ${displayMetrics.densityDpi} DPI",
                    importance = 2,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
        session.onMemoryLearned()

        try {
            onStatusUpdate("Otonom keşif motoru başlatıldı. Ana ekrandan başlanıyor...")
            service.goHome()
            service.awaitScreenSettled(1500L)

            while (isActive && !session.isFinished) {
                // Check if central session was cancelled externally
                val loopStartSession = AgentLifecycleManager.currentSession.value
                if (loopStartSession == null || loopStartSession.taskId != centralSession.taskId || loopStartSession.isCancelled) {
                    session.cancel("Oturum merkezi yönetici tarafından iptal edildi.")
                    break
                }

                session.stepCount++
                val now = System.currentTimeMillis()

                // Geriye kalan süreyi güncelle
                val remainingSeconds = ((session.deadlineMs - now) / 1000).toInt().coerceAtLeast(0)
                onCountdownTick?.invoke(remainingSeconds)

                // 1. OBSERVE (Gözlem)
                session.currentState = AgentState.OBSERVING
                AgentLifecycleManager.transitionState(centralSession.taskId, AgentState.OBSERVING, session.stepCount, "Ekran inceleniyor...")
                val snapshot = service.updateLiveSnapshot()
                val currentFingerprint = ScreenFingerprintGenerator.generateFingerprint(snapshot)
                val isNewScreen = session.onScreenObserved(currentFingerprint, snapshot.packageName)

                // 2. FINGERPRINT & STUCK CHECK
                val previousFingerprint = session.currentScreenFingerprint
                if (previousFingerprint != null && previousFingerprint.value == currentFingerprint.value) {
                    session.consecutiveSameStateCount++
                    Log.d(TAG, "Aynı ekranda bulunuluyor (${session.consecutiveSameStateCount}. adım). Fingerprint: ${currentFingerprint.value.take(12)}")
                } else {
                    session.consecutiveSameStateCount = 0
                    if (isNewScreen) {
                        Log.i(TAG, "Yeni ekran keşfedildi! Paket: ${snapshot.packageName}, Fingerprint: ${currentFingerprint.value.take(12)}")
                    }
                }
                session.currentScreenFingerprint = currentFingerprint

                // 3. DECIDE NEXT MEANINGFUL ACTION WITH AGENT BRAIN
                session.currentState = AgentState.PLANNING
                AgentLifecycleManager.transitionState(centralSession.taskId, AgentState.PLANNING, session.stepCount, "Planlanıyor...")

                val proposal = brain.proposeNextAction(
                    snapshot = snapshot,
                    screenFingerprint = currentFingerprint.value,
                    apiKey = groqKey,
                    model = selectedModel
                )

                onStatusUpdate(proposal.reason.ifBlank { "Ekran inceleniyor..." })
                Log.d(TAG, "Adım ${session.stepCount} Kararı: ${proposal.actionType}, Açıklama: ${proposal.reason}")

                // Görev tamamlama kararı geldiyse
                if (proposal.actionType == BrainActionType.COMPLETE || proposal.actionType == BrainActionType.NO_ACTION) {
                    session.currentState = AgentState.COMPLETED
                    AgentLifecycleManager.completeSession(centralSession.taskId, proposal.reason.ifBlank { "Keşif başarıyla tamamlandı." })
                    onStatusUpdate(proposal.reason.ifBlank { "Keşif başarıyla tamamlandı." })
                    break
                }

                if (proposal.actionType == BrainActionType.REPLAN) {
                    onStatusUpdate("Yeniden planlanıyor...")
                    AgentLifecycleManager.transitionState(centralSession.taskId, AgentState.RECOVERING, session.stepCount, "Yeniden planlanıyor...")
                    brain.replan(snapshot, groqKey, selectedModel)
                    continue
                }

                // 4. SAFETY GUARDIAN GATE
                val targetNode = if (proposal.targetIndex != null && proposal.targetIndex in snapshot.clickableNodes.indices) {
                    snapshot.clickableNodes[proposal.targetIndex]
                } else {
                    snapshot.clickableNodes.firstOrNull { node ->
                        val txt = node.text.ifBlank { node.contentDescription }
                        proposal.target != null && txt.contains(proposal.target, ignoreCase = true)
                    }
                }

                val safetyDecision = brain.validateActionSafety(
                    proposal = proposal,
                    snapshot = snapshot,
                    node = targetNode
                )

                if (!safetyDecision.allowed) {
                    val blockedMsg = "Güvenlik Engeli: ${safetyDecision.reason}"
                    Log.w(TAG, "SafetyGuardian eylemi engelledi: $blockedMsg")
                    AgentLifecycleManager.transitionState(centralSession.taskId, AgentState.RECOVERING, session.stepCount, blockedMsg)
                    onStatusUpdate(blockedMsg)

                    brain.workingMemory.recordFailure(session.stepCount, proposal.actionType, "ENGELENDİ: ${safetyDecision.reason}")
                    brain.replan(snapshot, groqKey, selectedModel)
                    continue
                }

                // 5. ACT (Eylemi Güvenle Yürüt)
                session.currentState = AgentState.ACTING
                val actingStatus = proposal.reason.ifBlank { "Eylem gerçekleştiriliyor..." }
                val transitionOk = AgentLifecycleManager.transitionState(centralSession.taskId, AgentState.ACTING, session.stepCount, actingStatus)
                val preActSession = AgentLifecycleManager.currentSession.value

                if (!transitionOk || preActSession == null || preActSession.taskId != centralSession.taskId || preActSession.isCancelled || preActSession.isFinished) {
                    Log.w(TAG, "Keşif görevi eylem öncesi iptal edildi veya sonlandırıldı. Aksiyon durduruluyor.")
                    session.currentState = AgentState.CANCELLED
                    break
                }

                val coords = if (proposal.targetIndex != null && proposal.targetIndex in snapshot.clickableNodes.indices) {
                    val n = snapshot.clickableNodes[proposal.targetIndex]
                    PointF(n.bounds.centerX().toFloat(), n.bounds.centerY().toFloat())
                } else if (proposal.target != null) {
                    val n = snapshot.clickableNodes.firstOrNull {
                        it.text.contains(proposal.target, ignoreCase = true) || it.contentDescription.contains(proposal.target, ignoreCase = true)
                    }
                    n?.let { PointF(it.bounds.centerX().toFloat(), it.bounds.centerY().toFloat()) }
                } else null

                when (proposal.actionType) {
                    BrainActionType.CLICK_COORD, BrainActionType.CLICK_NODE -> {
                        val finalCoords = coords ?: targetNode?.let {
                            PointF(it.bounds.centerX().toFloat(), it.bounds.centerY().toFloat())
                        }
                        if (finalCoords != null) {
                            service.clickAtWithVerification(finalCoords.x, finalCoords.y, proposal.target ?: "düğme", targetNode = targetNode)
                        } else {
                            service.awaitScreenSettled(800L)
                        }
                    }
                    BrainActionType.TYPE_TEXT -> {
                        if (!proposal.textPayload.isNullOrBlank()) {
                            service.typeTextIntoNode(proposal.textPayload)
                        }
                    }
                    BrainActionType.SWIPE, BrainActionType.SWIPE_DOWN -> {
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
                    BrainActionType.PRESS_BACK -> {
                        service.goBack()
                    }
                    BrainActionType.PRESS_HOME -> {
                        service.goHome()
                    }
                    BrainActionType.OPEN_APP -> {
                        val appName = proposal.target ?: proposal.textPayload ?: ""
                        if (appName.isNotBlank()) {
                            service.findAndOpenAppVisually(appName, profile?.customApiKey ?: "", 4) { msg ->
                                onStatusUpdate(msg)
                            }
                        }
                    }
                    BrainActionType.OPEN_QUICK_SETTINGS -> {
                        service.openQuickSettings()
                    }
                    BrainActionType.OPEN_NOTIFICATIONS -> {
                        service.openNotifications()
                    }
                    BrainActionType.VOLUME_UP -> {
                        service.volumeUp()
                    }
                    BrainActionType.VOLUME_DOWN -> {
                        service.volumeDown()
                    }
                    else -> {
                        service.awaitScreenSettled(1000L)
                    }
                }

                // 6. VERIFY & COMPARE (Eylem Sonrası Doğrulama)
                session.currentState = AgentState.VERIFYING
                AgentLifecycleManager.transitionState(centralSession.taskId, AgentState.VERIFYING, session.stepCount, "Sonuç kontrol ediliyor...")
                service.awaitScreenSettled(1200L, 200L)

                val afterSnapshot = service.updateLiveSnapshot()
                val afterFingerprint = ScreenFingerprintGenerator.generateFingerprint(afterSnapshot)
                val diffType = ScreenFingerprintGenerator.compareFingerprints(currentFingerprint, afterFingerprint)

                val verification = brain.verifyAndRecordResult(proposal, snapshot, afterSnapshot)
                val isActionResultEffective = verification.isVerified

                if (isActionResultEffective) {
                    session.consecutiveFailures = 0
                } else {
                    session.consecutiveFailures++
                }

                // 7. RECORD ACTION OUTCOME & PROGRESS
                session.recordActionOutcome(
                    stepNumber = session.stepCount,
                    fromFingerprint = currentFingerprint.value,
                    actionType = when (proposal.actionType) {
                        BrainActionType.CLICK_COORD -> AgentActionType.CLICK_COORD
                        BrainActionType.CLICK_NODE -> AgentActionType.CLICK_NODE
                        BrainActionType.SWIPE_DOWN -> AgentActionType.SWIPE_DOWN
                        BrainActionType.SWIPE_UP -> AgentActionType.SWIPE_UP
                        BrainActionType.SWIPE_LEFT -> AgentActionType.SWIPE_LEFT
                        BrainActionType.SWIPE_RIGHT -> AgentActionType.SWIPE_RIGHT
                        BrainActionType.PRESS_BACK -> AgentActionType.PRESS_BACK
                        BrainActionType.PRESS_HOME -> AgentActionType.PRESS_HOME
                        BrainActionType.OPEN_APP -> AgentActionType.OPEN_APP
                        BrainActionType.OPEN_QUICK_SETTINGS -> AgentActionType.OPEN_QUICK_SETTINGS
                        BrainActionType.OPEN_NOTIFICATIONS -> AgentActionType.OPEN_NOTIFICATIONS
                        BrainActionType.VOLUME_UP -> AgentActionType.VOLUME_UP
                        BrainActionType.VOLUME_DOWN -> AgentActionType.VOLUME_DOWN
                        else -> AgentActionType.IDLE
                    },
                    targetDescription = proposal.target?.ifBlank { proposal.textPayload?.ifBlank { proposal.actionType } } ?: proposal.actionType,
                    thought = proposal.reason,
                    toFingerprint = afterFingerprint.value,
                    diffType = diffType,
                    isSuccess = isActionResultEffective
                )

                // 8. MEMORY LEARNING (Yeni keşfedilen bilgi varsa kaydet)
                if (!proposal.memoryKey.isNullOrBlank() && !proposal.memoryValue.isNullOrBlank()) {
                    withContext(Dispatchers.IO) {
                        database.memoryDao().insertMemory(
                            MemoryEntryEntity(
                                category = MemoryCategory.PREFERENCE.name,
                                key = proposal.memoryKey,
                                value = proposal.memoryValue,
                                importance = 1,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                        val updatedMemories = database.memoryDao().getAllMemoriesOnce()
                        MemoryFileManager.exportMemoryToDownloads(context, profile, updatedMemories)
                    }
                    session.onMemoryLearned()
                }

                // Küçük bekleme ve durum güncellemesi
                delay(600)
            }

            // Keşif tamamlandı
            service.goHome()
            if (session.currentState != AgentState.CANCELLED && session.currentState != AgentState.FAILED) {
                session.currentState = AgentState.COMPLETED
            }

            withContext(Dispatchers.IO) {
                val allMemories = database.memoryDao().getAllMemoriesOnce()
                MemoryFileManager.exportMemoryToDownloads(context, profile, allMemories)
            }

            val summaryMsg = "Keşif oturumu tamamlandı! ${session.progress.discoveredScreensCount} farklı ekran incelendi, ${session.progress.memoriesLearnedCount} bilgi kaydedildi."
            if (session.currentState == AgentState.COMPLETED) {
                AgentLifecycleManager.completeSession(centralSession.taskId, summaryMsg)
            }
            onStatusUpdate(summaryMsg)
            onFinished(session.progress.memoriesLearnedCount)

        } catch (e: Exception) {
            Log.e(TAG, "Exploration execution error", e)
            session.currentState = AgentState.FAILED
            AgentLifecycleManager.failSession(centralSession.taskId, e.localizedMessage ?: "Bilinmeyen hata")
            onStatusUpdate("Keşif duraklatıldı: ${e.localizedMessage}")
            onFinished(session.progress.memoriesLearnedCount)
        }
    }
}
