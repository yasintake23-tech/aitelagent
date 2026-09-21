package com.example.agent.multibrain.adapters

import android.util.Log
import com.example.agent.brain.ActionProposal
import com.example.agent.brain.AgentActionType
import com.example.agent.multibrain.AgentMessage
import com.example.agent.multibrain.AgentMessageType
import com.example.agent.multibrain.AgentRiskLevel
import com.example.agent.multibrain.ReasoningBrain
import com.example.agent.multibrain.ScreenContext
import com.example.ai.AIProviderManager
import org.json.JSONObject

class GroqReasoningBrainAdapter(
    private val aiProviderManager: AIProviderManager
) : ReasoningBrain {

    private val TAG = "GroqReasoningAdapter"
    private val providerId = "groq"

    override suspend fun proposePlan(goal: String, context: ScreenContext): List<String> {
        val systemPrompt = """
            Sen bir Android otomasyon planlayıcısısın. 
            Görevin, kullanıcının hedefine ulaşmak için yüksek seviyeli adımlar oluşturmaktır.
            Yanıtın sadece JSON listesi olmalıdır: ["adım 1", "adım 2", ...]
        """.trimIndent()

        val userPrompt = "Hedef: $goal. Mevcut Ekran: ${context.packageName}/${context.activityName}"

        return try {
            val response = aiProviderManager.generateStructuralContent(
                providerId = providerId,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt
            )
            val json = parseJsonObject(response)
            val stepsArray = json.optJSONArray("steps") ?: json.optJSONArray("plan")
            val steps = mutableListOf<String>()
            if (stepsArray != null) {
                for (i in 0 until stepsArray.length()) {
                    steps.add(stepsArray.getString(i))
                }
            } else {
                val directArray = try { org.json.JSONArray(response) } catch (e: Exception) { null }
                if (directArray != null) {
                    for (i in 0 until directArray.length()) {
                        steps.add(directArray.getString(i))
                    }
                }
            }
            steps
        } catch (e: Exception) {
            Log.e(TAG, "Error proposing plan", e)
            emptyList()
        }
    }

    override suspend fun proposeAction(
        goal: String,
        context: ScreenContext,
        history: List<AgentMessage>
    ): AgentMessage {
        val systemPrompt = """
            Sen Lumina AI'nın ana Android Karar Beyni'sin.
            Ekranı, görev hedefini, kronolojik geçmişi ve varsa Vision Brain gözlemini değerlendir.
            Her turda yalnızca TEK bir yapılandırılmış eylem öner.
            Vision Brain'den gelen x/y koordinatlarını yalnızca aday bilgi olarak değerlendir; fiziksel eylemi sen önerirsin.
            Hedef belirsizse veya Vision doğrulaması gerekiyorsa visionRequired=true üret.

            YANIT YALNIZCA SAF JSON:
            {
              "decisionSummary": "kısa karar özeti",
              "confidence": 0.0,
              "riskLevel": "SAFE|SENSITIVE|HIGH_RISK",
              "visionRequired": false,
              "targetMissing": false,
              "proposedAction": {
                "type": "CLICK_NODE|CLICK_COORD|TYPE_TEXT|SWIPE_DOWN|SWIPE_UP|SWIPE_LEFT|SWIPE_RIGHT|PRESS_BACK|PRESS_HOME|OPEN_APP|COMPLETE|REPLAN|NO_ACTION",
                "targetId": "metin veya viewId",
                "targetIndex": null,
                "text": "yazılacak metin veya null",
                "x": null,
                "y": null
              }
            }
            Rastgele hedef seçme. Node id varsa onu tercih et. Emin değilsen REPLAN.
        """.trimIndent()

        val historyText = history.takeLast(10).joinToString("\n") { message ->
            val payload = message.structuredPayload.entries
                .joinToString(" ") { (key, value) -> "$key=$value" }
            "${message.sender}: ${message.decisionSummary} ${payload}".trim()
        }
        
        val userPrompt = """
            Hedef: $goal
            Ekran Snapshot: ${context.snapshot}
            Geçmiş:
            $historyText
        """.trimIndent()

        return try {
            val response = aiProviderManager.generateStructuralContent(
                providerId = providerId,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt
            )
            val json = parseJsonObject(response)
            
            val actionJson = json.optJSONObject("proposedAction")
            val rawActionType = actionJson?.optString("type", AgentActionType.NO_ACTION) ?: AgentActionType.NO_ACTION
            val allowedActions = setOf(
                AgentActionType.CLICK_NODE,
                AgentActionType.CLICK_COORD,
                AgentActionType.TYPE_TEXT,
                AgentActionType.SWIPE_DOWN,
                AgentActionType.SWIPE_UP,
                AgentActionType.SWIPE_LEFT,
                AgentActionType.SWIPE_RIGHT,
                AgentActionType.PRESS_BACK,
                AgentActionType.PRESS_HOME,
                AgentActionType.OPEN_APP,
                AgentActionType.COMPLETE,
                AgentActionType.REPLAN,
                AgentActionType.NO_ACTION
            )
            val actionType = rawActionType.takeIf { it in allowedActions } ?: AgentActionType.NO_ACTION
            
            val rawTarget = actionJson?.optString("targetId")?.trim().orEmpty()
            val targetIndex = if (actionJson?.has("targetIndex") == true && !actionJson.isNull("targetIndex")) actionJson.optInt("targetIndex") else null
            val proposal = ActionProposal(
                actionType = actionType,
                target = rawTarget.takeIf { it.isNotBlank() },
                targetIndex = targetIndex,
                textPayload = actionJson?.optString("text")?.takeIf { it.isNotBlank() },
                x = if (actionJson?.has("x") == true && !actionJson.isNull("x")) actionJson.optInt("x") else null,
                y = if (actionJson?.has("y") == true && !actionJson.isNull("y")) actionJson.optInt("y") else null,
                reason = json.optString("decisionSummary", ""),
                confidence = json.optDouble("confidence", 0.5).coerceIn(0.0, 1.0)
            )

            AgentMessage(
                taskId = history.firstOrNull()?.taskId ?: "UNKNOWN_TASK",
                sender = "GROQ_REASONING",
                receiver = "ORCHESTRATOR",
                messageType = AgentMessageType.ACTION_PROPOSAL,
                decisionSummary = proposal.reason,
                confidence = proposal.confidence,
                riskLevel = parseRiskLevel(json.optString("riskLevel")),
                proposedAction = proposal,
                structuredPayload = mapOf(
                    "visionRequired" to json.optBoolean("visionRequired", false).toString(),
                    "targetMissing" to json.optBoolean("targetMissing", false).toString()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error proposing action", e)
            AgentMessage(
                taskId = history.firstOrNull()?.taskId ?: "UNKNOWN_TASK",
                sender = "GROQ_REASONING",
                receiver = "ORCHESTRATOR",
                messageType = AgentMessageType.ERROR,
                decisionSummary = "Failed to generate action: ${e.message}",
                confidence = 0.0,
                errorCode = "PROVIDER_ERROR"
            )
        }
    }

    private fun parseJsonObject(raw: String): JSONObject {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            JSONObject(cleaned)
        } catch (_: Exception) {
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start >= 0 && end > start) JSONObject(cleaned.substring(start, end + 1))
            else throw IllegalArgumentException("Groq JSON parse edilemedi")
        }
    }

    private fun parseRiskLevel(level: String?): AgentRiskLevel {
        return when (level?.uppercase()) {
            "SENSITIVE" -> AgentRiskLevel.SENSITIVE
            "HIGH_RISK" -> AgentRiskLevel.HIGH_RISK
            else -> AgentRiskLevel.SAFE
        }
    }
}
