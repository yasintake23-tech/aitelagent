package com.example.agent.core

import android.content.Context
import android.graphics.PointF
import android.util.Log
import com.example.ai.AIAgentScreenReasoner
import com.example.ai.AgentActionType
import com.example.data.local.AssistantDatabase
import com.example.data.local.MemoryFileManager
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.UserProfileEntity
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

                // 3. DECIDE NEXT MEANINGFUL ACTION (Karar Alma - Rastgele hareket YOK)
                session.currentState = AgentState.PLANNING
                AgentLifecycleManager.transitionState(centralSession.taskId, AgentState.PLANNING, session.stepCount, "Planlanıyor...")
                val existingMemories = withContext(Dispatchers.IO) {
                    database.memoryDao().getAllMemoriesOnce()
                }

                val decision = ExplorationDecisionMaker.decideNextExplorationAction(
                    snapshot = snapshot,
                    session = session,
                    reasoner = reasoner,
                    memories = existingMemories,
                    profile = profile
                )

                onStatusUpdate(decision.speechStatus.ifBlank { "Ekran inceleniyor..." })
                Log.d(TAG, "Adım ${session.stepCount} Kararı: ${decision.actionType}, Açıklama: ${decision.thought}")

                // Görev tamamlama kararı geldiyse
                if (decision.actionType == AgentActionType.TASK_COMPLETE) {
                    session.currentState = AgentState.COMPLETED
                    AgentLifecycleManager.completeSession(centralSession.taskId, decision.completionSummary.ifBlank { "Keşif başarıyla tamamlandı." })
                    onStatusUpdate(decision.completionSummary.ifBlank { "Keşif başarıyla tamamlandı." })
                    break
                }

                // 4. ACT (Eylemi Güvenle Yürüt)
                session.currentState = AgentState.ACTING
                val actingStatus = decision.thought.ifBlank { "Eylem gerçekleştiriliyor..." }
                val transitionOk = AgentLifecycleManager.transitionState(centralSession.taskId, AgentState.ACTING, session.stepCount, actingStatus)
                val preActSession = AgentLifecycleManager.currentSession.value

                if (!transitionOk || preActSession == null || preActSession.taskId != centralSession.taskId || preActSession.isCancelled || preActSession.isFinished) {
                    Log.w(TAG, "Keşif görevi eylem öncesi iptal edildi veya sonlandırıldı. Aksiyon durduruluyor.")
                    session.currentState = AgentState.CANCELLED
                    break
                }

                val targetNode = if (decision.targetIndex in snapshot.clickableNodes.indices) {
                    snapshot.clickableNodes[decision.targetIndex]
                } else null

                when (decision.actionType) {
                    AgentActionType.CLICK_COORD, AgentActionType.CLICK_NODE -> {
                        val coords = decision.coordinates ?: targetNode?.let {
                            PointF(it.bounds.centerX().toFloat(), it.bounds.centerY().toFloat())
                        }
                        if (coords != null) {
                            service.clickAtWithVerification(coords.x, coords.y, decision.targetText, targetNode = targetNode)
                        } else {
                            service.awaitScreenSettled(800L)
                        }
                    }
                    AgentActionType.SWIPE_DOWN -> {
                        service.swipeDownAsync()
                    }
                    AgentActionType.SWIPE_UP -> {
                        service.swipeUpAsync()
                    }
                    AgentActionType.SWIPE_LEFT -> {
                        service.swipeLeftAsync()
                    }
                    AgentActionType.SWIPE_RIGHT -> {
                        service.swipeRightAsync()
                    }
                    AgentActionType.PRESS_BACK -> {
                        service.goBack()
                    }
                    AgentActionType.PRESS_HOME -> {
                        service.goHome()
                    }
                    AgentActionType.OPEN_APP -> {
                        if (decision.appName.isNotBlank()) {
                            service.findAndOpenAppVisually(decision.appName, profile?.customApiKey ?: "", 4) { msg ->
                                onStatusUpdate(msg)
                            }
                        }
                    }
                    AgentActionType.OPEN_QUICK_SETTINGS -> {
                        service.openQuickSettings()
                    }
                    AgentActionType.OPEN_NOTIFICATIONS -> {
                        service.openNotifications()
                    }
                    AgentActionType.VOLUME_UP -> {
                        service.volumeUp()
                    }
                    AgentActionType.VOLUME_DOWN -> {
                        service.volumeDown()
                    }
                    else -> {
                        service.awaitScreenSettled(1000L)
                    }
                }

                // 5. VERIFY & COMPARE (Eylem Sonrası Doğrulama)
                session.currentState = AgentState.VERIFYING
                AgentLifecycleManager.transitionState(centralSession.taskId, AgentState.VERIFYING, session.stepCount, "Sonuç kontrol ediliyor...")
                service.awaitScreenSettled(1200L, 200L)

                val afterSnapshot = service.updateLiveSnapshot()
                val afterFingerprint = ScreenFingerprintGenerator.generateFingerprint(afterSnapshot)
                val diffType = ScreenFingerprintGenerator.compareFingerprints(currentFingerprint, afterFingerprint)

                val isActionResultEffective = (diffType != ScreenDifferenceType.SAME_STATE) ||
                        (decision.actionType == AgentActionType.SWIPE_DOWN) ||
                        (decision.actionType == AgentActionType.PRESS_BACK)

                if (isActionResultEffective) {
                    session.consecutiveFailures = 0
                } else {
                    session.consecutiveFailures++
                }

                // 6. RECORD ACTION OUTCOME & PROGRESS
                session.recordActionOutcome(
                    stepNumber = session.stepCount,
                    fromFingerprint = currentFingerprint.value,
                    actionType = decision.actionType,
                    targetDescription = decision.targetText.ifBlank { decision.appName.ifBlank { decision.actionType.name } },
                    thought = decision.thought,
                    toFingerprint = afterFingerprint.value,
                    diffType = diffType,
                    isSuccess = isActionResultEffective
                )

                // 7. MEMORY LEARNING (Yeni keşfedilen bilgi varsa kaydet)
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
