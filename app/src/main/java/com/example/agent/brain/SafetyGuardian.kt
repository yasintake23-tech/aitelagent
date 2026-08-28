package com.example.agent.brain

import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot
import java.util.Locale

/**
 * Aksiyon risk düzeyleri.
 */
enum class ActionRiskLevel {
    /** Tamamen güvenli ve sıradan gezinme eylemleri */
    SAFE,

    /** Düşük riskli (örn. etiketi bulunmayan boş simge tıklaması veya nötr arayüz değişikliği) */
    LOW_RISK,

    /** Hassas arayüz öğeleri veya dikkat gerektiren ayarlar */
    SENSITIVE,

    /** Yüksek riskli (Satın alma, ödeme, para transferi, abonelik, kart bilgileri) */
    HIGH_RISK,

    /** Doğrudan engellenen kritik ve geri döndürülemez işlemler (Hesap silme, format, vb.) */
    BLOCKED
}

/**
 * Güvenlik değerlendirme kararı.
 */
data class SafetyDecision(
    val allowed: Boolean,
    val riskLevel: ActionRiskLevel,
    val reason: String,
    val requiresUserConfirmation: Boolean
)

/**
 * İleride Vision API veya ekran görüntüsü analizinden gelecek olan güvenlik bağlamı.
 */
data class VisionSafetyContext(
    val hasPriceInScreenshot: Boolean = false,
    val detectedFinancialTextInScreenshot: List<String> = emptyList(),
    val detectedDangerousButtonInScreenshot: Boolean = false,
    val screenshotSummary: String? = null
)

/**
 * Agent aksiyonlarının fiziksel olarak icra edilmeden önce denetlendiği deterministik Safety Guardian.
 */
object SafetyGuardian {

    // Doğrudan engellenen kritik ifadeler
    private val BLOCKED_KEYWORDS = listOf(
        "hesabı sil", "hesabımı sil", "delete account", "fabrika ayarları",
        "format at", "cihazı sıfırla", "factory reset", "şifremi değiştir",
        "reset password", "tüm verileri sil", "erase all data"
    )

    // Yüksek riskli finansal ve hassas işlemler
    private val HIGH_RISK_KEYWORDS = listOf(
        "satın al", "satın alma", "buy", "buy now",
        "ödemeye geç", "ödeme yap", "ödeme", "pay", "checkout",
        "siparişi tamamla", "sipariş ver", "order now",
        "transfer", "para gönder", "send money", "eft", "havale",
        "abone ol", "abonelik", "subscribe", "subscription", "planı yükselt",
        "kredi kartı", "kart bilgileri", "card details", "bakiye", "balance",
        "iban", "cvv", "son kullanma tarihi", "banka işlemleri", "ödeme onayı",
        "tl", "₺", "usd", "eur"
    )

    // Finansal ekran tespiti için paket ve metin anahtar kelimeleri
    private val FINANCIAL_PACKAGE_PATTERNS = listOf(
        "bank", "pay", "wallet", "checkout", "cüzdan", "shopping", "store",
        "market", "crypto", "borsa", "garanti", "isbank", "ziraat", "akbank",
        "yapikredi", "qnb", "denizbank", "papara", "tosla", "iyzico", "trendyol",
        "hepsiburada", "amazon", "getir", "yemeksepeti"
    )

    private val FINANCIAL_SCREEN_TEXTS = listOf(
        "ödeme", "sepet", "toplam tutar", "fatura", "checkout", "cart",
        "subtotal", "total", "hesap numarası", "kredi kartı", "alışverişi tamamla",
        "tl", "₺", "usd", "eur", "toplam", "fiyat", "satın alma", "sipariş", "kart", "transfer"
    )

    // Fiyat ve para birimi Regex desenleri (3.000 TL, 299,99 TL, $100, €50 vb.)
    private val PRICE_REGEX = Regex(
        """(?i)(\d{1,3}(\.\d{3})*(,\d+)?|\d+([.,]\d+)?)\s*(tl|₺|usd|eur|\$|€)"""
    )
    private val CURRENCY_REGEX = Regex(
        """(?i)(tl|₺|usd|eur|\$|€)\s*(\d{1,3}(\.\d{3})*(,\d+)?|\d+([.,]\d+)?)"""
    )

