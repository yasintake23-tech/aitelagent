package com.example.agent.multibrain

import android.util.Log
import com.example.agent.brain.ActionProposal
import com.example.agent.brain.AgentActionType
import com.example.agent.brain.AgentWorkingMemory
import com.example.data.security.AgentLogStore
import com.example.service.AiDeviceAccessibilityService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val coordinationMutex = Mutex()

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

    fun updateArchitecture(architecture: Architecture) {
        currentArchitecture = architecture
        messages.clear()
        activeTaskId = null
        Log.i(tag, "Architecture updated: $currentArchitecture")
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

        // 1) Groq: ilk karar
        var currentProposal = reasoning.proposeAction(goal, context, messages).withTask(taskId)
        if (currentProposal.messageType == AgentMessageType.ERROR) {
            return createReplan(taskId, "Groq Reasoning sağlayıcısı başarısız oldu.", "REASONING_PROVIDER_ERROR")
        }
        messages.add(currentProposal)
        AiDeviceAccessibilityService.instance?.applicationContext?.let { AgentLogStore.record(it, "INFO", tag, "Groq initial proposal: ${currentProposal.proposedAction?.actionType}") }

        // 2) HF Vision: seçili 2/3-beyin mimarisinde her fiziksel adım için görsel grounding.
        val vision = visionBrain
            ?: return createReplan(taskId, "Vision Brain yapılandırılmamış.", "VISION_UNAVAILABLE")
        if (context.screenshot == null) {
            return createReplan(taskId, "Multi-Brain fiziksel görev için canlı ekran görüntüsü gerekli.", "VISION_SCREENSHOT_MISSING")
        }

        val visionRequired = currentProposal.structuredPayload["visionRequired"]?.toBooleanStrictOrNull() == true
        val targetMissing = currentProposal.structuredPayload["targetMissing"]?.toBooleanStrictOrNull() == true
        val observation = if (visionRequired || targetMissing || currentProposal.confidence < 0.99) {
            vision.analyzeScreen(context, currentProposal.proposedAction?.target).withTask(taskId)
        } else {
            AgentMessage(taskId, sender = "HF_VISION", receiver = "ORCHESTRATOR", messageType = AgentMessageType.OBSERVATION, decisionSummary = "Vision gereksiz olarak işaretlendi; ekran ağacı yeterli.", confidence = 1.0, structuredPayload = mapOf("skipped" to "true"))
        }
        if (observation.messageType == AgentMessageType.ERROR) {
            return createReplan(taskId, "Vision sağlayıcısı başarısız oldu; başka AI'ya gizli geçiş yapılmayacak.", observation.errorCode ?: "VISION_PROVIDER_ERROR")
        }
        messages.add(observation)
        AiDeviceAccessibilityService.instance?.applicationContext?.let { AgentLogStore.record(it, "INFO", tag, "HF Vision: found=${observation.structuredPayload["found"]}; confidence=${observation.confidence}") }

        // 3) Groq: HF grounding bilgisini okuyup tek bir son ActionProposal üretir.
        val refinedGoal = if (goal.isBlank()) "Ekrandaki mevcut duruma göre görevi ilerletecek tek güvenli adımı seç." else goal
        val refined = reasoning.proposeAction(refinedGoal, context, messages).withTask(taskId)
        if (refined.messageType == AgentMessageType.ERROR) {
            return createReplan(taskId, "Vision sonrası Groq yeniden değerlendirmesi başarısız oldu.", "REFINEMENT_PROVIDER_ERROR")
        }
        currentProposal = refined
        messages.add(currentProposal)
        AiDeviceAccessibilityService.instance?.applicationContext?.let { AgentLogStore.record(it, "INFO", tag, "Groq refinement: ${currentProposal.proposedAction?.actionType}") }

        // 4) Gemini: 3-beyin mimarisinde her fiziksel adım için bağımsız ikinci görüş.
        if (currentArchitecture == Architecture.GROQ_HF_GEMINI) {
            val advisor = advisorBrain
                ?: return createReplan(taskId, "Gemini Advisor yapılandırılmamış.", "ADVISOR_UNAVAILABLE")

            val advice = advisor.provideSecondOpinion(goal, context, currentProposal).withTask(taskId)
            if (advice.messageType == AgentMessageType.ERROR) {
                return createReplan(taskId, "Gemini Advisor sağlayıcısı başarısız oldu.", advice.errorCode ?: "ADVISOR_PROVIDER_ERROR")
            }
            messages.add(advice)
            AiDeviceAccessibilityService.instance?.applicationContext?.let { AgentLogStore.record(it, "INFO", tag, "Gemini Advisor: agreement=${advice.structuredPayload["agreement"]}; risk=${advice.riskLevel}") }

            val agreement = advice.structuredPayload["agreement"]?.toBooleanStrictOrNull() ?: false
            val advisorHighRisk = advice.riskLevel == AgentRiskLevel.HIGH_RISK
            val advisorLowConfidence = advice.confidence < 0.5
            if (!agreement || advisorHighRisk || advisorLowConfidence) {
                Log.w(tag, "Advisor veto: agreement=$agreement highRisk=$advisorHighRisk lowConfidence=$advisorLowConfidence")
                return currentProposal.copy(
                    taskId = taskId,
                    decisionSummary = "Gemini Advisor onaylamadı: ${advice.decisionSummary}".take(500),
                    proposedAction = currentProposal.proposedAction?.copy(
                        actionType = AgentActionType.REPLAN,
                        reason = "Gemini Advisor veto/uyuşmazlık: ${advice.decisionSummary}".take(500)
                    ),
                    riskLevel = if (advisorHighRisk) AgentRiskLevel.HIGH_RISK else currentProposal.riskLevel
                )
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
