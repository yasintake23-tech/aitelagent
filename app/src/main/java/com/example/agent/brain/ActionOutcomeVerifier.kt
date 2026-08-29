package com.example.agent.brain

import com.example.service.ScreenSnapshot

/**
 * Bir aksiyon gerçekleştirildikten sonra beklenen durumun oluşup oluşmadığını doğrular.
 */
data class VerificationResult(
    val isVerified: Boolean,
    val reason: String
)

object ActionOutcomeVerifier {

    /**
     * Eylem öncesi ve sonrası durumları karşılaştırarak beklenen sonucun gerçekleştiğini doğrular.
     */
    fun verifyOutcome(
        beforeSnapshot: ScreenSnapshot?,
        afterSnapshot: ScreenSnapshot?,
        expectedOutcome: ExpectedOutcomeSpec?,
        actionType: String? = null
    ): VerificationResult {
        if (expectedOutcome == null) {
            return VerificationResult(true, "Beklenen durum belirtilmemiş. Doğrulandı kabul ediliyor.")
        }

        if (afterSnapshot == null) {
            return VerificationResult(false, "Eylem sonrası ekran görüntüsü alınamadı.")
        }

        // 1. Ekran değişikliği beklentisi
        if (expectedOutcome.screenChangeExpected && beforeSnapshot != null) {
            val samePackage = beforeSnapshot.packageName == afterSnapshot.packageName
            val sameActivity = beforeSnapshot.activityName == afterSnapshot.activityName
            val sameTexts = beforeSnapshot.texts == afterSnapshot.texts
            
            // Allow actions that might not visibly change everything but still succeed
            val isActionTolerated = actionType == AgentActionType.TYPE_TEXT ||
                                    actionType == AgentActionType.SWIPE_UP ||
                                    actionType == AgentActionType.SWIPE_DOWN ||
                                    actionType == AgentActionType.SWIPE_LEFT ||
                                    actionType == AgentActionType.SWIPE_RIGHT ||
                                    actionType == AgentActionType.CLICK_NODE ||
                                    actionType == AgentActionType.CLICK_COORD

            if (samePackage && sameActivity && sameTexts && !isActionTolerated) {
                return VerificationResult(false, "Ekran değişikliği beklendi ancak ekran durumu değişmedi.")
            }
        }

        // 2. Paket adı kontrolü
        if (!expectedOutcome.expectedPackage.isNull_or_blank_and_null_str()) {
            val targetPkg = expectedOutcome.expectedPackage!!.lowercase()
            val currentPkg = afterSnapshot.packageName.lowercase()
            if (!currentPkg.contains(targetPkg) && !targetPkg.contains(currentPkg)) {
                return VerificationResult(
                    false,
                    "Hedef paket '$targetPkg' bekleniyordu fakat mevcut paket '$currentPkg'."
                )
            }
        }

        // 3. Beklenen metinlerin kontrolü
        if (expectedOutcome.expectedText.isNotEmpty()) {
            val allTextsJoined = (afterSnapshot.texts + afterSnapshot.clickableNodes.map { it.text })
                .joinToString(" ").lowercase()

            val missingTexts = expectedOutcome.expectedText.filter { exp ->
                !allTextsJoined.contains(exp.lowercase())
            }

            if (missingTexts.isNotEmpty()) {
                return VerificationResult(
                    false,
                    "Eylem sonrası ekranda beklenen metinler bulunamadı: ${missingTexts.joinToString(", ")}"
                )
            }
        }

        return VerificationResult(true, "Beklenen durum başarıyla doğrulandı.")
    }

    private fun String?.isNull_or_blank_and_null_str(): Boolean {
        if (this == null) return true
        val trimmed = this.trim()
        return trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)
    }
}
