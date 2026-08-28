package com.example.agent.brain

/**
 * Desteklenen yapılandırılmış eylem türleri.
 */
object AgentActionType {
    const val CLICK_NODE = "CLICK_NODE"
    const val CLICK_COORD = "CLICK_COORD"
    const val SWIPE = "SWIPE"
    const val TYPE_TEXT = "TYPE_TEXT"
    const val PRESS_BACK = "PRESS_BACK"
    const val PRESS_HOME = "PRESS_HOME"
    const val OPEN_APP = "OPEN_APP"
    const val COMPLETE = "COMPLETE"
    const val REPLAN = "REPLAN"
    const val NO_ACTION = "NO_ACTION"
    const val USER_REQUIRED = "USER_REQUIRED"
    const val SWIPE_DOWN = "SWIPE_DOWN"
    const val SWIPE_UP = "SWIPE_UP"
    const val SWIPE_LEFT = "SWIPE_LEFT"
    const val SWIPE_RIGHT = "SWIPE_RIGHT"
    const val OPEN_QUICK_SETTINGS = "OPEN_QUICK_SETTINGS"
    const val OPEN_NOTIFICATIONS = "OPEN_NOTIFICATIONS"
    const val VOLUME_UP = "VOLUME_UP"
    const val VOLUME_DOWN = "VOLUME_DOWN"
}

/**
 * Bir aksiyon icra edildikten sonra gerçekleşmesi beklenen durum.
 */
data class ExpectedOutcomeSpec(
    val screenChangeExpected: Boolean = true,
    val expectedPackage: String? = null,
    val expectedText: List<String> = emptyList(),
    val expectedState: String? = null
)

/**
 * LLM tarafından önerilen yapılandırılmış eylem teklifi (Action Proposal).
 */
data class ActionProposal(
    val actionType: String,
    val target: String? = null,
    val targetIndex: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val textPayload: String? = null,
    val reason: String = "",
    val expectedOutcome: ExpectedOutcomeSpec? = null,
    val confidence: Double = 1.0,
    val memoryKey: String? = null,
    val memoryValue: String? = null
)

/**
 * Engellenen veya başarısız olan rotaları temsil eden blok kaydı.
 */
data class BlockedRoute(
    val packageName: String,
    val screenFingerprint: String,
    val actionType: String,
    val target: String?,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Gerçek bir rota belleği kaydı.
 */
data class RouteMemoryEntry(
    val fromScreenFingerprint: String?,
    val toScreenFingerprint: String?,
    val packageName: String?,
    val actionType: String,
    val target: String?,
    val subGoal: String?,
    val isSuccess: Boolean,
    val failureReason: String?,
    val safetyBlocked: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

