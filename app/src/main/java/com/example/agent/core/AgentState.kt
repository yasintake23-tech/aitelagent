package com.example.agent.core

/**
 * Agent'ın yaşam döngüsünü ve anlık yürütme durumunu temsil eden merkezi durumlar.
 */
enum class AgentState {
    IDLE,
    PLANNING,
    OBSERVING,
    ACTING,
    VERIFYING,
    RECOVERING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isExecuting: Boolean
        get() = !isTerminal && this != IDLE
}
