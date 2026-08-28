package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.agent.core.ActionVerifier
import com.example.agent.core.AgentLifecycleManager
import com.example.agent.core.ScreenObserver
import com.example.agent.core.StructuredExplorationEngine
import com.example.agent.core.VerificationResult
import com.example.ai.AIAgentScreenReasoner
import com.example.ai.AgentActionType
import com.example.ai.GroundingAction
import com.example.ai.VisualGroundingEngine
import com.example.data.local.AssistantDatabase
import com.example.data.local.MemoryFileManager
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.UserProfileEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

data class ScreenNodeData(
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean = false,
    val className: String,
    val packageName: String
)

data class ScreenSnapshot(
    val packageName: String,
    val activityName: String,
    val nodeCount: Int,
    val texts: List<String>,
    val clickableNodes: List<ScreenNodeData>,
    val editableNodes: List<ScreenNodeData> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toUltraMinifiedString(maxItems: Int = 25): String {
        val appName = packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
        val header = if (appName.isNotBlank()) "App: $appName\n" else ""
        val textSnippet = if (texts.isNotEmpty()) "Metinler: " + texts.take(6).joinToString(", ") { "\"${it.take(25)}\"" } + "\n" else ""
        val elements = clickableNodes.take(maxItems).mapIndexed { idx, node ->
            val cx = node.bounds.centerX()
            val cy = node.bounds.centerY()
            val textPart = node.text.takeIf { it.isNotBlank() }?.let { "txt:\"${it.replace("\"", "'").take(25)}\"" }
            val descPart = node.contentDescription.takeIf { it.isNotBlank() && it != node.text }?.let { "desc:\"${it.replace("\"", "'").take(25)}\"" }
            val idPart = node.viewId.takeIf { it.isNotBlank() }?.let { "viewId:\"${it.replace("\"", "'").take(20)}\"" }
            val details = listOfNotNull(textPart, descPart, idPart).joinToString(", ")
            if (details.isNotBlank()) {
                "[id:$idx, $details, x:$cx, y:$cy]"
            } else {
                "[id:$idx, x:$cx, y:$cy]"
            }
        }.joinToString("\n")
        return "$header$textSnippet$elements".trim()
    }
}

