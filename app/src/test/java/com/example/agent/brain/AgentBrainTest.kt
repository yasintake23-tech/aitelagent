package com.example.agent.brain

import android.graphics.Rect
import com.example.agent.core.UserIntent
import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentBrainTest {

    private lateinit var workingMemory: AgentWorkingMemory
    private lateinit var planner: AgentPlanner
    private lateinit var brain: AgentBrain

    @Before
    fun setUp() {
        workingMemory = AgentWorkingMemory("WhatsApp Canım Annem'e merhaba yaz")
        planner = AgentPlanner()
        brain = AgentBrain(workingMemory = workingMemory, planner = planner)
    }

    @Test
    fun testFallbackPlanGeneration() {
        val taskSpec = TaskSpec(
            originalGoal = "WhatsApp Canım Annem'e mesaj gönder",
            intentType = UserIntent.DEVICE_TASK,
            targetApp = "WhatsApp",
            targetEntity = "Canım Annem",
            requestedAction = "SEND_MESSAGE",
            payloadText = "Merhaba",
            completionCriteria = "Mesaj gönderildi"
        )

        val plan = planner.createFallbackPlan(taskSpec)

        assertEquals("WhatsApp Canım Annem'e mesaj gönder", plan.originalGoal)
        assertEquals("WhatsApp", plan.targetApp)
        assertEquals(3, plan.subGoals.size)
        assertEquals("WhatsApp uygulamasını aç", plan.subGoals[0].description)
        assertEquals("Canım Annem kişisini/öğesini ara ve seç", plan.subGoals[1].description)
        assertEquals("'Merhaba' metnini yaz ve gönder", plan.subGoals[2].description)
    }

    @Test
    fun testWorkingMemoryAndBlockedRoutes() {
        val packageName = "com.whatsapp"
        val screenFingerprint = "fingerprint_123"
        val target = "Sil"

        assertFalse(workingMemory.isRouteBlocked(packageName, screenFingerprint, target))

        val route = BlockedRoute(
            packageName = packageName,
            screenFingerprint = screenFingerprint,
            actionType = AgentActionType.CLICK_NODE,
            target = target,
            reason = "SafetyGuardian tarafından engellendi"
        )

        workingMemory.addBlockedRoute(route)

        assertTrue(workingMemory.isRouteBlocked(packageName, screenFingerprint, target))
    }

    @Test
    fun testAttemptKeyFrequencyCheck() {
        val pkg = "com.example.app"
        val screen = "main_screen"
        val subGoal = "Tıkla"
        val actionType = AgentActionType.CLICK_NODE
        val target = "Düğme 1"

        assertFalse(workingMemory.isAttemptKeyTooFrequent(pkg, screen, subGoal, actionType, target))

        // Record 2 failures
        workingMemory.updateSubGoal(subGoal)
        workingMemory.updateScreenState(screen, pkg)
        workingMemory.recordActionResult(1, actionType, target, isSuccess = false, resultSummary = "Tıklama başarısız")
        workingMemory.recordActionResult(2, actionType, target, isSuccess = false, resultSummary = "Tıklama başarısız")

        assertTrue(workingMemory.isAttemptKeyTooFrequent(pkg, screen, subGoal, actionType, target))
    }

    @Test
    fun testSafetyGuardianIntegration() {
        val node = ScreenNodeData(
            text = "3.000 TL Öde",
            contentDescription = "",
            viewId = "btn_confirm",
            bounds = Rect(100, 100, 300, 200),
            isClickable = true,
            isScrollable = false,
            className = "android.widget.Button",
            packageName = "com.bank.app"
        )

        val snapshot = ScreenSnapshot(
            packageName = "com.bank.app",
            activityName = "PaymentActivity",
            nodeCount = 1,
            texts = listOf("Ödeme Tutarı: 3.000 TL", "3.000 TL Öde"),
            clickableNodes = listOf(node)
        )

        val proposal = ActionProposal(
            actionType = AgentActionType.CLICK_NODE,
            target = "3.000 TL Öde",
            reason = "Ödemeyi onaylamak için tıkla"
        )

        val safetyDecision = brain.validateActionSafety(proposal, snapshot, node)

        assertFalse("3.000 TL finansal ödeme butonu engellenmelidir.", safetyDecision.allowed)
        assertEquals(ActionRiskLevel.HIGH_RISK, safetyDecision.riskLevel)
        assertTrue("Engellenen rota çalışma belleğine yazılmalıdır.", workingMemory.state.blockedRoutes.isNotEmpty())
    }

    @Test
    fun testActionOutcomeVerifier() {
        val before = ScreenSnapshot(
            packageName = "com.whatsapp",
            activityName = "MainActivity",
            nodeCount = 2,
            texts = listOf("Sohbetler", "Arama"),
            clickableNodes = emptyList()
        )

        val after = ScreenSnapshot(
            packageName = "com.whatsapp",
            activityName = "ChatActivity",
            nodeCount = 2,
            texts = listOf("Canım Annem", "Çevrimiçi"),
            clickableNodes = emptyList()
        )

        val expected = ExpectedOutcomeSpec(
            screenChangeExpected = true,
            expectedPackage = "com.whatsapp",
            expectedText = listOf("Canım Annem")
        )

        val result = ActionOutcomeVerifier.verifyOutcome(before, after, expected)

        assertTrue(result.isVerified)
    }
}
