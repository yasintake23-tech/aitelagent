package com.example.agent.core

import com.example.ai.AgentActionType
import java.util.UUID

/**
 * Keşif ilerlemesini ve istatistiklerini temsil eden veri modeli.
 */
data class ExplorationProgress(
    val discoveredScreensCount: Int = 0,
    val discoveredPackagesCount: Int = 0,
    val successfulActionsCount: Int = 0,
    val memoriesLearnedCount: Int = 0
)

/**
 * Keşif sırasında atılan her bir adımın geçmiş kaydı.
 */
data class ExplorationStepRecord(
    val stepNumber: Int,
    val fromFingerprint: String,
    val actionType: AgentActionType,
    val targetDescription: String,
    val thought: String = "",
    val toFingerprint: String? = null,
    val differenceType: ScreenDifferenceType? = null,
    val isSuccess: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * "Cihazı Keşfet" / Uzun süreli otonom keşif görevlerini yöneten merkezi oturum modeli.
 * Ekran parmak izleri, ziyaret geçmişi, stuck takibi, bütçe ve ilerlemeyi deterministik olarak tutar.
 */
data class ExplorationTaskSession(
    val taskId: String = UUID.randomUUID().toString(),
    val explorationObjective: String = "Yeni ve anlamlı cihaz/uygulama ekranlarını kontrollü şekilde keşfetmek.",
    val startTimeMs: Long = System.currentTimeMillis(),
    val deadlineMs: Long = System.currentTimeMillis() + 15 * 60 * 1000L,
    val budget: TaskBudget = TaskBudget(
        maxSteps = 60,
        maxRetriesPerStep = 3,
        overallTimeoutMs = 15 * 60 * 1000L,
        perStepTimeoutMs = 25_000L,
        maxConsecutiveFailures = 3
    ),
    var currentState: AgentState = AgentState.IDLE,
    var currentScreenFingerprint: ScreenFingerprint? = null,
    val visitedScreens: MutableSet<String> = mutableSetOf(),
    val visitedPackages: MutableSet<String> = mutableSetOf(),
    val visitedNodeSignatures: MutableSet<String> = mutableSetOf(),
    val actionHistory: MutableList<ExplorationStepRecord> = mutableListOf(),
    var stepCount: Int = 0,
    var consecutiveSameStateCount: Int = 0,
    var consecutiveFailures: Int = 0,
    var isCancelled: Boolean = false,
    var cancellationReason: String? = null,
    var progress: ExplorationProgress = ExplorationProgress()
) {
    /**
     * Toplam süre veya bütçe zaman aşımını kontrol eder.
     */
    fun isTimedOut(currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        return currentTimeMs >= deadlineMs || (currentTimeMs - startTimeMs) > budget.overallTimeoutMs
    }

    /**
     * Maksimum adım limitinin aşılıp aşılmadığını kontrol eder.
     */
    fun isStepLimitReached(): Boolean {
        return stepCount >= budget.maxSteps
    }

    /**
     * Oturumun sonlanıp sonlanmadığını kontrol eder.
     */
    val isFinished: Boolean
        get() = isCancelled || currentState.isTerminal || isStepLimitReached() || isTimedOut()

    /**
     * Yeni bir ekran gözlemlendiğinde visited ve progress durumlarını günceller.
     * @return Yeni bir ekran ise true, daha önce görülmüşse false döner.
     */
    fun onScreenObserved(fingerprint: ScreenFingerprint, packageName: String): Boolean {
        val isNewScreen = visitedScreens.add(fingerprint.value)
        val isNewPackage = packageName.isNotBlank() && visitedPackages.add(packageName)

        val newScreenDelta = if (isNewScreen) 1 else 0
        val newPkgDelta = if (isNewPackage) 1 else 0

        if (isNewScreen || isNewPackage) {
            progress = progress.copy(
                discoveredScreensCount = progress.discoveredScreensCount + newScreenDelta,
                discoveredPackagesCount = progress.discoveredPackagesCount + newPkgDelta
            )
        }
        return isNewScreen
    }

    /**
     * Gerçekleştirilen eylemi ve sonucunu geçmişe kaydeder.
     */
    fun recordActionOutcome(
        stepNumber: Int,
        fromFingerprint: String,
        actionType: AgentActionType,
        targetDescription: String,
        thought: String,
        toFingerprint: String?,
        diffType: ScreenDifferenceType?,
        isSuccess: Boolean
    ) {
        actionHistory.add(
            ExplorationStepRecord(
                stepNumber = stepNumber,
                fromFingerprint = fromFingerprint,
                actionType = actionType,
                targetDescription = targetDescription,
                thought = thought,
                toFingerprint = toFingerprint,
                differenceType = diffType,
                isSuccess = isSuccess
            )
        )
        if (isSuccess) {
            progress = progress.copy(
                successfulActionsCount = progress.successfulActionsCount + 1
            )
        }
    }

    /**
     * Yeni bir bilgi/hafıza öğrenildiğinde progress sayacını günceller.
     */
    fun onMemoryLearned() {
        progress = progress.copy(
            memoriesLearnedCount = progress.memoriesLearnedCount + 1
        )
    }

    /**
     * Görevi iptal eder.
     */
    fun cancel(reason: String = "Kullanıcı tarafından durduruldu") {
        isCancelled = true
        cancellationReason = reason
        currentState = AgentState.CANCELLED
    }
}
