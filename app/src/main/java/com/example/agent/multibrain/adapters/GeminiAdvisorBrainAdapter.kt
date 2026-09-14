package com.example.agent.multibrain.adapters

import android.util.Log
import com.example.agent.multibrain.AdvisorBrain
import com.example.agent.multibrain.AgentMessage
import com.example.agent.multibrain.AgentMessageType
import com.example.agent.multibrain.AgentRiskLevel
import com.example.agent.multibrain.ScreenContext
import com.example.ai.AIProviderManager
import org.json.JSONObject

class GeminiAdvisorBrainAdapter(
    private val aiProviderManager: AIProviderManager
) : AdvisorBrain {

    private val TAG = "GeminiAdvisorAdapter"
    private val providerId = "gemini"

    override suspend fun provideSecondOpinion(
        task: String,
        context: ScreenContext,
        proposal: AgentMessage
    ): AgentMessage {
        val systemPrompt = """
            Sen Lumina AI'nın Kıdemli Danışman Beyni'sin (Advisor Brain).
            Görevin, başka bir yapay zekanın önerdiği eylemi değerlendirmek ve onaylamak veya alternatif sunmaktır.
            Yanıtın sadece şu JSON formatında olmalıdır:
            {
              "agreement": true/false,
              "decisionSummary": "Değerlendirme özeti",
              "confidence": 0.0-1.0 arası sayı,
              "riskAssessment": "Güvenlik değerlendirmesi",
              "suggestedAction": "varsa alternatif eylem"
            }
        """.trimIndent()

        val userPrompt = """
            Görev: $task
            Önerilen Eylem: ${proposal.decisionSummary}
            Confidence: ${proposal.confidence}
            Ekran: ${context.packageName}/${context.activityName}
            Lütfen bu eylemi doğrula.
        """.trimIndent()

        return try {
            val response = aiProviderManager.generateStructuralContent(
                providerId = providerId,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt
            )
            val json = parseJsonObject(response)
            
            AgentMessage(
                taskId = proposal.taskId,
                sender = "GEMINI_ADVISOR",
                receiver = "ORCHESTRATOR",
                messageType = AgentMessageType.ADVICE,
                decisionSummary = json.optString("decisionSummary", "No summary"),
                confidence = json.optDouble("confidence", 0.0),
                riskLevel = parseRiskLevel(json.optString("riskAssessment", "")),
                structuredPayload = mapOf(
                    "agreement" to json.optBoolean("agreement").toString(),
                    "riskAssessment" to json.optString("riskAssessment", ""),
                    "suggestedAction" to json.optString("suggestedAction", "")
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error providing second opinion", e)
            AgentMessage(
                taskId = proposal.taskId,
                sender = "GEMINI_ADVISOR",
                receiver = "ORCHESTRATOR",
                messageType = AgentMessageType.ERROR,
                decisionSummary = "Advisor failure: ${e.message}",
                confidence = 0.0,
                errorCode = "PROVIDER_ERROR"
            )
        }
    }

    private fun parseRiskLevel(text: String): AgentRiskLevel {
        val normalized = text.lowercase()
        return when {
            normalized.contains("yüksek") || normalized.contains("kritik") || normalized.contains("high") || normalized.contains("danger") || normalized.contains("riskli") -> AgentRiskLevel.HIGH_RISK
            normalized.contains("hassas") || normalized.contains("sensitive") -> AgentRiskLevel.SENSITIVE
            else -> AgentRiskLevel.SAFE
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
            if (start >= 0 && end > start) JSONObject(cleaned.substring(start, end + 1)) else throw IllegalArgumentException("Advisor JSON parse edilemedi")
        }
    }
}