data class VirtualFingerState(
    val x: Float,
    val y: Float,
    val actionType: String,
    val targetLabel: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

class AiDeviceAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var agentJob: Job? = null

    // Layout stability tracking
    @Volatile
    private var lastAccessibilityEventTime: Long = System.currentTimeMillis()
    private var layoutChangeSignal: CompletableDeferred<Unit>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceActive.value = true
        Log.d("AiAccessibility", "AI Device Accessibility Service Connected")
        serviceScope.launch {
            delay(500)
            updateLiveSnapshot()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        lastAccessibilityEventTime = System.currentTimeMillis()
        layoutChangeSignal?.let {
            if (!it.isCompleted) {
                it.complete(Unit)
            }
        }

        val pkg = event.packageName?.toString() ?: ""
        val cls = event.className?.toString() ?: ""
        if (pkg.isNotEmpty()) {
            _currentPackage.value = pkg
            _currentActivity.value = cls
        }

        updateLiveSnapshot()
    }

    override fun onInterrupt() {
        Log.w("AiAccessibility", "AI Device Accessibility Service Interrupted")
        _isAgentActive.value = false
        _remainingTimeSeconds.value = 0
        serviceScope.launch {
            AgentLifecycleManager.cancelCurrentSession("Erişilebilirlik servisi kesintiye uğradı.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isServiceActive.value = false
        agentJob?.cancel()
        serviceScope.launch {
            AgentLifecycleManager.cancelCurrentSession("Erişilebilirlik servisi kapatıldı.")
        }
    }

    // ----------------- Real-Time Live Screen Vision & Tree Analysis -----------------

    fun extractLiveScreenSnapshot(): ScreenSnapshot = inspectCurrentScreen()

    fun updateLiveSnapshot(): ScreenSnapshot {
        val snapshot = inspectCurrentScreen()
        _liveScreenSnapshot.value = snapshot
        return snapshot
    }

    fun inspectCurrentScreen(): ScreenSnapshot {
        return ScreenObserver.observeScreen(
            root = rootInActiveWindow,
            currentPackage = _currentPackage.value,
            currentActivity = _currentActivity.value
        )
    }

    suspend fun captureLiveScreenshotAsync(): Bitmap? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val deferred = CompletableDeferred<Bitmap?>()
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    applicationContext.mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshotResult.hardwareBuffer,
                                screenshotResult.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
                            _liveScreenshotBitmap.value = bitmap
                            deferred.complete(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e("AiAccessibility", "Screenshot capture failed: $errorCode")
                            deferred.complete(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("AiAccessibility", "Screenshot exception", e)
                deferred.complete(null)
            }
            deferred.await()
        } else {
            null
        }
    }

    /**
     * Smart Action Delay & Layout Settling:
     * Waits until the screen settles and animations / window transitions finish.
     */
    suspend fun awaitScreenSettled(maxWaitMs: Long = 1200L, quietPeriodMs: Long = 300L) {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            val quietTime = System.currentTimeMillis() - lastAccessibilityEventTime
            if (quietTime >= quietPeriodMs) {
                break
            }
            delay(100)
        }
        updateLiveSnapshot()
    }

    // ----------------- Virtual Human-Like Finger Gestures & Touch Dispatch -----------------

    /**
     * Finds the actual AccessibilityNodeInfo matching the ScreenNodeData and executes ACTION_CLICK directly on it or its clickable parent.
     * Returns true if native ACTION_CLICK was performed and returned true.
     */
    fun performNativeNodeClick(nodeData: ScreenNodeData): Boolean {
        val root = rootInActiveWindow ?: return false
        var targetNode: AccessibilityNodeInfo? = null

        fun findNode(node: AccessibilityNodeInfo?) {
            if (node == null || targetNode != null) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val rawViewId = node.viewIdResourceName ?: ""
            val viewId = rawViewId.substringAfterLast("/")

            // Match by exact bounds or high bounds overlap + text/desc/viewId
            val boundsMatch = bounds == nodeData.bounds
            val labelMatch = (nodeData.text.isNotBlank() && text == nodeData.text) ||
                    (nodeData.contentDescription.isNotBlank() && desc == nodeData.contentDescription) ||
                    (nodeData.viewId.isNotBlank() && viewId == nodeData.viewId)

            if (boundsMatch || (labelMatch && Rect.intersects(bounds, nodeData.bounds))) {
                targetNode = node
                return
            }

            for (i in 0 until node.childCount) {
                findNode(node.getChild(i))
            }
        }

        findNode(root)

        if (targetNode != null) {
            val label = nodeData.text.ifBlank { nodeData.contentDescription.ifBlank { nodeData.viewId } }
            _virtualFingerState.value = VirtualFingerState(nodeData.bounds.centerX().toFloat(), nodeData.bounds.centerY().toFloat(), "DOKUNMA (NATIVE)", label)

            // Try clicking the node directly or find its first clickable parent
            var clickCandidate: AccessibilityNodeInfo? = targetNode
            while (clickCandidate != null) {
                if (clickCandidate.isClickable) {
                    val clicked = clickCandidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.d("AiAccessibility", "performNativeNodeClick SUCCESS on node: $label")
                        serviceScope.launch {
                            delay(200)
                            _virtualFingerState.value = null
                            awaitScreenSettled(600L, 200L)
                        }
                        return true
                    }
                }
                clickCandidate = clickCandidate.parent
            }

            // If no parent was marked clickable, try ACTION_CLICK on targetNode anyway
            val forceClicked = targetNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (forceClicked) {
                Log.d("AiAccessibility", "performNativeNodeClick forced SUCCESS on node: $label")
                serviceScope.launch {
                    delay(200)
                    _virtualFingerState.value = null
                    awaitScreenSettled(600L, 200L)
                }
                return true
            }
        }

        return false
    }

    /**
     * Dispatches physical touch with real physical pixel bounds, clipping, and screen boundary validation.
     */
    fun clickAt(x: Float, y: Float, label: String = "", onComplete: (() -> Unit)? = null) {
        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels.toFloat()
        val screenHeight = dm.heightPixels.toFloat()

        // Ensure touch coordinates are strictly inside screen bounds
        val clampedX = x.coerceIn(10f, screenWidth - 10f)
        val clampedY = y.coerceIn(10f, screenHeight - 10f)

        _virtualFingerState.value = VirtualFingerState(clampedX, clampedY, "DOKUNMA", label)

        val path = Path().apply {
            moveTo(clampedX, clampedY)
            lineTo(clampedX, clampedY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 90)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        layoutChangeSignal = CompletableDeferred()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                serviceScope.launch {
                    delay(200)
                    _virtualFingerState.value = null
                    awaitScreenSettled(600L, 200L)
                    onComplete?.invoke()
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                _virtualFingerState.value = null
                onComplete?.invoke()
            }
        }, null)
    }

    /**
     * Executes click on a node:
     * 1. First tries direct native performAction(ACTION_CLICK) to guarantee zero coordinate shift.
     * 2. If node is not native clickable or fails, uses physical gesture at accurate node center.
     */
    fun clickNode(nodeData: ScreenNodeData, onComplete: (() -> Unit)? = null) {
        val nativeSuccess = performNativeNodeClick(nodeData)
        if (nativeSuccess) {
            onComplete?.invoke()
            return
        }

        val centerX = nodeData.bounds.centerX().toFloat()
        val centerY = nodeData.bounds.centerY().toFloat()
        val label = nodeData.text.ifBlank { nodeData.contentDescription.ifBlank { nodeData.viewId } }
        clickAt(centerX, centerY, label, onComplete)
    }

    /**
     * "Gördüm ve Bastım" Action-Verification Loop:
     * 1. If targetNode is provided, attempts native node ACTION_CLICK first.
     * 2. Otherwise takes before screenshot, dispatches touch gesture.
     * 3. Waits 400ms and takes after screenshot.
     * 4. If similarity >= 90%, retries with 10-15px offset jitter (tolerance check).
     */
    suspend fun clickAtWithVerificationResult(
        x: Float,
        y: Float,
        label: String = "",
        targetNode: ScreenNodeData? = null,
        maxRetries: Int = 2
    ): VerificationResult = withContext(Dispatchers.Main) {
        val beforeSnapshot = inspectCurrentScreen()
        val beforeScreenshot = captureLiveScreenshotAsync()

        // 1. If we have the exact target node, attempt direct native performAction first
        if (targetNode != null) {
            val nativeDone = performNativeNodeClick(targetNode)
            if (nativeDone) {
                delay(400)
                awaitScreenSettled(600L, 200L)
                val afterSnapshot = inspectCurrentScreen()
                val afterScreenshot = captureLiveScreenshotAsync()

                val result = ActionVerifier.verifyClickOutcome(
                    beforeSnapshot = beforeSnapshot,
                    afterSnapshot = afterSnapshot,
                    targetNode = targetNode,
                    beforeBitmap = beforeScreenshot,
                    afterBitmap = afterScreenshot
                )
                if (result.isSuccess) {
                    Log.d("AiAccessibility", "Native click ActionVerifier SUCCESS: ${result.reason}")
                    return@withContext result
                }
            }
        }

        // 2. Gesture touch loop with physical coordinate precision & jitter retry
        var currentX = x
        var currentY = y
        var lastResult: VerificationResult = VerificationResult.unchanged("Tıklama gerçekleşti fakat ekran tepki vermedi")

        for (attempt in 0..maxRetries) {
            val currentBeforeSnapshot = if (attempt == 0) beforeSnapshot else inspectCurrentScreen()
            val currentBeforeScreenshot = if (attempt == 0) beforeScreenshot else captureLiveScreenshotAsync()

            val clickFinished = CompletableDeferred<Unit>()
            clickAt(currentX, currentY, label) {
                clickFinished.complete(Unit)
            }
            clickFinished.await()

            // Wait 400ms for screen response
            delay(400)
            awaitScreenSettled(600L, 200L)

            val afterSnapshot = inspectCurrentScreen()
            val afterScreenshot = captureLiveScreenshotAsync()

            val result = ActionVerifier.verifyClickOutcome(
                beforeSnapshot = currentBeforeSnapshot,
                afterSnapshot = afterSnapshot,
                targetNode = targetNode,
                beforeBitmap = currentBeforeScreenshot,
                afterBitmap = afterScreenshot
            )

            lastResult = result
            Log.d("AiAccessibility", "Verification check attempt #$attempt at ($currentX, $currentY): ${result.reason} (isSuccess=${result.isSuccess})")

            if (result.isSuccess) {
                Log.d("AiAccessibility", "Verification SUCCESS: ${result.reason}")
                return@withContext result
            }

            // Screen didn't react -> Try offset jitter
            if (attempt < maxRetries) {
                val offset = if (attempt == 0) 12f else -14f
                currentX = (x + offset).coerceAtLeast(10f)
                currentY = (y + if (attempt == 1) 12f else 0f).coerceAtLeast(10f)
                Log.w("AiAccessibility", "Verification RETRY: Screen didn't react. Jittering touch to ($currentX, $currentY)...")
                delay(200)
            }
        }

        return@withContext lastResult
    }

    suspend fun clickAtWithVerification(
        x: Float,
        y: Float,
        label: String = "",
        targetNode: ScreenNodeData? = null,
        maxRetries: Int = 2
    ): Boolean = clickAtWithVerificationResult(x, y, label, targetNode, maxRetries).isSuccess

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300, onComplete: (() -> Unit)? = null) {
        _virtualFingerState.value = VirtualFingerState(startX, startY, "KAYDIRMA", "Ekran Kaydırma")

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                serviceScope.launch {
                    delay(250)
                    _virtualFingerState.value = null
                    awaitScreenSettled(600L, 200L)
                    onComplete?.invoke()
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                _virtualFingerState.value = null
                onComplete?.invoke()
            }
        }, null)
    }

    suspend fun swipeLeftAsync(): Unit = withContext(Dispatchers.Main) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val deferred = CompletableDeferred<Unit>()
        swipe(w * 0.85f, h * 0.5f, w * 0.15f, h * 0.5f, 320) {
            deferred.complete(Unit)
        }
        deferred.await()
        delay(500)
        awaitScreenSettled()
    }

    suspend fun swipeRightAsync(): Unit = withContext(Dispatchers.Main) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val deferred = CompletableDeferred<Unit>()
        swipe(w * 0.15f, h * 0.5f, w * 0.85f, h * 0.5f, 320) {
            deferred.complete(Unit)
        }
        deferred.await()
        delay(500)
        awaitScreenSettled()
    }

    suspend fun swipeUpAsync(): Unit = withContext(Dispatchers.Main) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val deferred = CompletableDeferred<Unit>()
        swipe(w * 0.5f, h * 0.82f, w * 0.5f, h * 0.18f, 320) {
            deferred.complete(Unit)
        }
        deferred.await()
        delay(500)
        awaitScreenSettled()
    }

    suspend fun swipeDownAsync(): Unit = withContext(Dispatchers.Main) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val deferred = CompletableDeferred<Unit>()
        swipe(w * 0.5f, h * 0.25f, w * 0.5f, h * 0.78f, 320) {
            deferred.complete(Unit)
        }
        deferred.await()
        delay(500)
        awaitScreenSettled()
    }

    fun typeTextIntoNode(text: String, targetQuery: String = ""): Boolean {
        val root = rootInActiveWindow ?: return false
        var targetNode: AccessibilityNodeInfo? = null

        fun findEditable(node: AccessibilityNodeInfo?) {
            if (node == null || targetNode != null) return
            val isEdit = node.isEditable || (node.className?.contains("EditText", ignoreCase = true) == true)
            if (isEdit) {
                if (targetQuery.isBlank()) {
                    targetNode = node
                    return
                } else {
                    val desc = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
                    if (desc.contains(targetQuery, ignoreCase = true)) {
                        targetNode = node
                        return
                    }
                }
            }
            for (i in 0 until node.childCount) {
                findEditable(node.getChild(i))
            }
        }

        findEditable(root)

        if (targetNode != null) {
            val bounds = Rect()
            targetNode?.getBoundsInScreen(bounds)
            _virtualFingerState.value = VirtualFingerState(bounds.centerX().toFloat(), bounds.centerY().toFloat(), "METİN YAZMA", text)

            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = targetNode?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
            if (success) {
                serviceScope.launch {
                    delay(300)
                    _virtualFingerState.value = null
                    updateLiveSnapshot()
                }
                return true
            }
        }

        return false
    }

    fun findAndClickMatching(query: String): Boolean {
        val snapshot = updateLiveSnapshot()
        val q = query.lowercase(Locale("tr", "TR")).trim()

        val target = snapshot.clickableNodes.find {
            it.text.lowercase(Locale("tr", "TR")).contains(q) ||
                    it.contentDescription.lowercase(Locale("tr", "TR")).contains(q) ||
                    it.viewId.lowercase(Locale("tr", "TR")).contains(q)
        }

        if (target != null) {
            clickNode(target)
            return true
        }
        return false
    }

    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun pressNotifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun volumeUp(): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            Log.d("AiAccessibility", "Volume RAISED successfully")
            true
        } catch (e: Exception) {
            Log.e("AiAccessibility", "Error raising volume", e)
            false
        }
    }

    fun volumeDown(): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            Log.d("AiAccessibility", "Volume LOWERED successfully")
            true
        } catch (e: Exception) {
            Log.e("AiAccessibility", "Error lowering volume", e)
            false
        }
    }

    // ----------------- Visual Autonomous UI Search & App Navigation -----------------

    /**
     * Pure Visual Grounding Human-Like App Search and Opener:
     * Does NOT use Android background Intent!
     * 1. Presses Home button to reach launcher.
     * 2. Visually searches for app icon across home screen pages / app drawer using Gemini Vision + Screen Verification.
     * 3. Opens folder if app is inside folder, locates app inside, and taps it!
     */
    suspend fun findAndOpenAppVisually(
        appName: String,
        apiKey: String = "",
        maxSwipes: Int = 6,
        onStatusUpdate: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.Main) {
        onStatusUpdate?.invoke("Ana ekranda $appName simgesi görsel olarak aranıyor...")
        pressHome()
        delay(800)
        awaitScreenSettled(1200L, 300L)

        val targetQuery = appName.lowercase(Locale("tr", "TR")).trim()

        for (step in 1..maxSwipes) {
            onStatusUpdate?.invoke("Ekran taranıyor ($step/$maxSwipes): $appName")

            val screenshot = captureLiveScreenshotAsync()
            val snapshot = updateLiveSnapshot()

            val grounding = VisualGroundingEngine.locateTargetOnScreen(
                apiKey = apiKey,
                bitmap = screenshot,
                targetDescription = appName,
                candidateNodes = snapshot.clickableNodes,
                currentPackage = snapshot.packageName,
                stepNumber = step,
                searchContext = "Ana ekranda veya çekmecede $appName uygulamasının simgesini bulup tıkla"
            )

            Log.d("AiAccessibility", "Grounding step $step: found=${grounding.found}, action=${grounding.action}, coords=(${grounding.targetX}, ${grounding.targetY}), isFolder=${grounding.isFolder}")

            if (grounding.found && (grounding.targetNode != null || (grounding.targetX > 0 && grounding.targetY > 0))) {
                if (grounding.isFolder || grounding.action == GroundingAction.CLICK_FOLDER) {
                    onStatusUpdate?.invoke("Klasör açılıyor: ${grounding.targetName}")
                    val folderOpened = clickAtWithVerification(grounding.targetX, grounding.targetY, grounding.targetName, targetNode = grounding.targetNode)
                    delay(500)
                    awaitScreenSettled(800L, 200L)

                    // Search inside opened folder
                    val insideScreenshot = captureLiveScreenshotAsync()
                    val insideSnapshot = updateLiveSnapshot()
                    val insideGrounding = VisualGroundingEngine.locateTargetOnScreen(
                        apiKey = apiKey,
                        bitmap = insideScreenshot,
                        targetDescription = appName,
                        candidateNodes = insideSnapshot.clickableNodes,
                        currentPackage = insideSnapshot.packageName,
                        stepNumber = 1,
                        searchContext = "Klasörün içindeki $appName simgesine tıkla"
                    )

                    if (insideGrounding.found && (insideGrounding.targetNode != null || (insideGrounding.targetX > 0 && insideGrounding.targetY > 0))) {
                        onStatusUpdate?.invoke("Klasör içinden $appName başlatılıyor...")
                        clickAtWithVerification(insideGrounding.targetX, insideGrounding.targetY, appName, targetNode = insideGrounding.targetNode)
                        delay(1200)
                        awaitScreenSettled(1500L, 300L)
                        return@withContext true
                    }
                } else {
                    onStatusUpdate?.invoke("$appName simgesine dokunuluyor...")
                    clickAtWithVerification(grounding.targetX, grounding.targetY, appName, targetNode = grounding.targetNode)
                    delay(1200)
                    awaitScreenSettled(1500L, 300L)
                    return@withContext true
                }
            }

            // Target not found on this screen -> Human-like navigation swipe
            when (grounding.action) {
                GroundingAction.SWIPE_LEFT -> {
                    onStatusUpdate?.invoke("Sonraki ana ekran sayfasına kaydırılıyor...")
                    swipeLeftAsync()
                }
                GroundingAction.SWIPE_RIGHT -> {
                    onStatusUpdate?.invoke("Önceki sayfaya kaydırılıyor...")
                    swipeRightAsync()
                }
                GroundingAction.SWIPE_UP -> {
                    onStatusUpdate?.invoke("Uygulama çekmecesi açılıyor...")
                    swipeUpAsync()
                }
                GroundingAction.SWIPE_DOWN -> {
                    onStatusUpdate?.invoke("Uygulama listesinde aşağı kaydırılıyor...")
                    swipeDownAsync()
                }
                else -> {
                    onStatusUpdate?.invoke("Diğer sayfalara bakılıyor...")
                    swipeLeftAsync()
                }
            }
        }

        onStatusUpdate?.invoke("Ana ekranda $appName simgesi bulunamadı.")
        return@withContext false
    }

    // ----------------- Autonomous Device Control & AI Reasoning Exploration Loop -----------------

    fun startTimedAgentControl(
        context: Context,
        durationMinutes: Int,
        taskPrompt: String = "Cihazı Keşfet ve İncele",
        reasoner: AIAgentScreenReasoner? = null,
        profile: UserProfileEntity? = null,
        onStatusUpdate: (String) -> Unit,
        onFinished: (learnedCount: Int) -> Unit
    ) {
        agentJob?.cancel()

        val totalSeconds = if (durationMinutes <= 0) 120 else durationMinutes * 60
        _totalControlDurationSeconds.value = totalSeconds
        _remainingTimeSeconds.value = totalSeconds
        _isAgentActive.value = true
        _currentTaskName.value = taskPrompt

        agentJob = serviceScope.launch {
            try {
                StructuredExplorationEngine.executeExploration(
                    context = context,
                    service = this@AiDeviceAccessibilityService,
                    durationMinutes = durationMinutes,
                    taskPrompt = taskPrompt,
                    reasoner = reasoner,
                    profile = profile,
                    onCountdownTick = { remaining ->
                        _remainingTimeSeconds.value = remaining
                    },
                    onStatusUpdate = onStatusUpdate,
                    onFinished = { learned ->
                        _discoveredCount.value = learned
                        onFinished(learned)
                    }
                )
            } catch (e: Exception) {
                Log.e("AiAccessibility", "Agent execution error", e)
                onStatusUpdate("İşlem sırasında duraklatıldı: ${e.localizedMessage}")
                onFinished(0)
            } finally {
                _isAgentActive.value = false
                _remainingTimeSeconds.value = 0
            }
        }
    }

    fun stopAgentControl() {
        agentJob?.cancel()
        _isAgentActive.value = false
        _remainingTimeSeconds.value = 0
        serviceScope.launch {
            AgentLifecycleManager.cancelCurrentSession("Kullanıcı tarafından durduruldu.")
        }
    }

    companion object {
        var instance: AiDeviceAccessibilityService? = null
            private set

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive = _isServiceActive.asStateFlow()

        private val _isAgentActive = MutableStateFlow(false)
        val isAgentActive = _isAgentActive.asStateFlow()

        private val _remainingTimeSeconds = MutableStateFlow(0)
        val remainingTimeSeconds = _remainingTimeSeconds.asStateFlow()

        private val _totalControlDurationSeconds = MutableStateFlow(120)
        val totalControlDurationSeconds = _totalControlDurationSeconds.asStateFlow()

        private val _currentTaskName = MutableStateFlow("Cihazı Keşfet")
        val currentTaskName = _currentTaskName.asStateFlow()

        private val _discoveredCount = MutableStateFlow(0)
        val discoveredCount = _discoveredCount.asStateFlow()

        private val _currentPackage = MutableStateFlow("")
        val currentPackage = _currentPackage.asStateFlow()

        private val _currentActivity = MutableStateFlow("")
        val currentActivity = _currentActivity.asStateFlow()

        private val _liveScreenSnapshot = MutableStateFlow(
            ScreenSnapshot(
                packageName = "",
                activityName = "",
                nodeCount = 0,
                texts = emptyList(),
                clickableNodes = emptyList()
            )
        )
        val liveScreenSnapshot = _liveScreenSnapshot.asStateFlow()

        private val _liveScreenshotBitmap = MutableStateFlow<Bitmap?>(null)
        val liveScreenshotBitmap = _liveScreenshotBitmap.asStateFlow()

        private val _virtualFingerState = MutableStateFlow<VirtualFingerState?>(null)
        val virtualFingerState = _virtualFingerState.asStateFlow()

        fun isAccessibilityEnabled(context: Context): Boolean {
            val expectedComponentName = "${context.packageName}/${AiDeviceAccessibilityService::class.java.canonicalName}"
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            return enabledServicesSetting.split(':').any {
                it.equals(expectedComponentName, ignoreCase = true) ||
                        it.contains(AiDeviceAccessibilityService::class.java.simpleName)
            }
        }
    }
}
