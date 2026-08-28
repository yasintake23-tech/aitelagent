package com.example.agent.brain

/**
 * Bir SubGoal'in durumunu belirten enum.
 */
enum class SubGoalStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

/**
 * Bir Plan'ın genel durumunu belirten enum.
 */
enum class PlanStatus {
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    REPLANNING
}

/**
 * Tekil bir alt hedef (Sub-Goal).
 */
data class SubGoal(
    val id: Int,
    val description: String,
    val expectedPackage: String? = null,
    val expectedScreen: String? = null,
    val expectedOutcome: String = "",
    val status: SubGoalStatus = SubGoalStatus.PENDING,
    val attempts: Int = 0,
    val maxAttempts: Int = 3
)

/**
 * Dinamik olarak oluşturulan çok adımlı Agent Planı.
 */
data class AgentPlan(
    val originalGoal: String,
    val targetApp: String? = null,
    val targetEntity: String? = null,
    val requestedAction: String? = null,
    val subGoals: List<SubGoal> = emptyList(),
    val currentSubGoalIndex: Int = 0,
    val completionCriteria: String? = null,
    val safetyConstraints: List<String> = emptyList(),
    val replanningCount: Int = 0,
    val planStatus: PlanStatus = PlanStatus.IN_PROGRESS
) {
    val currentSubGoal: SubGoal?
        get() = subGoals.getOrNull(currentSubGoalIndex)

    val isFinished: Boolean
        get() = planStatus == PlanStatus.COMPLETED || planStatus == PlanStatus.FAILED
}
