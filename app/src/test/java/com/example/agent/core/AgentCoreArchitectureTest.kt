package com.example.agent.core

import android.graphics.Rect
import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentCoreArchitectureTest {

    // 1. AgentTaskSession & TaskBudget Tests
    @Test
    fun testTaskSessionLifecycleAndBudget() {
        val budget = TaskBudget(maxSteps = 5, overallTimeoutMs = 1000L, maxConsecutiveFailures = 3)
        var session = AgentTaskSession(taskGoal = "Test Goal", budget = budget)

        assertEquals(AgentState.IDLE, session.currentState)
        assertEquals(0, session.currentStep)
        assertFalse(session.isFinished)

        // Advance step
        session = session.copy(currentStep = 5, currentState = AgentState.ACTING)
        assertTrue("Max step reached should finish session", session.isStepLimitReached())
        assertTrue("Session should be finished when max step is reached", session.isFinished)

        // Timeout test
        val oldSession = AgentTaskSession(
            taskGoal = "Timeout Test",
            startTimeMs = System.currentTimeMillis() - 2000L,
            budget = budget
        )
        assertTrue("Session should report timeout", oldSession.isTimedOut())
        assertTrue("Timed out session should report isFinished = true", oldSession.isFinished)

        // Cancellation test
        val cancelledSession = session.copy(isCancelled = true, currentState = AgentState.CANCELLED)
        assertTrue(cancelledSession.isFinished)
        assertEquals(AgentState.CANCELLED, cancelledSession.currentState)
    }

    // 2. ScreenObserver Null Node Safety
    @Test
    fun testScreenObserverNullHandling() {
        val snapshot = ScreenObserver.observeScreen(null, "com.example.app", "MainActivity")
        assertNotNull(snapshot)
        assertEquals(0, snapshot.nodeCount)
        assertTrue(snapshot.texts.isEmpty())
        assertTrue(snapshot.clickableNodes.isEmpty())
        assertTrue(snapshot.editableNodes.isEmpty())
        assertEquals("com.example.app", snapshot.packageName)
        assertEquals("MainActivity", snapshot.activityName)
    }

    // 3. ActionVerifier Verification Tests
    @Test
    fun testActionVerifierClickPackageChange() {
        val before = ScreenSnapshot("com.source.app", "MainAct", 10, listOf("Home"), emptyList(), emptyList())
        val after = ScreenSnapshot("com.target.app", "TargetAct", 12, listOf("Target"), emptyList(), emptyList())

        val result = ActionVerifier.verifyClickOutcome(before, after)
        assertEquals(VerificationStatus.VERIFIED, result.status)
        assertTrue(result.isSuccess)
        assertTrue(result.packageChanged)
    }

    @Test
    fun testActionVerifierClickNodeDisappeared() {
        val targetNode = ScreenNodeData("Submit", "Submit Button", "btn_submit", Rect(10, 10, 100, 50), true, false, false, "Button", "com.app")
        val before = ScreenSnapshot("com.app", "MainAct", 10, listOf("Submit"), listOf(targetNode), emptyList())
        val after = ScreenSnapshot("com.app", "MainAct", 9, listOf("Done"), emptyList(), emptyList())

        val result = ActionVerifier.verifyClickOutcome(before, after, targetNode)
        assertEquals(VerificationStatus.VERIFIED, result.status)
        assertTrue(result.isSuccess)
        assertTrue(result.nodeStateChanged)
    }

    @Test
    fun testActionVerifierClickUnchanged() {
        val node = ScreenNodeData("Button", "Btn", "btn1", Rect(10, 10, 50, 50), true, false, false, "Button", "com.app")
        val snapshot = ScreenSnapshot("com.app", "MainAct", 10, listOf("Button"), listOf(node), emptyList())

        val result = ActionVerifier.verifyClickOutcome(snapshot, snapshot, node)
        assertEquals(VerificationStatus.UNCHANGED, result.status)
        assertFalse(result.isSuccess)
    }

    @Test
    fun testActionVerifierTextOutcome() {
        val before = ScreenSnapshot("com.app", "MainAct", 5, emptyList(), emptyList(), emptyList())
        val editableNode = ScreenNodeData("Hello World", "", "edit_text", Rect(0,0,10,10), true, false, true, "EditText", "com.app")
        val afterSuccess = ScreenSnapshot("com.app", "MainAct", 5, listOf("Hello World"), emptyList(), listOf(editableNode))
        val afterFail = ScreenSnapshot("com.app", "MainAct", 5, listOf("Empty"), emptyList(), emptyList())

        val successResult = ActionVerifier.verifyTextOutcome(before, afterSuccess, "Hello")
        assertEquals(VerificationStatus.VERIFIED, successResult.status)
        assertTrue(successResult.isSuccess)

        val failResult = ActionVerifier.verifyTextOutcome(before, afterFail, "Hello")
        assertEquals(VerificationStatus.UNCHANGED, failResult.status)
        assertFalse(failResult.isSuccess)
    }

    @Test
    fun testActionVerifierScrollOutcome() {
        val before = ScreenSnapshot("com.app", "MainAct", 10, listOf("Item 1", "Item 2"), emptyList(), emptyList())
        val afterScrolled = ScreenSnapshot("com.app", "MainAct", 10, listOf("Item 5", "Item 6"), emptyList(), emptyList())
        val afterSame = ScreenSnapshot("com.app", "MainAct", 10, listOf("Item 1", "Item 2"), emptyList(), emptyList())

        val scrolledResult = ActionVerifier.verifyScrollOutcome(before, afterScrolled)
        assertEquals(VerificationStatus.VERIFIED, scrolledResult.status)
        assertTrue(scrolledResult.isSuccess)

        val sameResult = ActionVerifier.verifyScrollOutcome(before, afterSame)
        assertEquals(VerificationStatus.UNCHANGED, sameResult.status)
        assertFalse(sameResult.isSuccess)
    }

    @Test
    fun testActionVerifierAppLaunchOutcome() {
        val snapshot = ScreenSnapshot("com.whatsapp", "ChatList", 10, emptyList(), emptyList(), emptyList())

        val success = ActionVerifier.verifyAppLaunchOutcome("WhatsApp", "com.whatsapp", snapshot)
        assertEquals(VerificationStatus.VERIFIED, success.status)
        assertTrue(success.isSuccess)

        val fail = ActionVerifier.verifyAppLaunchOutcome("Instagram", "com.whatsapp", snapshot)
        assertEquals(VerificationStatus.FAILED, fail.status)
        assertFalse(fail.isSuccess)
    }

    // 4. RecoveryStrategy Tests
    @Test
    fun testRecoveryStrategyUnchangedPipeline() {
        val budget = TaskBudget(maxRetriesPerStep = 3, maxConsecutiveFailures = 3)
        val unchangedResult = VerificationResult.unchanged("Ekran tepki vermedi")

        // Attempt 1 -> Jitter retry
        val plan1 = RecoveryStrategy.evaluateRecovery(unchangedResult, attemptCount = 1, consecutiveFailures = 0, budget = budget)
        assertEquals(RecoveryActionType.RETRY_WITH_JITTER, plan1.actionType)
        assertEquals(15f, plan1.suggestedOffsetX)
        assertFalse(plan1.shouldAbort)

        // Attempt 2 -> Swipe unblock
        val plan2 = RecoveryStrategy.evaluateRecovery(unchangedResult, attemptCount = 2, consecutiveFailures = 1, budget = budget)
        assertEquals(RecoveryActionType.SWIPE_TO_UNBLOCK, plan2.actionType)
        assertFalse(plan2.shouldAbort)

        // Attempt 3 -> Consecutive failures limit reached (nextConsecutive = 3 >= maxConsecutiveFailures 3) -> ABORT_TASK
        val plan3 = RecoveryStrategy.evaluateRecovery(unchangedResult, attemptCount = 3, consecutiveFailures = 2, budget = budget)
        assertEquals(RecoveryActionType.ABORT_TASK, plan3.actionType)
        assertTrue(plan3.shouldAbort)
    }

    @Test
    fun testRecoveryStrategyFailedPipeline() {
        val budget = TaskBudget(maxRetriesPerStep = 3, maxConsecutiveFailures = 3)
        val failedResult = VerificationResult.failed("Yanlış ekran açıldı")

        // Attempt 1 -> Press back
        val plan1 = RecoveryStrategy.evaluateRecovery(failedResult, attemptCount = 1, consecutiveFailures = 0, budget = budget)
        assertEquals(RecoveryActionType.PRESS_BACK_AND_RETRY, plan1.actionType)

        // Attempt 2 -> Re-plan required
        val plan2 = RecoveryStrategy.evaluateRecovery(failedResult, attemptCount = 2, consecutiveFailures = 1, budget = budget)
        assertEquals(RecoveryActionType.REPLAN_REQUIRED, plan2.actionType)
    }

    // 5. Full Pipeline Chain Integration Test
    @Test
    fun testFullPipelineChainSimulation() {
        val budget = TaskBudget(maxSteps = 5, maxRetriesPerStep = 3, maxConsecutiveFailures = 3)
        var session = AgentTaskSession(taskGoal = "Full Chain Test", budget = budget)

        // Step 1: Observe & Act
        session = session.copy(currentState = AgentState.OBSERVING, currentStep = 1)
        session = session.copy(currentState = AgentState.ACTING)

        val beforeSnapshot = ScreenSnapshot("com.test", "Main", 5, listOf("Button"), emptyList(), emptyList())
        val afterSnapshot = ScreenSnapshot("com.test", "Main", 5, listOf("Button"), emptyList(), emptyList()) // Unchanged!

        // Step 2: Verification
        session = session.copy(currentState = AgentState.VERIFYING)
        val vResult = ActionVerifier.verifyClickOutcome(beforeSnapshot, afterSnapshot)
        assertFalse(vResult.isSuccess)
        assertEquals(VerificationStatus.UNCHANGED, vResult.status)

        // Step 3: Recovery
        session = session.copy(currentState = AgentState.RECOVERING)
        val recoveryPlan = RecoveryStrategy.evaluateRecovery(vResult, attemptCount = 1, consecutiveFailures = 0, budget = budget)
        assertEquals(RecoveryActionType.RETRY_WITH_JITTER, recoveryPlan.actionType)

        // Step 4: Simulate Jitter Execution success
        val retryAfterSnapshot = ScreenSnapshot("com.test.details", "DetailAct", 8, listOf("Details"), emptyList(), emptyList())
        val retryVResult = ActionVerifier.verifyClickOutcome(beforeSnapshot, retryAfterSnapshot)
        assertTrue(retryVResult.isSuccess)
        assertEquals(VerificationStatus.VERIFIED, retryVResult.status)

        session = session.copy(currentState = AgentState.VERIFYING)
        session = session.copy(currentState = AgentState.COMPLETED, resultSummary = "Chain test succeeded")

        assertTrue(session.isFinished)
        assertEquals(AgentState.COMPLETED, session.currentState)
    }
}
