package com.example.agent.brain

import com.example.agent.core.UserIntent

/**
 * Güvenlik seviyesi sınıflandırması.
 */
enum class SafetyLevel {
    /** Standart cihaz işlemleri ve gezinme */
    NORMAL,

    /** Finansal / Ödeme veya satın alma sinyalleri içeren hassas işlemler */
    STRICT_FINANCIAL,

    /** Sistem ayarları, izinler veya silme gibi yüksek riskli işlemler */
    HIGH_RISK
}

/**
 * Kullanıcı hedefini yapılandırılmış görev bileşenlerine dönüştüren veri modeli.
 */
data class TaskSpec(
    val originalGoal: String,
    val intentType: UserIntent = UserIntent.DEVICE_TASK,
    val targetApp: String? = null,
    val targetEntity: String? = null,
    val requestedAction: String? = null,
    val payloadText: String? = null,
    val constraints: List<String> = emptyList(),
    val safetyLevel: SafetyLevel = SafetyLevel.NORMAL,
    val completionCriteria: String? = null
)
