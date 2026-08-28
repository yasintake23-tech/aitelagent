package com.example.agent.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentLifecycleTest {

    @Before
    fun setUp() {
        runBlocking {
            AgentLifecycleManager.cancelCurrentSession("Test reset")
        }
    }

    @Test
    fun testSessionLifecycleTransitions() {
        runBlocking {
            val budget = TaskBudget(maxSteps = 10, maxRetriesPerStep = 3)
            val session = AgentLifecycleManager.startSession(
                taskGoal = "WhatsApp mesaj gönder",
                budget = budget,
                initialState = AgentState.PLANNING
            )

            assertEquals("WhatsApp mesaj gönder", session.taskGoal)
            assertEquals(AgentState.PLANNING, AgentLifecycleManager.agentState.value)
            assertNotNull(AgentLifecycleManager.currentSession.value)

            // PLANNING -> OBSERVING
            AgentLifecycleManager.transitionState(session.taskId, AgentState.OBSERVING, step = 1, customStatus = "Ekran inceleniyor...")
            assertEquals(AgentState.OBSERVING, AgentLifecycleManager.agentState.value)
            assertEquals("Ekran inceleniyor...", AgentLifecycleManager.statusText.value)

            // OBSERVING -> ACTING
            AgentLifecycleManager.transitionState(session.taskId, AgentState.ACTING, step = 1, customStatus = "Butona tıklanıyor...")
            assertEquals(AgentState.ACTING, AgentLifecycleManager.agentState.value)

            // ACTING -> VERIFYING
            AgentLifecycleManager.transitionState(session.taskId, AgentState.VERIFYING, step = 1, customStatus = "Doğrulanıyor...")
            assertEquals(AgentState.VERIFYING, AgentLifecycleManager.agentState.value)

            // VERIFYING -> COMPLETED
            AgentLifecycleManager.completeSession(session.taskId, "Mesaj başarıyla gönderildi.")
            assertEquals(AgentState.COMPLETED, AgentLifecycleManager.agentState.value)
            assertTrue(AgentLifecycleManager.currentSession.value?.isFinished == true)
            assertEquals("Mesaj başarıyla gönderildi.", AgentLifecycleManager.currentSession.value?.resultSummary)
        }
    }

    @Test
    fun testAutoCancelPreviousSessionOnNewStart() {
        runBlocking {
            val budget = TaskBudget(maxSteps = 10)
            val session1 = AgentLifecycleManager.startSession(
                taskGoal = "Görev 1",
                budget = budget,
                initialState = AgentState.PLANNING
            )
            assertEquals("Görev 1", AgentLifecycleManager.currentSession.value?.taskGoal)

            val session2 = AgentLifecycleManager.startSession(
                taskGoal = "Görev 2",
                budget = budget,
                initialState = AgentState.PLANNING
            )

            assertEquals(session2.taskId, AgentLifecycleManager.currentSession.value?.taskId)
            assertEquals("Görev 2", AgentLifecycleManager.currentSession.value?.taskGoal)

            // Stale session1 transitions should be ignored
            val staleTransitionResult = AgentLifecycleManager.transitionState(
                session1.taskId,
                AgentState.ACTING,
                step = 1,
                customStatus = "Stale update"
            )
            assertFalse(staleTransitionResult)
            assertEquals(AgentState.PLANNING, AgentLifecycleManager.agentState.value)
        }
    }

    @Test
    fun testFailSession() {
        runBlocking {
            val session = AgentLifecycleManager.startSession("Başarısız olacak görev")
            AgentLifecycleManager.failSession(session.taskId, "Ekran dondu.")

            assertEquals(AgentState.FAILED, AgentLifecycleManager.agentState.value)
            assertEquals("Ekran dondu.", AgentLifecycleManager.currentSession.value?.errorMessage)
            assertTrue(AgentLifecycleManager.currentSession.value?.isFinished == true)
        }
    }

    @Test
    fun testCancelCurrentSession() {
        runBlocking {
            val session = AgentLifecycleManager.startSession("İptal edilecek görev")
            AgentLifecycleManager.cancelCurrentSession("Kullanıcı durdurdu.")

            assertEquals(AgentState.CANCELLED, AgentLifecycleManager.agentState.value)
            assertTrue(AgentLifecycleManager.currentSession.value?.isCancelled == true)
            assertTrue(AgentLifecycleManager.currentSession.value?.isFinished == true)
        }
    }

    @Test
    fun testTransitionFailsOnCancelledSession() {
        runBlocking {
            val session = AgentLifecycleManager.startSession("İptal testi")
            AgentLifecycleManager.cancelCurrentSession("Kullanıcı durdurdu")

            // Transition on cancelled session must return false
            val transitionOk = AgentLifecycleManager.transitionState(
                session.taskId,
                AgentState.ACTING,
                step = 2,
                customStatus = "Butona dokunuluyor"
            )

            assertFalse(transitionOk)
            assertEquals(AgentState.CANCELLED, AgentLifecycleManager.agentState.value)
        }
    }

    @Test
    fun testConversationalIntentDoesNotStartLifecycle() {
        val intentResult = IntentRouter.classifyIntent("merhaba nasılsın")
        assertEquals(UserIntent.CONVERSATIONAL, intentResult.intent)
        // Ensure active session is null or idle
        val active = AgentLifecycleManager.currentSession.value
        assertTrue(active == null || active.isFinished || active.currentState == AgentState.IDLE)
    }

    @Test
    fun testShortDeviceTaskLifecycleSequence() {
        runBlocking {
            val budget = TaskBudget(maxSteps = 1)
            val session = AgentLifecycleManager.startSession("Sesi aç", budget = budget, initialState = AgentState.ACTING)

            assertEquals(AgentState.ACTING, AgentLifecycleManager.agentState.value)
            AgentLifecycleManager.completeSession(session.taskId, "Ses seviyesi yükseltildi.")

            assertEquals(AgentState.COMPLETED, AgentLifecycleManager.agentState.value)
            assertTrue(AgentLifecycleManager.currentSession.value?.isFinished == true)
        }
    }
}