    /**
     * Genel bir aksiyonu (ActionType, TargetText, Node, Snapshot, Vision) güvenlik filtresinden geçirir.
     */
    fun evaluateAction(
        actionType: String,
        targetText: String? = null,
        node: ScreenNodeData? = null,
        snapshot: ScreenSnapshot? = null,
        visionContext: VisionSafetyContext? = null
    ): SafetyDecision {

        val combinedText = listOfNotNull(
            targetText,
            node?.text,
            node?.contentDescription,
            node?.viewId
        ).joinToString(" ").trim()

        val lowerAction = actionType.uppercase(Locale.ROOT)

        // 1. Vision context yüksek risk kontrolü
        if (visionContext != null) {
            if (visionContext.detectedDangerousButtonInScreenshot) {
                return SafetyDecision(
                    allowed = false,
                    riskLevel = ActionRiskLevel.HIGH_RISK,
                    reason = "Vision: Ekran görüntüsünde tehlikeli finansal/işlem butonu tespit edildi.",
                    requiresUserConfirmation = true
                )
            }
            if (visionContext.hasPriceInScreenshot || visionContext.detectedFinancialTextInScreenshot.isNotEmpty()) {
                if (isFinancialOrConfirmationAction(combinedText)) {
                    return SafetyDecision(
                        allowed = false,
                        riskLevel = ActionRiskLevel.HIGH_RISK,
                        reason = "Vision: Görselde fiyat veya finansal işlem tespit edildi.",
                        requiresUserConfirmation = true
                    )
                }
            }
        }

        // 2. Doğrudan Engellenen (BLOCKED) İşlemler
        val lowerText = combinedText.lowercase(Locale.ROOT)
        if (BLOCKED_KEYWORDS.any { lowerText.contains(it) }) {
            return SafetyDecision(
                allowed = false,
                riskLevel = ActionRiskLevel.BLOCKED,
                reason = "Kritik güvenlik engeli: Geri döndürülemez veya hassas hesap işlemi ($combinedText).",
                requiresUserConfirmation = true
            )
        }

        // 3. Fiyat ve Para Birimi Tespiti
        if (containsPrice(combinedText)) {
            return SafetyDecision(
                allowed = false,
                riskLevel = ActionRiskLevel.HIGH_RISK,
                reason = "Finansal risk: Fiyat / Para birimi içeren işlem ($combinedText).",
                requiresUserConfirmation = true
            )
        }

        // 4. Doğrudan Yüksek Riskli Finansal/Abonelik Kelimeleri
        if (HIGH_RISK_KEYWORDS.any { lowerText.contains(it) }) {
            return SafetyDecision(
                allowed = false,
                riskLevel = ActionRiskLevel.HIGH_RISK,
                reason = "Finansal risk: Yüksek riskli işlem anahtar kelimesi tespit edildi ($combinedText).",
                requiresUserConfirmation = true
            )
        }

        // 5. Ekran / Paket Bağlamı (Sensitive / Financial Screen Context)
        val isFinancialContext = isFinancialScreenContext(snapshot)
        if (isFinancialContext) {
            val isBlockedFinancialText = isFinancialBlockedText(combinedText)
            val isUnlabeled = (node != null && node.text.isBlank() && node.contentDescription.isBlank())
            val isCoordinate = lowerAction == "CLICK_COORD"
            
            if (isBlockedFinancialText || isUnlabeled || isCoordinate || isConfirmationOrForwardAction(combinedText) || lowerAction.contains("CLICK")) {
                return SafetyDecision(
                    allowed = false,
                    riskLevel = ActionRiskLevel.HIGH_RISK,
                    reason = "Finansal ekran bağlamında hassas işlem, etiketsiz, onay butonu veya koordinat tıklaması engellendi ($combinedText).",
                    requiresUserConfirmation = true
                )
            }
        }

        // 6. Boş Metinli (Icon / Unlabeled) Düğüm Kontrolü
        if (node != null && node.text.isBlank() && node.contentDescription.isBlank()) {
            return SafetyDecision(
                allowed = true,
                riskLevel = ActionRiskLevel.LOW_RISK,
                reason = "Etiketsiz/boş metinli simge tıklaması. Otomatik güvenli kabul edilmedi (LOW_RISK).",
                requiresUserConfirmation = false
            )
        }

        // 7. Normal Kaydırma (Swipe)
        if (lowerAction.contains("SWIPE")) {
            return SafetyDecision(
                allowed = true,
                riskLevel = ActionRiskLevel.SAFE,
                reason = "Normal kaydırma eylemi.",
                requiresUserConfirmation = false
            )
        }

        // 8. Standart Güvenli Eylemler
        return SafetyDecision(
            allowed = true,
            riskLevel = ActionRiskLevel.SAFE,
            reason = "Güvenli işlem.",
            requiresUserConfirmation = false
        )
    }

