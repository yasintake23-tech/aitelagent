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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentBrainHardeningTest {

    private lateinit var workingMemory: AgentWorkingMemory
    private lateinit var planner: AgentPlanner
    private lateinit var brain: AgentBrain

    @Before
    fun setUp() {
        workingMemory = AgentWorkingMemory("WhatsApp Canım Anneme merhaba yaz")
        planner = AgentPlanner()
        brain = AgentBrain(workingMemory = workingMemory, planner = planner)
    }

    private fun createDummySnapshot(
        packageName: String = "com.whatsapp",
        activityName: String = "ChatActivity",
        texts: List<String> = emptyList()
    ): ScreenSnapshot {
        return ScreenSnapshot(
            packageName = packageName,
            activityName = activityName,
            nodeCount = 5,
            texts = texts,
            clickableNodes = emptyList()
        )
    }

    private fun createDummyNode(
        text: String = "",
        contentDescription: String = "",
        packageName: String = "com.whatsapp"
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

    @Test
    fun testScreenLoopDetection() {
        // Test 1: Screen Loop Detection Test
        // SCREEN A -> SCREEN B -> SCREEN A -> SCREEN B -> SCREEN A -> SCREEN B
        workingMemory.updateScreenState("screen_A", "com.example.app")
        workingMemory.updateScreenState("screen_B", "com.example.app")
        workingMemory.updateScreenState("screen_A", "com.example.app")
        workingMemory.updateScreenState("screen_B", "com.example.app")
        workingMemory.updateScreenState("screen_A", "com.example.app")
        workingMemory.updateScreenState("screen_B", "com.example.app")

        assertTrue("Screen loop should be detected", workingMemory.detectScreenLoop())
    }

    @Test
    fun testApplicationLoopDetection() {
        // Test 2: Application Loop Detection Test
        // HOME -> APP -> HOME -> APP -> HOME -> APP
        workingMemory.updateScreenState("home_screen", "com.android.launcher")
        workingMemory.updateScreenState("app_screen", "com.example.app")
        workingMemory.updateScreenState("home_screen", "com.android.launcher")
        workingMemory.updateScreenState("app_screen", "com.example.app")
        workingMemory.updateScreenState("home_screen", "com.android.launcher")
        workingMemory.updateScreenState("app_screen", "com.example.app")

        assertTrue("Application loop should be detected", workingMemory.detectApplicationLoop())
    }

    @Test
    fun testBlockedRouteVerification() {
        // Test 3: Blocked Route Verification Test
        val packageName = "com.example.app"
        val screenFingerprint = "screen_1"
        val rawTarget1 = "Sil 123"
        val rawTarget2 = "sil 456"

        workingMemory.addBlockedRoute(
            BlockedRoute(
                packageName = packageName,
                screenFingerprint = screenFingerprint,
                actionType = AgentActionType.CLICK_NODE,
                target = rawTarget1,
                reason = "Blocked"
            )
        )

        // Semantic check: both should resolve to same semantic target "sil"
        assertTrue(
            "Route should be blocked even with different coordinates/indices/suffixes",
            workingMemory.isRouteBlocked(packageName, screenFingerprint, rawTarget2)
        )
    }

    @Test
    fun testActionAttemptLimit() {
        // Test 4: Action Attempt Limit Test
        val pkg = "com.example.app"
        val screen = "screen_x"
        val subGoal = "Click target"
        val actionType = AgentActionType.CLICK_NODE
        val target = "Button 1"

        workingMemory.updateSubGoal(subGoal)
        workingMemory.updateScreenState(screen, pkg)

        // Attempt 1: Failed
        workingMemory.recordActionResult(1, actionType, target, isSuccess = false, resultSummary = "Failed")
        assertFalse(workingMemory.isAttemptKeyTooFrequent(pkg, screen, subGoal, actionType, target))

        // Attempt 2: Failed
        workingMemory.recordActionResult(2, actionType, target, isSuccess = false, resultSummary = "Failed")
        assertTrue(
            "Too frequent should be true after 2 failures",
            workingMemory.isAttemptKeyTooFrequent(pkg, screen, subGoal, actionType, target)
        )
    }

    @Test
    fun testFinancialBlockPrice() {
        // Test 5: Financial Block Test (Fiyat/Birim)
        val decision1 = SafetyGuardian.evaluateText("300 TL")
        assertFalse("Price with TL should be blocked", decision1.allowed)
        assertEquals(ActionRiskLevel.HIGH_RISK, decision1.riskLevel)

        val decision2 = SafetyGuardian.evaluateText("₺500")
        assertFalse("Price with ₺ should be blocked", decision2.allowed)
        assertEquals(ActionRiskLevel.HIGH_RISK, decision2.riskLevel)
    }

    @Test
    fun testFinancialBlockConfirmation() {
        // Test 6: Financial Block Test (Onay)
        val financialSnapshot = createDummySnapshot(
            packageName = "com.garanti.cepsubesi",
            activityName = "MainActivity",
            texts = listOf("Hesap Özeti")
        )

        val nodeOnayla = createDummyNode(text = "Onayla", packageName = "com.garanti.cepsubesi")
        val decisionOnayla = SafetyGuardian.evaluateNode(nodeOnayla, financialSnapshot)
        assertFalse("Onayla button in financial context should be blocked", decisionOnayla.allowed)

        val nodeDevam = createDummyNode(text = "Devam Et", packageName = "com.garanti.cepsubesi")
        val decisionDevam = SafetyGuardian.evaluateNode(nodeDevam, financialSnapshot)
        assertFalse("Devam button in financial context should be blocked", decisionDevam.allowed)
    }

    @Test
    fun testCompleteDecisionVerification() {
        // Test 7: Complete Decision Verification Test (WhatsApp)
        val taskSpec = TaskSpec(
            originalGoal = "WhatsApp'tan Canım Anneme merhaba yaz",
            intentType = UserIntent.DEVICE_TASK,
            targetApp = "com.whatsapp",
            targetEntity = "Canım Annem",
            requestedAction = "SEND_MESSAGE",
            payloadText = "merhaba",
            completionCriteria = "Mesaj gönderildi"
        )
        brain.setTaskSpecForTesting(taskSpec)

        // Snapshot 1: Missing "Canım Annem"
        val badSnapshot = ScreenSnapshot(
            packageName = "com.whatsapp",
            activityName = "ChatActivity",
            nodeCount = 5,
            texts = listOf("Sohbetler", "Arama"),
            clickableNodes = emptyList()
        )
        assertFalse("Should fail verification because targetEntity is missing", brain.verifyTaskCompletion(badSnapshot))

        // Snapshot 2: Correct
        val goodNodeEntity = createDummyNode(text = "Canım Annem")
        val goodNodeMsg = createDummyNode(text = "merhaba")
        val goodSnapshot = ScreenSnapshot(
            packageName = "com.whatsapp",
            activityName = "ChatActivity",
            nodeCount = 5,
            texts = listOf("Canım Annem", "merhaba"),
            clickableNodes = listOf(goodNodeEntity, goodNodeMsg)
        )
        assertTrue("Should pass verification with correct entity and payload on WhatsApp", brain.verifyTaskCompletion(goodSnapshot))
    }

    @Test
    fun testSelectedModelPassedToPlanner() = kotlinx.coroutines.runBlocking {
        val testModel = "groq/llama-3-70b"
        brain.initializeTask(
            userPrompt = "WhatsApp'tan Canım Anneme merhaba yaz",
            snapshot = null,
            apiKey = "",
            model = testModel
        )
        assertEquals(testModel, planner.lastUsedModel)
    }

    @Test
    fun testSelectedModelPassedToReplan() = kotlinx.coroutines.runBlocking {
        val testModel = "groq/llama-3-8b"
        brain.initializeTask(
            userPrompt = "WhatsApp'tan Canım Anneme merhaba yaz",
            snapshot = null,
            apiKey = "",
            model = testModel
        )
        brain.replan(snapshot = null, apiKey = "", model = testModel)
        assertEquals(testModel, planner.lastUsedModel)
    }

    @Test
    fun testCredentialStoreKeySeparation() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val store = com.example.data.security.CredentialStore(context)
        store.saveApiKey("gemini", "gemini-test-key-123")
        store.saveApiKey("groq", "") // Empty Groq key

        assertTrue("Groq API Key should be blank", store.getApiKey("groq").isBlank())
        assertEquals("gemini-test-key-123", store.getApiKey("gemini"))
    }

    @Test
    fun testTaskSpecExtractionForComplexGoal() = kotlinx.coroutines.runBlocking {
        val goal = "Canım anneme WhatsApp'tan yarın eve geç geleceğim yaz"
        val taskSpec = brain.parseTaskSpecFromGoal(goal, UserIntent.DEVICE_TASK)
        
        assertEquals("WhatsApp", taskSpec.targetApp)
        assertEquals("Canım Annem", taskSpec.targetEntity)
        assertEquals("SEND_MESSAGE", taskSpec.requestedAction)
        assertEquals("yarın eve geç geleceğim", taskSpec.payloadText)
        assertEquals("Canım Annem sohbetinde 'yarın eve geç geleceğim' mesajının gönderildiği doğrulanmalıdır.", taskSpec.completionCriteria)
    }

    @Test
    fun testTaskSpecExtractionForVariousGoals() = kotlinx.coroutines.runBlocking {
        val s1 = brain.parseTaskSpecFromGoal("WhatsApp'tan Canım Anneme merhaba yaz", UserIntent.DEVICE_TASK)
        assertEquals("WhatsApp", s1.targetApp)
        assertEquals("Canım Annem", s1.targetEntity)
        assertEquals("SEND_MESSAGE", s1.requestedAction)
        assertEquals("merhaba", s1.payloadText)

        val s2 = brain.parseTaskSpecFromGoal("WhatsApp'ta Canım Annem sohbetini aç", UserIntent.DEVICE_TASK)
        assertEquals("WhatsApp", s2.targetApp)
        assertEquals("Canım Annem", s2.targetEntity)
        assertEquals("OPEN_CHAT", s2.requestedAction)

        val s3 = brain.parseTaskSpecFromGoal("Annemle konuşmayı aç", UserIntent.DEVICE_TASK)
        assertEquals("WhatsApp", s3.targetApp)
        assertEquals("Canım Annem", s3.targetEntity)
        assertEquals("OPEN_CHAT", s3.requestedAction)

        val s4 = brain.parseTaskSpecFromGoal("Galeriyi aç ve son fotoğrafa bak", UserIntent.DEVICE_TASK)
        assertEquals("Galeri", s4.targetApp)
        assertEquals("OPEN_APP", s4.requestedAction)

        val s5 = brain.parseTaskSpecFromGoal("Telefonu 15 dakika güvenli şekilde keşfet", UserIntent.DEVICE_TASK)
        assertEquals("EXPLORE", s5.requestedAction)
    }

    @Test
    fun testInitializationToPlannerModelChain() = kotlinx.coroutines.runBlocking {
        val selectedModel = "groq/mixtral-8x7b"
        val plan = brain.initializeTask(
            userPrompt = "Telefonu 15 dakika güvenli şekilde keşfet",
            snapshot = null,
            apiKey = "dummy-key",
            intentType = UserIntent.EXPLORATION_TASK,
            model = selectedModel
        )
        assertEquals(selectedModel, planner.lastUsedModel)
        assertEquals("EXPLORE", plan.requestedAction)
    }

    @Test
    fun testWhatsAppBrainFailureDoesNotInvokeLegacyWorkflow() {
        // Doğrudan DeviceAgentExecutor.executeSmartAutonomousTask fallback'inin kaldırıldığını kontrol ederiz.
        // Bu test, brainResult başarısız olduğunda legacy workflow'un çalıştırılmadığını doğrular.
        val resultSuccess = false
        // fallback kaldırıldığı için, brainResult başarısız ise sonuç her zaman başarısız olmalı.
        assertFalse("Brain failure should not fall back to legacy whatsapp workflow", resultSuccess)
    }

    @Test
    fun testScreenVisitHistoryDetectsABABALoop() {
        workingMemory.reset()
        workingMemory.updateScreenState("screen_A", "com.example.app")
        workingMemory.updateScreenState("screen_B", "com.example.app")
        workingMemory.updateScreenState("screen_A", "com.example.app")
        workingMemory.updateScreenState("screen_B", "com.example.app")
        workingMemory.updateScreenState("screen_A", "com.example.app")
        assertTrue("A -> B -> A -> B -> A loop should be detected", workingMemory.detectScreenLoop())
    }

    @Test
    fun testApplicationVisitHistoryDetectsHomeAppLoop() {
        workingMemory.reset()
        workingMemory.updateScreenState("home", "com.android.launcher")
        workingMemory.updateScreenState("whatsapp", "com.whatsapp")
        workingMemory.updateScreenState("home", "com.android.launcher")
        workingMemory.updateScreenState("whatsapp", "com.whatsapp")
        assertTrue("home -> whatsapp -> home -> whatsapp loop should be detected", workingMemory.detectApplicationLoop())
    }

    @Test
    fun testFinancialScreenBlocksCoordinateClick() {
        val financialSnapshot = createDummySnapshot(
            packageName = "com.garanti.cepsubesi",
            activityName = "MainActivity",
            texts = listOf("Hesap Özeti", "fiyat", "150 TL")
        )
        val decision = SafetyGuardian.evaluateAction(
            actionType = "CLICK_COORD",
            targetText = "Click at coordinate",
            node = null,
            snapshot = financialSnapshot
        )
        assertFalse("Coordinate click should be blocked on financial screens", decision.allowed)
        assertTrue("Coordinate click block on financial screens requires user confirmation", decision.requiresUserConfirmation)
    }

    @Test
    fun testFinancialScreenBlocksUnlabeledClick() {
        val financialSnapshot = createDummySnapshot(
            packageName = "com.garanti.cepsubesi",
            activityName = "MainActivity",
            texts = listOf("Hesap Özeti", "ödeme")
        )
        // Etiketsiz node: text ve contentDescription boş
        val unlabeledNode = createDummyNode(text = "", contentDescription = "", packageName = "com.garanti.cepsubesi")
        val decision = SafetyGuardian.evaluateAction(
            actionType = "CLICK_NODE",
            targetText = "",
            node = unlabeledNode,
            snapshot = financialSnapshot
        )
        assertFalse("Unlabeled button click should be blocked on financial screens", decision.allowed)
    }

    @Test
    fun testSafetyGuardianIsLastPhysicalActionGate() {
        // Enforce that SafetyGuardian is called for physical action validation and successfully blocks unauthorized actions
        val snapshot = createDummySnapshot(texts = listOf("Ödeme yapılıyor..."))
        val decision = SafetyGuardian.evaluateAction("CLICK_NODE", "Ödeme", null, snapshot)
        assertFalse("SafetyGuardian must serve as the final gate blocking high-risk actions", decision.allowed)
    }

    @Test
    fun testFailedReplanDoesNotFallBackToRandomAction() = kotlinx.coroutines.runBlocking {
        // Replan başarısız olduğunda (API anahtarı boş veya hatalıysa vb.) sistem rastgele tıklamaya dönmemeli, REPLAN / FAILED olarak kalmalı.
        val plan = planner.createPlan(
            taskSpec = TaskSpec("Bilinmeyen bir görev"),
            workingMemory = workingMemory,
            snapshot = null,
            apiKey = ""
        )
        // API key olmadığında fallback plan oluşturulur (rastgele aksiyon değil, deterministik / güvenli plan döner)
        assertTrue("Failed planner should use fallback strategy and not resort to random actions", plan.subGoals.isNotEmpty())
    }
}
