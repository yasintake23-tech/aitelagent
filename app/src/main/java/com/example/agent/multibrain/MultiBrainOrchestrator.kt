package com.example.agent.multibrain

import android.util.Log
import com.example.agent.brain.ActionProposal
import com.example.agent.brain.AgentActionType
import com.example.agent.brain.AgentWorkingMemory

/**
 * Deterministic hierarchical multi-brain council.
 * Groq is the decision brain, HF is an optional visual grounding witness,
 * Gemini is an on-demand safety/advisor brain. No provider fallback is hidden here.
 */
class MultiBrainOrchestrator(
    private val workingMemory: AgentWorkingMemory
) {
    private val tag = "MultiBrainOrch"

    enum class Architecture {
        GROQ_HF,
        GROQ_HF_GEMINI
    }

    var currentArchitecture: Architecture = Architecture.GROQ_HF_GEMINI
        private set

    private var reasoningBrain: ReasoningBrain? = null
    private var visionBrain: VisionBrain? = null
    private var advisorBrain: AdvisorBrain? = null

    private val messages = mutableListOf<AgentMessage>()
    private var activeTaskId: String? = null

    fun setBrains(
        reasoning: ReasoningBrain?,
        vision: VisionBrain?,
        advisor: AdvisorBrain?,
        architecture: Architecture = Architecture.GROQ_HF_GEMINI
    ) {
        reasoningBrain = reasoning
        visionBrain = vision
        advisorBrain = advisor
        currentArchitecture = architecture
        messages.clear()
        activeTaskId = null
    }

    fun resetTask(taskId: String) {
        if (activeTaskId != taskId) {
            messages.clear()
            activeTaskId = taskId
        }
    }

    suspend fun coordinate(goal: String, context: ScreenContext, taskId: String): AgentMessage {
        resetTask(taskId)
        Log.d(tag, "Council started task=$taskId goal=$goal architecture=$currentArchitecture")

        val reasoning = reasoningBrain
            ?: return createReplan(taskId, "Reasoning Brain kullanılamıyor.", "REASONING_UNAVAILABLE")

        var currentProposal = reasoning.proposeAction(goal, context, messages).withTask(taskId)
        if (currentProposal.messageType == AgentMessageType.ERROR) {
            return createReplan(taskId, "Groq Reasoning sağlayıcısı başarısız oldu.", "REASONING_PROVIDER_ERROR")
        }
        messages.add(currentProposal)

        val payload = currentProposal.structuredPayload
        val requiresVision = payload["visionRequired"].equals("true", ignoreCase = true)
        val targetMissing = payload["targetMissing"].equals("true", ignoreCase = true)
        val needsVision = currentProposal.confidence < 0.8 || requiresVision || targetMissing

        if (needsVision) {
            val vision = visionBrain
                ?: return createReplan(taskId, "Görsel doğrulama gerekli ancak Vision Brain kullanılamıyor.", "VISION_UNAVAILABLE")

            Log.d(tag, "Vision refinement requested confidence=${currentProposal.confidence} required=$requiresVision missing=$targetMissing")
            val observation = vision.analyzeScreen(context, currentProposal.proposedAction?.target)
                .withTask(taskId)
            if (observation.messageType == AgentMessageType.ERROR) {
                return createReplan(taskId, "Vision sağlayıcısı başarısız oldu.", observation.errorCode ?: "VISION_PROVIDER_ERROR")
            }
            messages.add(observation)

            val refined = reasoning.proposeAction(goal, context, messages).withTask(taskId)
            if (refined.messageType == AgentMessageType.ERROR) {
                return createReplan(taskId, "Vision sonrası Groq yeniden değerlendirmesi başarısız oldu.", "REFINEMENT_PROVIDER_ERROR")
            }
            currentProposal = refined
            messages.add(currentProposal)
        }

        if (currentArchitecture == Architecture.GROQ_HF_GEMINI) {
            val advisor = advisorBrain
                ?: return createReplan(taskId, "Advisor Brain seçili mimaride kullanılamıyor.", "ADVISOR_UNAVAILABLE")

            val riskTriggered = currentProposal.confidence < 0.7 || currentProposal.riskLevel == AgentRiskLevel.HIGH_RISK
            val conflictTriggered = messages.any {
                it.messageType == AgentMessageType.ACTION_PROPOSAL &&
                    it.proposedAction != null &&
                    it.proposedAction != currentProposal.proposedAction
            }

            if (riskTriggered || conflictTriggered) {
                Log.d(tag, "Advisor requested risk=$riskTriggered conflict=$conflictTriggered")
                val advice = advisor.provideSecondOpinion(goal, context, currentProposal).withTask(taskId)
                if (advice.messageType == AgentMessageType.ERROR) {
                    return createReplan(taskId, "Gemini Advisor sağlayıcısı başarısız oldu.", advice.errorCode ?: "ADVISOR_PROVIDER_ERROR")
                }
                messages.add(advice)

                val agreement = advice.structuredPayload["agreement"]?.toBooleanStrictOrNull() ?: true
                val advisorHighRisk = advice.riskLevel == AgentRiskLevel.HIGH_RISK
                val advisorLowConfidence = advice.confidence < 0.5
                if (!agreement || advisorHighRisk || advisorLowConfidence) {
                    Log.w(tag, "Advisor veto: agreement=$agreement highRisk=$advisorHighRisk lowConfidence=$advisorLowConfidence")
                    return currentProposal.copy(
                        taskId = taskId,
                        decisionSummary = "Advisor yeniden planlama istedi: ${advice.decisionSummary}".take(500),
                        proposedAction = currentProposal.proposedAction?.copy(
                            actionType = AgentActionType.REPLAN,
                            reason = "Gemini Advisor veto/uyuşmazlık: ${advice.decisionSummary}".take(500)
                        ),
                        riskLevel = if (advisorHighRisk) AgentRiskLevel.HIGH_RISK else currentProposal.riskLevel
                    )
                }
            }
        }

        return currentProposal.withTask(taskId)
    }

    private fun createReplan(taskId: String, message: String, code: String): AgentMessage = AgentMessage(
        taskId = taskId,
        sender = "ORCHESTRATOR",
        receiver = "AGENT_BRAIN",
        messageType = AgentMessageType.ACTION_PROPOSAL,
        decisionSummary = message,
        confidence = 0.0,
        riskLevel = AgentRiskLevel.HIGH_RISK,
        proposedAction = ActionProposal(
            actionType = AgentActionType.REPLAN,
            reason = message
        ),
        errorCode = code
    )

    private fun AgentMessage.withTask(taskId: String): AgentMessage = copy(taskId = taskId)
}
