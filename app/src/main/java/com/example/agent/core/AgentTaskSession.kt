package com.example.agent.core

import java.util.UUID

/**
 * Her otonom görevin kendi bağımsız oturumunu temsil eden veri modeli.
 * Görev süresi, mevcut adım, durum, iptal ve bütçe takibini yapar.
 */
data class AgentTaskSession(
    val taskId: String = UUID.randomUUID().toString(),
    val taskGoal: String,
    val startTimeMs: Long = System.currentTimeMillis(),
    val budget: TaskBudget = TaskBudget(),
    val currentState: AgentState = AgentState.IDLE,
    val currentStep: Int = 0,
    val isCancelled: Boolean = false,
    val cancellationReason: String? = null,
    val errorMessage: String? = null,
    val resultSummary: String? = null
) {
    /**
     * Görevin toplam zaman aşımına uğrayıp uğramadığını kontrol eder.
     */
    fun isTimedOut(currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        return (currentTimeMs - startTimeMs) > budget.overallTimeoutMs
    }

    /**
     * Maksimum adım sınırına ulaşılıp ulaşılmadığını kontrol eder.
     */
    fun isStepLimitReached(): Boolean {
        return currentStep >= budget.maxSteps
    }

    /**
     * Oturumun sonlanıp sonlanmadığını kontrol eder.
     */
    val isFinished: Boolean
        get() = isCancelled || currentState.isTerminal || isStepLimitReached() || isTimedOut()
}
