package com.example.agent.core

import android.graphics.Bitmap
import com.example.ai.VisualGroundingEngine
import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot

/**
 * Action doğrulama sonuç durumları.
 */
enum class VerificationStatus {
    VERIFIED,
    FAILED,
    UNCHANGED,
    TIMEOUT,
    NO_OBSERVATION
}

/**
 * Bir eylemin yürütüldükten sonra doğrulama sonucunu taşıyan veri modeli.
 */
data class VerificationResult(
    val status: VerificationStatus,
    val isSuccess: Boolean,
    val reason: String,
    val details: String = "",
    val packageChanged: Boolean = false,
    val nodeStateChanged: Boolean = false,
    val screenStructureChanged: Boolean = false,
    val similarityScore: Float? = null
) {
    companion object {
        fun verified(reason: String, details: String = "", packageChanged: Boolean = false, nodeStateChanged: Boolean = false, screenStructureChanged: Boolean = false, similarityScore: Float? = null) =
            VerificationResult(
                status = VerificationStatus.VERIFIED,
                isSuccess = true,
                reason = reason,
                details = details,
                packageChanged = packageChanged,
                nodeStateChanged = nodeStateChanged,
                screenStructureChanged = screenStructureChanged,
                similarityScore = similarityScore
            )

        fun unchanged(reason: String = "Ekran veya hedef bileşen durumunda hiçbir değişiklik algılanmadı", similarityScore: Float? = null) =
            VerificationResult(
                status = VerificationStatus.UNCHANGED,
                isSuccess = false,
                reason = reason,
                similarityScore = similarityScore
            )

        fun failed(reason: String, details: String = "") =
            VerificationResult(
                status = VerificationStatus.FAILED,
                isSuccess = false,
                reason = reason,
                details = details
            )

        fun noObservation(reason: String = "Eylem sonrası ekran gözlemi alınamadı") =
            VerificationResult(
                status = VerificationStatus.NO_OBSERVATION,
                isSuccess = false,
                reason = reason
            )
    }
}

/**
 * Eylemlerin (Tıklama, Metin Yazma, Kaydırma, Uygulama Açma vb.) 
 * beklenen sonuçları oluşturup oluşturmadığını kademeli olarak değerlendiren doğrulama motoru.
 * Eylemin kendisini yürütmez; eylem öncesi ve sonrası gözlemleri (ScreenSnapshot/Bitmap) karşılaştırır.
 */
object ActionVerifier {

    /**
     * Tıklama (CLICK) eyleminin başarısını kademeli olarak doğrular.
     * 1. Uygulama/Paket değişti mi?
     * 2. Hedef node durumu veya varlığı değişti mi?
     * 3. Ekran düğüm yapısı değişti mi?
     * 4. Görsel (Bitmap) benzerliği düştü mü? (Ekran değişti mi?)
     */
    fun verifyClickOutcome(
        beforeSnapshot: ScreenSnapshot?,
        afterSnapshot: ScreenSnapshot?,
        targetNode: ScreenNodeData? = null,
        beforeBitmap: Bitmap? = null,
        afterBitmap: Bitmap? = null
    ): VerificationResult {
        if (afterSnapshot == null) {
            return VerificationResult.noObservation("Eylem sonrası ekran snapshot'ı alınamadı.")
        }

        val beforePkg = beforeSnapshot?.packageName ?: ""
        val afterPkg = afterSnapshot.packageName

        // 1. Paket / Uygulama Değişimi Kontrolü
        if (beforePkg.isNotBlank() && afterPkg.isNotBlank() && beforePkg != afterPkg) {
            return VerificationResult.verified(
                reason = "Uygulama/Paket değişti: $beforePkg -> $afterPkg",
                packageChanged = true
            )
        }

        // 2. Hedef Node / İlgili Düğüm Durumu Kontrolü
        if (targetNode != null && beforeSnapshot != null) {
            val isTargetStillVisible = afterSnapshot.clickableNodes.any { node ->
                node.bounds == targetNode.bounds && (node.text == targetNode.text || node.contentDescription == targetNode.contentDescription)
            }
            if (!isTargetStillVisible) {
                return VerificationResult.verified(
                    reason = "Hedef düğüm ekrandan kayboldu veya yeni ekrana geçildi.",
                    nodeStateChanged = true
                )
            }
        }

        // 3. Ekran Düğüm Yapısı & İçerik Değişimi Kontrolü
        if (beforeSnapshot != null) {
            val nodeCountDiff = Math.abs(beforeSnapshot.nodeCount - afterSnapshot.nodeCount)
            val clickableCountDiff = Math.abs(beforeSnapshot.clickableNodes.size - afterSnapshot.clickableNodes.size)
            val textsChanged = beforeSnapshot.texts != afterSnapshot.texts

            if (nodeCountDiff >= 2 || clickableCountDiff >= 2 || textsChanged) {
                return VerificationResult.verified(
                    reason = "Ekran hiyerarşisi ve tıklanabilir bileşenler değişti.",
                    screenStructureChanged = true,
                    details = "Node farkı: $nodeCountDiff, Tıklanabilir öğe farkı: $clickableCountDiff"
                )
            }
        }

        // 4. Görsel Bitmap Benzerliği Kontrolü (Varsa)
        if (beforeBitmap != null && afterBitmap != null) {
            val similarity = VisualGroundingEngine.computeScreenSimilarity(beforeBitmap, afterBitmap)
            if (similarity < 0.92f) {
                return VerificationResult.verified(
                    reason = "Görsel ekran değişimi algılandı (Benzerlik: ${(similarity * 100).toInt()}%)",
                    similarityScore = similarity
                )
            } else {
                return VerificationResult.unchanged(
                    reason = "Görsel ekran değişmedi (Benzerlik: ${(similarity * 100).toInt()}%)",
                    similarityScore = similarity
                )
            }
        }

        return VerificationResult.unchanged("Ekran hiyerarşisi ve durumunda belirgin bir değişim algılanamadı.")
    }