    /**
     * Tekil metni değerlendirir.
     */
    fun evaluateText(
        text: String,
        snapshot: ScreenSnapshot? = null,
        visionContext: VisionSafetyContext? = null
    ): SafetyDecision {
        return evaluateAction(
            actionType = "CLICK",
            targetText = text,
            node = null,
            snapshot = snapshot,
            visionContext = visionContext
        )
    }

    /**
     * Düğüm nesnesini değerlendirir.
     */
    fun evaluateNode(
        node: ScreenNodeData,
        snapshot: ScreenSnapshot? = null,
        visionContext: VisionSafetyContext? = null
    ): SafetyDecision {
        return evaluateAction(
            actionType = "CLICK",
            targetText = node.text.ifBlank { node.contentDescription },
            node = node,
            snapshot = snapshot,
            visionContext = visionContext
        )
    }

    /**
     * Koordinatı değerlendirir.
     */
    fun evaluateCoordinates(
        x: Int,
        y: Int,
        snapshot: ScreenSnapshot? = null,
        visionContext: VisionSafetyContext? = null
    ): SafetyDecision {
        val matchingNode = snapshot?.clickableNodes?.firstOrNull {
            it.bounds.contains(x, y)
        }
        return evaluateAction(
            actionType = "CLICK_COORD",
            targetText = matchingNode?.text ?: "Coord($x,$y)",
            node = matchingNode,
            snapshot = snapshot,
            visionContext = visionContext
        )
    }

    private fun containsPrice(text: String): Boolean {
        if (text.isBlank()) return false
        return PRICE_REGEX.containsMatchIn(text) || CURRENCY_REGEX.containsMatchIn(text)
    }

    private fun isFinancialScreenContext(snapshot: ScreenSnapshot?): Boolean {
        if (snapshot == null) return false
        val lowerPkg = snapshot.packageName.lowercase(Locale.ROOT)
        val lowerAct = snapshot.activityName.lowercase(Locale.ROOT)

        if (FINANCIAL_PACKAGE_PATTERNS.any { lowerPkg.contains(it) || lowerAct.contains(it) }) {
            return true
        }

        val allTextsJoined = snapshot.texts.joinToString(" ").lowercase(Locale.ROOT)
        if (FINANCIAL_SCREEN_TEXTS.any { allTextsJoined.contains(it) }) {
            return true
        }

        if (containsPrice(allTextsJoined)) {
            return true
        }

        return false
    }

    private fun isConfirmationOrForwardAction(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val confirmKeywords = listOf("devam", "ilerle", "onayla", "tamamla", "evet", "öde", "satın al", "ok", "confirm", "continue", "next", "proceed", "tamam")
        return confirmKeywords.any { lower.contains(it) }
    }

    private fun isFinancialBlockedText(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT).trim()
        val blocked = listOf("devam", "onayla", "ilerle", "tamam")
        return blocked.any { lower == it || lower.contains(it) }
    }

    private fun isFinancialOrConfirmationAction(text: String): Boolean {
        return isConfirmationOrForwardAction(text) || containsPrice(text)
    }
}
