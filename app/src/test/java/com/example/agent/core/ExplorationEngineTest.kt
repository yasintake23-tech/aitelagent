package com.example.agent.core

import android.graphics.Rect
import com.example.ai.AgentActionType
import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Aşama 3: Structured Exploration Engine birim testleri.
 * Visited screen takibi, progress, stuck detection, safety filtering, bütçe ve determinizm doğrulamaları.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExplorationEngineTest {

    private fun createDummyNode(
        text: String,
        desc: String = "",
        viewId: String = "com.android.settings:id/title",
        isClickable: Boolean = true
    ): ScreenNodeData {
        return ScreenNodeData(
            text = text,
            contentDescription = desc,
            viewId = viewId,
            bounds = Rect(100, 200, 300, 400),
            isClickable = isClickable,
            isScrollable = false,
            isEditable = false,
            className = "android.widget.TextView",
            packageName = "com.android.settings"
        )
    }

    private fun createSnapshot(
        packageName: String = "com.android.settings",
        activityName: String = "com.android.settings.SettingsActivity",
        nodes: List<ScreenNodeData> = listOf(createDummyNode("Ekran"), createDummyNode("Ses"))
    ): ScreenSnapshot {
        return ScreenSnapshot(
            packageName = packageName,
            activityName = activityName,
            nodeCount = nodes.size,
            texts = nodes.map { it.text },
            clickableNodes = nodes,
            editableNodes = emptyList(),
            timestamp = System.currentTimeMillis()
        )
    }

    // 1. Visited Screen Takibi ve İlerleme Testi
    @Test
    fun testVisitedScreenTrackingAndProgress() {
        val session = ExplorationTaskSession()
        val snapshot1 = createSnapshot(nodes = listOf(createDummyNode("Ekran")))
        val fp1 = ScreenFingerprintGenerator.generateFingerprint(snapshot1)

        val isNew1 = session.onScreenObserved(fp1, snapshot1.packageName)
        assertTrue("İlk gözlem yeni ekran olarak işaretlenmeli", isNew1)
        assertEquals(1, session.visitedScreens.size)
        assertEquals(1, session.progress.discoveredScreensCount)
        assertEquals(1, session.progress.discoveredPackagesCount)

        // Aynı fingerprint tekrar gözlemlendiğinde
        val isNew2 = session.onScreenObserved(fp1, snapshot1.packageName)
        assertFalse("Aynı fingerprint tekrar gözlemlendiğinde false dönmeli", isNew2)
        assertEquals(1, session.visitedScreens.size)
        assertEquals(1, session.progress.discoveredScreensCount)

        // Farklı bir ekran gözlemlendiğinde
        val snapshot2 = createSnapshot(
            packageName = "com.google.android.calculator",
            activityName = "com.android.calculator2.Calculator",
            nodes = listOf(createDummyNode("7", viewId = "com.android.calculator2:id/digit_7"))
        )
        val fp2 = ScreenFingerprintGenerator.generateFingerprint(snapshot2)
        val isNew3 = session.onScreenObserved(fp2, snapshot2.packageName)

        assertTrue("Farklı ekran yeni olarak işaretlenmeli", isNew3)
        assertEquals(2, session.visitedScreens.size)
        assertEquals(2, session.progress.discoveredScreensCount)
        assertEquals(2, session.progress.discoveredPackagesCount)
    }

    // 2. Action History Takibi Testi
    @Test
    fun testActionHistoryTracking() {
        val session = ExplorationTaskSession()
        val fp1 = "fp_screen_settings"
        val fp2 = "fp_screen_display"

        session.recordActionOutcome(
            stepNumber = 1,
            fromFingerprint = fp1,
            actionType = AgentActionType.CLICK_NODE,
            targetDescription = "Ekran",
            thought = "Ekran ayarlarına giriliyor",
            toFingerprint = fp2,
            diffType = ScreenDifferenceType.NEW_STATE,
            isSuccess = true
        )

        assertEquals(1, session.actionHistory.size)
        val record = session.actionHistory.first()
        assertEquals(1, record.stepNumber)
        assertEquals(fp1, record.fromFingerprint)
        assertEquals(fp2, record.toFingerprint)
        assertEquals(AgentActionType.CLICK_NODE, record.actionType)
        assertEquals(ScreenDifferenceType.NEW_STATE, record.differenceType)
        assertTrue(record.isSuccess)
        assertEquals(1, session.progress.successfulActionsCount)
    }

    // 3. Güvenlik Filtresi Testi (Zararlı butonları engelleme)
    @Test
    fun testExplorationSafetyFiltering() {
        assertTrue(ExplorationSafety.isSafeText("Ekran"))
        assertTrue(ExplorationSafety.isSafeText("Ses ve Bildirimler"))
        assertTrue(ExplorationSafety.isSafeText("Karanlık Tema"))
        assertTrue(ExplorationSafety.isSafeText("Wi-Fi"))

        // Zararlı / Riskli kelimeler reddedilmeli
        assertFalse(ExplorationSafety.isSafeText("Tüm verileri sil"))
        assertFalse(ExplorationSafety.isSafeText("Fabrika ayarlarına sıfırla"))
        assertFalse(ExplorationSafety.isSafeText("Satın Al ve Öde"))
        assertFalse(ExplorationSafety.isSafeText("Uygulamayı kaldır"))
        assertFalse(ExplorationSafety.isSafeText("Hesabı kapat"))
        assertFalse(ExplorationSafety.isSafeText("Kart bilgisi ekle"))

        val safeNode = createDummyNode("Ekran Ayarları")
        assertTrue(ExplorationSafety.isSafeNode(safeNode))

        val dangerousNode = createDummyNode("Tümünü Sil", viewId = "com.example:id/btn_delete_all")
        assertFalse(ExplorationSafety.isSafeNode(dangerousNode))
    }

    // 4. Stuck Detection ve Kurtarma Testi
    @Test
    fun testStuckDetectionAndAlternativeActionSelection() = runBlocking {
        val session = ExplorationTaskSession()
        val node1 = createDummyNode("Ekran", viewId = "id/btn_display")
        val node2 = createDummyNode("Ses", viewId = "id/btn_sound")
        val snapshot = createSnapshot(nodes = listOf(node1, node2))

        // 1. Adım: İlk düğüm seçilir
        val decision1 = ExplorationDecisionMaker.decideNextExplorationAction(snapshot, session, reasoner = null)
        assertEquals(AgentActionType.CLICK_NODE, decision1.actionType)
        assertEquals("Ekran", decision1.targetText)
        assertTrue(session.visitedNodeSignatures.isNotEmpty())

        // 2. Adım: Stuck durumu simüle edelim (consecutiveSameStateCount = 2)
        session.consecutiveSameStateCount = 2
        val decision2 = ExplorationDecisionMaker.decideNextExplorationAction(snapshot, session, reasoner = null)

        // Daha önce denenmiş olan "Ekran" yerine denenmemiş olan "Ses" seçilmeli
        assertEquals(AgentActionType.CLICK_NODE, decision2.actionType)
        assertEquals("Ses", decision2.targetText)

        // 3. Adım: Tüm düğümler denendikten sonra stuck kalırsa
        session.consecutiveSameStateCount = 3
        val decision3 = ExplorationDecisionMaker.decideNextExplorationAction(snapshot, session, reasoner = null)
        // Tüm düğümler ziyaret edildiğinde kaydırma (SWIPE_DOWN) seçilmeli
        assertEquals(AgentActionType.SWIPE_DOWN, decision3.actionType)
    }

    // 5. Bütçe ve Zaman Aşımı Testi
    @Test
    fun testBudgetAndTimeoutConstraints() {
        val budget = TaskBudget(
            maxSteps = 5,
            overallTimeoutMs = 10_000L
        )
        val startTime = System.currentTimeMillis() - 20_000L // 20 saniye önce başlamış
        val session = ExplorationTaskSession(
            startTimeMs = startTime,
            deadlineMs = startTime + 10_000L, // 10 saniye önce dolmuş
            budget = budget
        )

        assertTrue(session.isTimedOut())
        assertTrue(session.isFinished)

        // Yeni başlayan oturum
        val freshSession = ExplorationTaskSession(
            startTimeMs = System.currentTimeMillis(),
            deadlineMs = System.currentTimeMillis() + 60_000L,
            budget = budget
        )
        assertFalse(freshSession.isTimedOut())
        assertFalse(freshSession.isStepLimitReached())
        assertFalse(freshSession.isFinished)

        // Adım limiti kontrolü
        freshSession.stepCount = 5
        assertTrue(freshSession.isStepLimitReached())
        assertTrue(freshSession.isFinished)
    }

    // 6. İptal Mekanizması Testi
    @Test
    fun testCancellationStopsSession() {
        val session = ExplorationTaskSession()
        assertFalse(session.isFinished)

        session.cancel("Kullanıcı durdurdu")
        assertTrue(session.isCancelled)
        assertEquals(AgentState.CANCELLED, session.currentState)
        assertTrue(session.isFinished)
    }

    // 7. Deterministik Düğüm İmzası Testi
    @Test
    fun testNodeSignatureDeterminism() {
        val node1 = createDummyNode("Ayarlar", desc = "Sistem ayarları", viewId = "com.android.settings:id/main_item")
        val sig1 = ExplorationDecisionMaker.generateNodeSignature("com.android.settings", node1)
        val sig2 = ExplorationDecisionMaker.generateNodeSignature("com.android.settings", node1)

        assertEquals("Aynı düğüm için imza deterministik olmalı", sig1, sig2)
        assertTrue(sig1.contains("com.android.settings"))
        assertTrue(sig1.contains("main_item"))
    }
}
