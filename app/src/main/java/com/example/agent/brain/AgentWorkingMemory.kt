package com.example.agent.brain

import android.util.Log

/**
 * Tekil bir eylem ve bunun sonucunu temsil eden geçmiş kaydı.
 */
data class ActionResultRecord(
    val stepIndex: Int,
    val actionType: String,
    val target: String?,
    val isSuccess: Boolean,
    val resultSummary: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Başarısız eylem veya hata kaydı.
 */
data class FailureEntry(
    val stepIndex: Int,
    val actionType: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Agent'ın çalışma belleğinin anlık görüntüsü (immutable state snapshot).
 */
data class AgentWorkingMemoryState(
    val originalGoal: String = "",
    val currentSubGoal: String? = null,
    val currentPlan: List<String> = emptyList(),
    val currentStepIndex: Int = 0,
    val currentScreenFingerprint: String? = null,
    val currentPackageName: String? = null,
    val activeAppTitle: String? = null,
    val actionHistory: List<ActionResultRecord> = emptyList(),
    val visitedPackages: Set<String> = emptySet(),
    val visitedScreens: Set<String> = emptySet(), // Geriye dönük uyumluluk
    val visitedScreenFingerprints: Set<String> = emptySet(),
    val screenVisitHistory: List<String> = emptyList(),
    val packageVisitHistory: List<String> = emptyList(),
    val failureHistory: List<FailureEntry> = emptyList(),
    val consecutiveFailures: Int = 0,
    val blockedRoutes: List<BlockedRoute> = emptyList(),
    val failedAttemptKeys: Map<String, Int> = emptyMap(),
    val routeHistory: List<RouteMemoryEntry> = emptyList()
)

/**
 * Thread-safe ve immutable snapshot güncellemeleri sunan Agent Çalışma Belleği yöneticisi.
 */
class AgentWorkingMemory(initialGoal: String = "") {

    @Volatile
    private var _state = AgentWorkingMemoryState(originalGoal = initialGoal)

    val state: AgentWorkingMemoryState
        get() = _state

    @Synchronized
    fun setGoal(goal: String) {
        _state = _state.copy(originalGoal = goal)
    }

    @Synchronized
    fun setPlan(plan: List<String>, subGoal: String? = plan.firstOrNull()) {
        _state = _state.copy(
            currentPlan = plan,
            currentSubGoal = subGoal,
            currentStepIndex = 0
        )
    }

    @Synchronized
    fun updateSubGoal(subGoal: String?, stepIndex: Int = _state.currentStepIndex) {
        _state = _state.copy(
            currentSubGoal = subGoal,
            currentStepIndex = stepIndex
        )
    }

    @Synchronized
    fun updateScreenState(fingerprint: String?, packageName: String?, appTitle: String? = null) {
        val updatedVisitedFingerprints = if (!fingerprint.isNullOrBlank()) {
            _state.visitedScreenFingerprints + fingerprint
        } else {
            _state.visitedScreenFingerprints
        }

        val updatedVisitedScreens = if (!fingerprint.isNullOrBlank()) {
            _state.visitedScreens + fingerprint
        } else {
            _state.visitedScreens
        }

        val updatedVisitedPackages = if (!packageName.isNullOrBlank()) {
            _state.visitedPackages + packageName
        } else {
            _state.visitedPackages
        }

        val updatedScreenHistory = if (!fingerprint.isNullOrBlank()) {
            (_state.screenVisitHistory + fingerprint).takeLast(MAX_HISTORY_SIZE)
        } else {
            _state.screenVisitHistory
        }

        val updatedPackageHistory = if (!packageName.isNullOrBlank()) {
            (_state.packageVisitHistory + packageName).takeLast(MAX_HISTORY_SIZE)
        } else {
            _state.packageVisitHistory
        }

        _state = _state.copy(
            currentScreenFingerprint = fingerprint,
            currentPackageName = packageName,
            activeAppTitle = appTitle,
            visitedScreenFingerprints = updatedVisitedFingerprints,
            visitedScreens = updatedVisitedScreens,
            visitedPackages = updatedVisitedPackages,
            screenVisitHistory = updatedScreenHistory,
            packageVisitHistory = updatedPackageHistory
        )
    }

    @Synchronized
    fun recordActionResult(
        stepIndex: Int,
        actionType: String,
        target: String?,
        isSuccess: Boolean,
        resultSummary: String,
        fromScreenFingerprint: String? = null,
        toScreenFingerprint: String? = null
    ) {
        val newRecord = ActionResultRecord(
            stepIndex = stepIndex,
            actionType = actionType,
            target = target,
            isSuccess = isSuccess,
            resultSummary = resultSummary
        )

        val updatedHistory = (_state.actionHistory + newRecord).takeLast(MAX_ACTION_HISTORY_SIZE)
        val updatedConsecutiveFailures = if (isSuccess) 0 else _state.consecutiveFailures + 1

        val updatedFailures = if (!isSuccess) {
            _state.failureHistory + FailureEntry(
                stepIndex = stepIndex,
                actionType = actionType,
                reason = resultSummary
            )
        } else {
            _state.failureHistory
        }

        val currentPkg = _state.currentPackageName ?: ""
        val currentFp = _state.currentScreenFingerprint ?: ""
        val currentSub = _state.currentSubGoal ?: ""

        val semanticTarget = getSemanticTarget(target, null)
        val attemptKey = generateAttemptKey(currentPkg, currentFp, currentSub, actionType, semanticTarget)

        val updatedAttemptKeys = if (!isSuccess && attemptKey.isNotBlank()) {
            val currentCount = _state.failedAttemptKeys[attemptKey] ?: 0
            _state.failedAttemptKeys + (attemptKey to currentCount + 1)
        } else {
            _state.failedAttemptKeys
        }

        // Rota Belleği Kaydı
        val routeEntry = RouteMemoryEntry(
            fromScreenFingerprint = fromScreenFingerprint ?: _state.currentScreenFingerprint,
            toScreenFingerprint = toScreenFingerprint ?: _state.currentScreenFingerprint,
            packageName = _state.currentPackageName,
            actionType = actionType,
            target = target,
            subGoal = _state.currentSubGoal,
            isSuccess = isSuccess,
            failureReason = if (isSuccess) null else resultSummary,
            safetyBlocked = false
        )

        val updatedRouteHistory = (_state.routeHistory + routeEntry).takeLast(MAX_ROUTE_HISTORY_SIZE)

        _state = _state.copy(
            actionHistory = updatedHistory,
            consecutiveFailures = updatedConsecutiveFailures,
            failureHistory = updatedFailures,
            failedAttemptKeys = updatedAttemptKeys,
            routeHistory = updatedRouteHistory
        )
    }

    @Synchronized
    fun recordFailure(stepIndex: Int, actionType: String, reason: String) {
        val failure = FailureEntry(stepIndex, actionType, reason)
        _state = _state.copy(
            failureHistory = _state.failureHistory + failure,
            consecutiveFailures = _state.consecutiveFailures + 1
        )
    }

    @Synchronized
    fun addBlockedRoute(route: BlockedRoute) {
        val routeEntry = RouteMemoryEntry(
            fromScreenFingerprint = route.screenFingerprint,
            toScreenFingerprint = route.screenFingerprint,
            packageName = route.packageName,
            actionType = route.actionType,
            target = route.target,
            subGoal = _state.currentSubGoal,
            isSuccess = false,
            failureReason = "Safety Blocked: ${route.reason}",
            safetyBlocked = true
        )

        _state = _state.copy(
            blockedRoutes = _state.blockedRoutes + route,
            routeHistory = (_state.routeHistory + routeEntry).takeLast(MAX_ROUTE_HISTORY_SIZE)
        )
    }

    fun isRouteBlocked(packageName: String, screenFingerprint: String, target: String?, subGoal: String? = null, actionType: String? = null): Boolean {
        // Tam eşleşme süzgeci (Safety block)
        val semanticTarget = getSemanticTarget(target, null)
        return _state.blockedRoutes.any {
            it.packageName == packageName &&
                    it.screenFingerprint == screenFingerprint &&
                    (target == null || getSemanticTarget(it.target, null) == semanticTarget) &&
                    (actionType == null || it.actionType == actionType)
        }
    }

    fun isAttemptKeyTooFrequent(packageName: String, screenFingerprint: String, subGoal: String, actionType: String, target: String): Boolean {
        val semanticTarget = getSemanticTarget(target, null)
        val key = generateAttemptKey(packageName, screenFingerprint, subGoal, actionType, semanticTarget)
        val count = _state.failedAttemptKeys[key] ?: 0
        return count >= MAX_ALLOWED_ATTEMPTS_PER_KEY
    }

    fun generateAttemptKey(packageName: String, screenFingerprint: String, subGoal: String, actionType: String, semanticTarget: String): String {
        return "${packageName}_${screenFingerprint}_${subGoal}_${actionType}_${semanticTarget}".lowercase()
    }

    fun getSemanticTarget(target: String?, textPayload: String?): String {
        val raw = target ?: textPayload ?: "none"
        // Target indexleri ve koordinatları temizle
        return raw.replace(Regex("\\d+"), "").trim().lowercase()
    }

    fun detectApplicationLoop(): Boolean {
        val history = _state.packageVisitHistory
        if (history.size < 4) return false
        val n = history.size
        // HOME -> WhatsApp -> HOME -> WhatsApp (length 4)
        if (history[n - 1] == history[n - 3] && history[n - 2] == history[n - 4]) {
            return true
        }
        // Period 3 loop: A -> B -> C -> A -> B -> C (length 6)
        if (history.size >= 6) {
            if (history[n - 1] == history[n - 4] && history[n - 2] == history[n - 5] && history[n - 3] == history[n - 6]) {
                return true
            }
        }
        return false
    }

    fun detectScreenLoop(): Boolean {
        val history = _state.screenVisitHistory
        if (history.size < 4) return false
        val n = history.size
        // A -> B -> A -> B -> A (length 5) or A -> B -> A -> B (length 4)
        if (history[n - 1] == history[n - 3] && history[n - 2] == history[n - 4]) {
            return true
        }
        // Period 3 loop: A -> B -> C -> A -> B -> C
        if (history.size >= 6) {
            if (history[n - 1] == history[n - 4] && history[n - 2] == history[n - 5] && history[n - 3] == history[n - 6]) {
                return true
            }
        }
        return false
    }

    @Synchronized
    fun reset(newGoal: String = "") {
        _state = AgentWorkingMemoryState(originalGoal = newGoal)
    }

    companion object {
        const val MAX_ACTION_HISTORY_SIZE = 5
        const val MAX_ALLOWED_ATTEMPTS_PER_KEY = 2
        const val MAX_HISTORY_SIZE = 30
        const val MAX_ROUTE_HISTORY_SIZE = 50
    }
}
