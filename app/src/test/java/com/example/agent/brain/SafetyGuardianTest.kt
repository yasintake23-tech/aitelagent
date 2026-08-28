package com.example.agent.brain

import android.graphics.Rect
import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SafetyGuardianTest {

    private fun createDummyNode(
        text: String = "",
        contentDescription: String = "",
        packageName: String = "com.example.app"
    ): ScreenNodeData {
        return ScreenNodeData(
            text = text,
            contentDescription = contentDescription,
            viewId = "id/btn",
            bounds = Rect(0, 0, 100, 100),
            isClickable = true,
            isScrollable = false,
            className = "android.widget.Button",
            packageName = packageName
        )
    }

    private fun createDummySnapshot(
        packageName: String = "com.example.app",
        activityName: String = "MainActivity",
        texts: List<String> = emptyList()
    ): ScreenSnapshot {
        return ScreenSnapshot(
            packageName = packageName,
            activityName = activityName,
            nodeCount = 10,
            texts = texts,
            clickableNodes = emptyList()
        )
    }

    @Test
    fun testPriceDetectionHighRisk() {
        val decision = SafetyGuardian.evaluateText("3.000 TL")
        assertEquals(ActionRiskLevel.HIGH_RISK, decision.riskLevel)
        assertFalse(decision.allowed)
        assertTrue(decision.requiresUserConfirmation)
    }

    @Test
    fun testSatinAlHighRisk() {
        val decision = SafetyGuardian.evaluateText("Satın Al")
        assertEquals(ActionRiskLevel.HIGH_RISK, decision.riskLevel)
        assertFalse(decision.allowed)
    }

    @Test
    fun testOdemeyeGecHighRisk() {
        val decision = SafetyGuardian.evaluateText("Ödemeye Geç")
        assertEquals(ActionRiskLevel.HIGH_RISK, decision.riskLevel)
        assertFalse(decision.allowed)
    }

    @Test
    fun testKartBilgileriHighRisk() {
        val decision = SafetyGuardian.evaluateText("Kart bilgileri")
        assertEquals(ActionRiskLevel.HIGH_RISK, decision.riskLevel)
        assertFalse(decision.allowed)
    }

    @Test
    fun testTransferHighRisk() {
        val decision = SafetyGuardian.evaluateText("Transfer")
        assertEquals(ActionRiskLevel.HIGH_RISK, decision.riskLevel)
        assertFalse(decision.allowed)
    }

    @Test
    fun testHesabiSilBlocked() {
        val decision = SafetyGuardian.evaluateText("Hesabı Sil")
        assertEquals(ActionRiskLevel.BLOCKED, decision.riskLevel)
        assertFalse(decision.allowed)
        assertTrue(decision.requiresUserConfirmation)
    }

    @Test
    fun testAyarlarSafe() {
        val decision = SafetyGuardian.evaluateText("Ayarlar")
        assertEquals(ActionRiskLevel.SAFE, decision.riskLevel)
        assertTrue(decision.allowed)
    }

    @Test
    fun testWhatsAppSafe() {
        val decision = SafetyGuardian.evaluateText("WhatsApp")
        assertEquals(ActionRiskLevel.SAFE, decision.riskLevel)
        assertTrue(decision.allowed)
    }

    @Test
    fun testAramaSafe() {
        val decision = SafetyGuardian.evaluateText("Arama")
        assertEquals(ActionRiskLevel.SAFE, decision.riskLevel)
        assertTrue(decision.allowed)
    }

    @Test
    fun testEmptyTextNodeNotAutomaticallySafe() {
        val emptyNode = createDummyNode(text = "", contentDescription = "")
        val decision = SafetyGuardian.evaluateNode(emptyNode)

        // Text is empty, so it must NOT be SAFE
        assertTrue(decision.riskLevel != ActionRiskLevel.SAFE)
        assertEquals(ActionRiskLevel.LOW_RISK, decision.riskLevel)
    }

    @Test
    fun testFinancialPackageInnocentButtonHighRisk() {
        val financialSnapshot = createDummySnapshot(
            packageName = "com.garanti.cepsubesi",
            activityName = "PaymentActivity",
            texts = listOf("Ödeme Ekranı", "Tutar: 500 TL")
        )

        // "Devam" button on a financial screen context must be HIGH_RISK
        val decision = SafetyGuardian.evaluateText("Devam", snapshot = financialSnapshot)
        assertEquals(ActionRiskLevel.HIGH_RISK, decision.riskLevel)
        assertFalse(decision.allowed)
    }

    @Test
    fun testNormalSwipeSafe() {
        val decision = SafetyGuardian.evaluateAction(actionType = "SWIPE_DOWN")
        assertEquals(ActionRiskLevel.SAFE, decision.riskLevel)
        assertTrue(decision.allowed)
    }
}
