package com.example.agent.multibrain.adapters

import android.util.Log
import com.example.agent.multibrain.AgentMessage
import com.example.agent.multibrain.AgentMessageType
import com.example.agent.multibrain.AgentRiskLevel
import com.example.agent.multibrain.ScreenContext
import com.example.agent.multibrain.VisionBrain
import com.example.ai.AIProviderManager
import org.json.JSONObject

class HuggingFaceVisionBrainAdapter(
    private val aiProviderManager: AIProviderManager
) : VisionBrain {

    private val tag = "HFVisionAdapter"
    private val providerId = "huggingface"

    override suspend fun analyzeScreen(
        context: ScreenContext,
        targetDescription: String?
    ): AgentMessage {
        val screenshot = context.screenshot ?: return AgentMessage(
            taskId = "UNKNOWN_TASK",
            sender = "HF_VISION",
            receiver = "ORCHESTRATOR",
            messageType = AgentMessageType.ERROR,
            decisionSummary = "Ekran görüntüsü bulunamadı.",
            confidence = 0.0,
            errorCode = "NO_SCREENSHOT"
        )

        val target = targetDescription?.takeIf { it.isNotBlank() } ?: "ekrandaki güvenli hedef"
        val prompt = """
            Bu Android ekran görüntüsünü görsel olarak analiz et.
            Hedef: "$target"
            Yalnızca SAF JSON döndür:
            {
              "found": true,
              "target": "kısa hedef adı",
              "centerX": 0,
              "centerY": 0,
              "confidence": 0.0,
              "summary": "kısa açıklama"
            }
            Hedef görünmüyorsa found=false, centerX/centerY=null ver.
            Koordinatlar ekranın gerçek piksel koordinatlarıdır ve SADECE aday grounding bilgisidir; fiziksel tıklama yapma.
        """.trimIndent()

        return try {
            val response = aiProviderManager.generateVisionContent(
                providerId = providerId,
                prompt = prompt,
                bitmap = screenshot
            )
            val json = parseJsonObject(response)
            val found = json.optBoolean("found", false)
            val confidence = json.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            val x = if (json.has("centerX") && !json.isNull("centerX")) json.optInt("centerX") else -1
            val y = if (json.has("centerY") && !json.isNull("centerY")) json.optInt("centerY") else -1
            val summary = json.optString("summary", if (found) "Hedef bulundu." else "Hedef bulunamadı.")
            AgentMessage(
                taskId = "UNKNOWN_TASK",
                sender = "HF_VISION",
                receiver = "ORCHESTRATOR",
                messageType = AgentMessageType.OBSERVATION,
                decisionSummary = summary.take(500),
                confidence = confidence,
                riskLevel = AgentRiskLevel.SAFE,
                structuredPayload = buildMap {
                    put("found", found.toString())
                    put("target", json.optString("target", target).take(200))
                    put("candidateX", x.toString())
                    put("candidateY", y.toString())
                }
            )
        } catch (e: Exception) {
            Log.e(tag, "Vision analysis failed", e)
            AgentMessage(
                taskId = "UNKNOWN_TASK",
                sender = "HF_VISION",
                receiver = "ORCHESTRATOR",
                messageType = AgentMessageType.ERROR,
                decisionSummary = "Vision analizi başarısız oldu.",
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
            else throw IllegalArgumentException("Vision JSON parse edilemedi")
        }
    }
}