    /**
     * Metin Yazma (TYPE_TEXT) eyleminin doğrulaması.
     */
    fun verifyTextOutcome(
        beforeSnapshot: ScreenSnapshot?,
        afterSnapshot: ScreenSnapshot?,
        typedText: String
    ): VerificationResult {
        if (afterSnapshot == null) {
            return VerificationResult.noObservation()
        }

        // Düzenlenebilir düğümlerde yazılan metin görünüyor mu?
        val textVisibleInEditable = afterSnapshot.editableNodes.any { node ->
            node.text.contains(typedText, ignoreCase = true) || typedText.contains(node.text, ignoreCase = true)
        }
        val textVisibleInAnyNode = afterSnapshot.clickableNodes.any { node ->
            node.text.contains(typedText, ignoreCase = true)
        } || afterSnapshot.texts.any { txt -> txt.contains(typedText, ignoreCase = true) }

        if (textVisibleInEditable || textVisibleInAnyNode) {
            return VerificationResult.verified(
                reason = "Yazılan metin ekranda veya düzenlenebilir alanda doğrulandı: '$typedText'",
                nodeStateChanged = true
            )
        }

        return VerificationResult.unchanged("Yazılan metin ekrandaki düğümlerde tespit edilemedi.")
    }

    /**
     * Kaydırma (SWIPE / SCROLL) eyleminin doğrulaması.
     */
    fun verifyScrollOutcome(
        beforeSnapshot: ScreenSnapshot?,
        afterSnapshot: ScreenSnapshot?
    ): VerificationResult {
        if (beforeSnapshot == null || afterSnapshot == null) {
            return VerificationResult.noObservation()
        }

        val beforeFirstNodes = beforeSnapshot.clickableNodes.take(5).map { it.text + it.contentDescription }
        val afterFirstNodes = afterSnapshot.clickableNodes.take(5).map { it.text + it.contentDescription }

        if (beforeFirstNodes != afterFirstNodes || beforeSnapshot.texts != afterSnapshot.texts) {
            return VerificationResult.verified(
                reason = "Kaydırma sonrası ekrandaki görünür bileşenler değişti.",
                screenStructureChanged = true
            )
        }

        return VerificationResult.unchanged("Kaydırma sonrası ekran içeriği değişmedi.")
    }

    /**
     * Uygulama Açma (LAUNCH_APP) eyleminin doğrulaması.
     */
    fun verifyAppLaunchOutcome(
        expectedAppName: String,
        currentPackage: String,
        afterSnapshot: ScreenSnapshot?
    ): VerificationResult {
        if (afterSnapshot == null) {
            return VerificationResult.noObservation()
        }

        val pkgLower = currentPackage.lowercase()
        val appLower = expectedAppName.lowercase()

        if (pkgLower.contains(appLower) || afterSnapshot.packageName.lowercase().contains(appLower)) {
            return VerificationResult.verified(
                reason = "Hedef uygulama başlatıldı: ${afterSnapshot.packageName}",
                packageChanged = true
            )
        }

        return VerificationResult.failed("Hedef uygulama açılması doğrulanamadı. Mevcut paket: $currentPackage")
    }
}
