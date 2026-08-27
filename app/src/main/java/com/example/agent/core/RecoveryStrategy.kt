package com.example.agent.core

import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot

/**
 * Recovery işlemi türleri.
 */
enum class RecoveryActionType {
    RETRY_WITH_JITTER,       // Koordinatı hafif kaydırarak (offset jitter) tekrar tıkla
    SWIPE_TO_UNBLOCK,        // Sayfayı kaydır (element ekran dışında veya tıkanmış olabilir)
    PRESS_BACK_AND_RETRY,    // Geri gel ve adımı tekrar dene
    REPLAN_REQUIRED,         // AI modelinden yeni bir plan/adım iste (Re-plan)
    ABORT_TASK               // Görevi durdur/başarısız say (Sonsuz döngüyü engelle)
}

/**
 * Belirli bir başarısız eylem sonrası kurtarma tavsiyesini temsil eden veri modeli.
 */
data class RecoveryPlan(
    val actionType: RecoveryActionType,
    val explanation: String,
    val attemptCount: Int,
    val consecutiveFailures: Int,
    val shouldAbort: Boolean = false,
    val suggestedOffsetX: Float = 0f,
    val suggestedOffsetY: Float = 0f
)

/**
 * Bir eylemin başarısız veya değişimsiz kalması durumunda (VerificationResult sonrasında)
 * alternatif kurtarma stratejilerini belirleyen ve sonsuz döngüleri engelleyen motor.
 */
object RecoveryStrategy {

    /**
     * Eylem doğrulama sonucuna, anlık deneme sayısına ve bütçeye göre bir kurtarma planı üretir.
     */
    fun evaluateRecovery(
        verificationResult: VerificationResult,
        attemptCount: Int,
        consecutiveFailures: Int,
        budget: TaskBudget = TaskBudget(),
        targetNode: ScreenNodeData? = null
    ): RecoveryPlan {
        val nextConsecutive = consecutiveFailures + 1

        // 1. Sonsuz döngü ve maksimum başarısızlık kontrolü
        if (nextConsecutive >= budget.maxConsecutiveFailures || attemptCount >= budget.maxRetriesPerStep) {
            return RecoveryPlan(
                actionType = RecoveryActionType.ABORT_TASK,
                explanation = "Maksimum deneme sınırına ($attemptCount/${budget.maxRetriesPerStep}) veya ardışık başarısızlık limitine ($nextConsecutive/${budget.maxConsecutiveFailures}) ulaşıldı. Görev güvenli şekilde durduruluyor.",
                attemptCount = attemptCount,
                consecutiveFailures = nextConsecutive,
                shouldAbort = true
            )
        }

        // 2. VerificationStatus'a göre kademeli recovery stratejisi
        return when (verificationResult.status) {
            VerificationStatus.UNCHANGED -> {
                when (attemptCount) {
                    1 -> {
                        // 1. Deneme: Tıklama noktasına hafif sapma (jitter) uygula (+15px)
                        RecoveryPlan(
                            actionType = RecoveryActionType.RETRY_WITH_JITTER,
                            explanation = "Ekran tepki vermedi. Tıklama koordinatı hafifçe kaydırılarak tekrar deneniyor.",
                            attemptCount = attemptCount,
                            consecutiveFailures = nextConsecutive,
                            suggestedOffsetX = 15f,
                            suggestedOffsetY = 15f
                        )
                    }
                    2 -> {
                        // 2. Deneme: Tıklama noktasına aksi yönde sapma uygula (-18px) veya swipe yap
                        RecoveryPlan(
                            actionType = RecoveryActionType.SWIPE_TO_UNBLOCK,
                            explanation = "Bileşen tıklanamadı veya kısmen görünmez durumda. Ekran hafifçe kaydırılıyor.",
                            attemptCount = attemptCount,
                            consecutiveFailures = nextConsecutive
                        )
                    }
                    else -> {
                        // 3. Deneme: Re-plan iste
                        RecoveryPlan(
                            actionType = RecoveryActionType.REPLAN_REQUIRED,
                            explanation = "Bileşen tepki vermedi. Alternatif adımlar için AI modelinden re-plan isteniyor.",
                            attemptCount = attemptCount,
                            consecutiveFailures = nextConsecutive
                        )
                    }
                }
            }

            VerificationStatus.FAILED -> {
                if (attemptCount == 1) {
                    RecoveryPlan(
                        actionType = RecoveryActionType.PRESS_BACK_AND_RETRY,
                        explanation = "Eylem beklenen sonucu vermedi. Geri tuşuna basılarak önceki duruma dönülüyor.",
                        attemptCount = attemptCount,
                        consecutiveFailures = nextConsecutive
                    )
                } else {
                    RecoveryPlan(
                        actionType = RecoveryActionType.REPLAN_REQUIRED,
                        explanation = "Eylem başarısız oldu. Re-plan isteniyor.",
                        attemptCount = attemptCount,
                        consecutiveFailures = nextConsecutive
                    )
                }
            }

            VerificationStatus.NO_OBSERVATION, VerificationStatus.TIMEOUT -> {
                RecoveryPlan(
                    actionType = RecoveryActionType.REPLAN_REQUIRED,
                    explanation = "Eylem sonrası ekran gözlemi alınamadı veya zaman aşımı oluştu. Re-plan gerekiyor.",
                    attemptCount = attemptCount,
                    consecutiveFailures = nextConsecutive
                )
            }

            VerificationStatus.VERIFIED -> {
                // Başarılı durumda recovery gerekmez
                RecoveryPlan(
                    actionType = RecoveryActionType.RETRY_WITH_JITTER,
                    explanation = "Eylem başarıyla doğrulandı.",
                    attemptCount = 0,
                    consecutiveFailures = 0
                )
            }
        }
    }
}
