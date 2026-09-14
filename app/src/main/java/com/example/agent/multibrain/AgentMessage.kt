package com.example.agent.multibrain

import com.example.agent.brain.ActionProposal

enum class AgentMessageType {
    PLAN,
    OBSERVATION,
    ACTION_PROPOSAL,
    ADVICE,
    ERROR
}

data class AgentMessage(
    val taskId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sender: String,
    val receiver: String,
    val messageType: AgentMessageType,
    val decisionSummary: String,
    val confidence: Double,
    val riskLevel: AgentRiskLevel = AgentRiskLevel.SAFE,
    val proposedAction: ActionProposal? = null,
    val structuredPayload: Map<String, String> = emptyMap(),
    val errorCode: String? = null
)
