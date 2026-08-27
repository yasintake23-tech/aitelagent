package com.example.agent.core

/**
 * Bir otonom görevin sınırlarını, zaman aşımlarını ve yeniden deneme limitlerini merkezi tutan bütçe modeli.
 */
data class TaskBudget(
    val maxSteps: Int = 15,
    val maxRetriesPerStep: Int = 3,
    val overallTimeoutMs: Long = 180_000L, // 3 dakika
    val perStepTimeoutMs: Long = 20_000L,  // 20 saniye
    val maxConsecutiveFailures: Int = 3
)
