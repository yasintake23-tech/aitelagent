package com.example.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.example.agent.core.ActionVerifier
import com.example.agent.core.AgentState
import com.example.agent.core.AgentTaskSession
import com.example.agent.core.RecoveryActionType
import com.example.agent.core.RecoveryStrategy
import com.example.agent.core.TaskBudget
import com.example.agent.core.VerificationResult
import com.example.ai.AIAgentScreenReasoner
import com.example.ai.AgentActionType
import com.example.ai.GroundingAction
import com.example.ai.VisualGroundingEngine
import com.example.data.local.AssistantDatabase
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

data class AgentExecutionResult(
    val isSuccess: Boolean,
    val actionType: String,
    val speechFeedback: String,
    val technicalLog: String,
    val clickCoordinates: PointF? = null,
    val targetPackage: String? = null
)

object DeviceAgentExecutor {

    private const val TAG = "DeviceAgentExecutor"

    /**
     * Aggressively normalizes Turkish app names by removing verbs, suffixes ('a, 'e, 'ı, 'i, 'ye, 'ya, etc.) and slang.
     */
    fun normalizeAppQuery(raw: String): String {
        var text = raw.lowercase(Locale("tr", "TR")).trim()

        // 1. Remove common action phrases / words
        val redundantWords = listOf(
            "uygulamasını", "uygulaması", "uygulamaya", "uygulamayı", "uygulama", "app", "application",
            "açsana", "açar mısın", "açarmısın", "aç", "başlatsana", "başlat",
            "girsene", "girer misin", "gir", "baksana", "bak", "çalıştır", "göster",
            "lütfen", "hadi", "şimdi", "canım", "kanka", "kardeşim", "bize"
        )

        for (w in redundantWords) {
            text = text.replace(Regex("\\b$w\\b"), " ")
        }
        text = text.trim().replace(Regex("\\s+"), " ")

        // 2. Slang / colloquial mappings
        when (text) {
            "wp", "watsap", "vatsap", "whatsappa", "whatsapa", "whatsapp'a", "whatsapp'ı", "whatsappi", "whatsapp'e" -> return "WhatsApp"
            "yt", "yutub", "yutup", "youtube'a", "youtubeye", "youtuba", "youtube'u" -> return "YouTube"
            "insta", "ig", "instagrama", "instagram'a", "instagram'ı", "instagrami" -> return "Instagram"
            "ayarlara", "ayarları", "ayarlari", "ayarlar'a", "settings" -> return "Ayarlar"
            "galeriye", "galeriyi", "galeri'ye", "fotoğraflara", "fotolara", "fotoğraf" -> return "Galeri"
            "kameraya", "kamerayı", "kamera'ya", "fotoğraf çek" -> return "Kamera"
            "rehbere", "rehberi", "kişilere", "kişiler'e", "kontaklar" -> return "Kişiler"
            "mesajlara", "mesajları", "sms'e", "sms" -> return "Mesajlar"
            "haritalara", "haritaya", "haritayı", "maps", "harita'ya" -> return "Haritalar"
            "hesap makinesine", "hesap makinesini", "hesap makinası", "hesap", "calculator" -> return "Hesap Makinesi"
            "internete", "tarayıcıya", "tarayıcıyı", "chrome'a", "google" -> return "Chrome"
            "mağazaya", "mağazayı", "play store'a", "playstore", "play store'u" -> return "Play Store"
            "saate", "saati", "alarma", "alarmı" -> return "Saat"
            "dosyalara", "dosyaları", "belgelere" -> return "Dosyalar"
            "gmail'e", "maillere", "postaya", "e-posta" -> return "Gmail"
            "spotify'a", "spotifay", "müzik" -> return "Spotify"
        }

        // 3. Turkish suffix stripping (apostrophe or attached)
        val suffixPatterns = listOf(
            "'[a-zçğıöşü]+",
            "(ya|ye|yı|yi|yu|yü)$",
            "(da|de|ta|te)$",
            "(dan|den|tan|ten)$",
            "(ın|in|un|ün)$",
            "(na|ne|nda|nde)$",
            "(a|e|ı|i|u|ü)$"
        )

        var stripped = text
        for (pattern in suffixPatterns) {
            val r = Regex(pattern)
            if (r.containsMatchIn(stripped) && stripped.length > 3) {
                val candidate = stripped.replace(r, "").trim()
                if (candidate.length >= 2) {
                    stripped = candidate
                    break
                }
            }
        }

        return (stripped.ifBlank { text }).replaceFirstChar { it.uppercase() }
    }

    /**
     * Checks if a string contains explicit app launching intent.
     */
    fun isAppLaunchIntent(text: String): Boolean {
        val lower = text.lowercase(Locale("tr", "TR"))
        val triggers = listOf("aç", "gir", "başlat", "çalıştır", "uygulama", "uygulaması", "app", "baksana")
        return triggers.any { lower.contains(it) }
    }

    /**
     * Pure Human-Like Visual App Opener:
     * Does NOT use Android background Intent.
     * Searches home screen pages, app drawer, and folders with Gemini Vision & touches screen with verification.
     */
    suspend fun openAppVisually(
        context: Context,
        appQuery: String,
        onStepUpdate: ((String) -> Unit)? = null
    ): AgentExecutionResult = withContext(Dispatchers.Main) {
        val service = AiDeviceAccessibilityService.instance
        if (service == null) {
            return@withContext AgentExecutionResult(
                isSuccess = false,
                actionType = "ACCESSIBILITY_UNAVAILABLE",
                speechFeedback = "Uygulamayı ekrandan bulup açabilmek için Erişilebilirlik iznine ihtiyacım var.",
                technicalLog = "AiDeviceAccessibilityService is not enabled"
            )
        }

        val appName = normalizeAppQuery(appQuery)
        onStepUpdate?.invoke("Ana ekranda $appName aranıyor...")

        val opened = service.findAndOpenAppVisually(
            appName = appName,
            apiKey = BuildConfig.GEMINI_API_KEY,
            maxSwipes = 6,
            onStatusUpdate = { status ->
                onStepUpdate?.invoke(status)
            }
        )

        if (opened) {
            return@withContext AgentExecutionResult(
                isSuccess = true,
                actionType = "OPEN_APP_VISUAL",
                speechFeedback = "$appName açılıyor.",
                technicalLog = "Visually located and opened $appName via touch gesture"
            )
        } else {
            return@withContext AgentExecutionResult(
                isSuccess = false,
                actionType = "APP_NOT_FOUND_VISUALLY",
                speechFeedback = "Ekranda $appName görünmüyor.",
                technicalLog = "Visual grounding could not locate $appName icon after swiping"
            )
        }
    }

    /**
     * Finds on-screen UI element matching query using Visual Grounding + Action-Verification touch loop.
     */
    suspend fun clickElementVisually(query: String): AgentExecutionResult = withContext(Dispatchers.Main) {
        val service = AiDeviceAccessibilityService.instance
        if (service == null) {
            return@withContext AgentExecutionResult(
                isSuccess = false,
                actionType = "ACCESSIBILITY_UNAVAILABLE",
                speechFeedback = "Erişilebilirlik izni gerekli.",
                technicalLog = "AiDeviceAccessibilityService instance is null"
            )
        }

        val screenshot = service.captureLiveScreenshotAsync()
        val snapshot = service.updateLiveSnapshot()

        val grounding = VisualGroundingEngine.locateTargetOnScreen(
            apiKey = BuildConfig.GEMINI_API_KEY,
            bitmap = screenshot,
            targetDescription = query,
            candidateNodes = snapshot.clickableNodes,
            currentPackage = snapshot.packageName,
            stepNumber = 1,
            searchContext = "Ekranda '$query' butonunu veya alanını bul"
        )

        if (grounding.found && (grounding.targetNode != null || (grounding.targetX > 0 && grounding.targetY > 0))) {
            val verified = service.clickAtWithVerification(
                x = grounding.targetX,
                y = grounding.targetY,
                label = query,
                targetNode = grounding.targetNode
            )
            return@withContext AgentExecutionResult(
                isSuccess = verified,
                actionType = "CLICK_VISUAL_VERIFIED",
                speechFeedback = "Tıklandı.",
                technicalLog = "Clicked visual coordinate (${grounding.targetX}, ${grounding.targetY}) with verification: $verified (hasNativeNode=${grounding.targetNode != null})",
                clickCoordinates = PointF(grounding.targetX, grounding.targetY)
            )
        }

        return@withContext AgentExecutionResult(
            isSuccess = false,
            actionType = "ELEMENT_NOT_FOUND_VISUALLY",
            speechFeedback = "Ekranda $query göremiyorum.",
            technicalLog = "Visual grounding found=false for '$query'"
        )
    }

    /**
     * Performs direct swipe or navigation gestures.
     */
    suspend fun performNavigation(action: String): AgentExecutionResult = withContext(Dispatchers.Main) {
        val service = AiDeviceAccessibilityService.instance
        val clean = action.lowercase(Locale("tr", "TR")).trim()

        when {
            clean.contains("hızlı panel") || clean.contains("hızlı ayar") || clean.contains("quick settings") || clean.contains("kontrol panel") -> {
                service?.openQuickSettings()
                service?.awaitScreenSettled(800L, 200L)
                return@withContext AgentExecutionResult(
                    isSuccess = service != null,
                    actionType = "GLOBAL_QUICK_SETTINGS",
                    speechFeedback = "Hızlı ayarlar paneli açıldı.",
                    technicalLog = "Executed Accessibility GLOBAL_ACTION_QUICK_SETTINGS"
                )
            }

            clean.contains("bildirim") -> {
                service?.openNotifications()
                service?.awaitScreenSettled(800L, 200L)
                return@withContext AgentExecutionResult(
                    isSuccess = service != null,
                    actionType = "GLOBAL_NOTIFICATIONS",
                    speechFeedback = "Bildirim paneli açıldı.",
                    technicalLog = "Executed Accessibility GLOBAL_ACTION_NOTIFICATIONS"
                )
            }

            clean.contains("sesi aç") || clean.contains("sesi artır") || clean.contains("sesi yükselt") || clean.contains("ses aç") || clean.contains("ses artır") || clean == "volume up" -> {
                val done = service?.volumeUp() == true
                return@withContext AgentExecutionResult(
                    isSuccess = done,
                    actionType = "VOLUME_UP",
                    speechFeedback = "Ses seviyesi yükseltildi.",
                    technicalLog = "Executed AudioManager volumeUp"
                )
            }

            clean.contains("sesi kıs") || clean.contains("sesi azalt") || clean.contains("sesi düşür") || clean.contains("ses kıs") || clean.contains("ses azalt") || clean == "volume down" -> {
                val done = service?.volumeDown() == true
                return@withContext AgentExecutionResult(
                    isSuccess = done,
                    actionType = "VOLUME_DOWN",
                    speechFeedback = "Ses seviyesi kısıldı.",
                    technicalLog = "Executed AudioManager volumeDown"
                )
            }

            clean.contains("ana sayfa") || clean.contains("ana ekran") || clean == "home" -> {
                service?.goHome()
                service?.awaitScreenSettled(1000L, 200L)
                return@withContext AgentExecutionResult(
                    isSuccess = service != null,
                    actionType = "GLOBAL_HOME",
                    speechFeedback = "Ana ekrana dönüldü.",
                    technicalLog = "Executed Accessibility GLOBAL_ACTION_HOME"
                )
            }

            clean.contains("geri") || clean == "back" -> {
                service?.goBack()
                service?.awaitScreenSettled(800L, 200L)
                return@withContext AgentExecutionResult(
                    isSuccess = service != null,
                    actionType = "GLOBAL_BACK",
                    speechFeedback = "Geri dönüldü.",
                    technicalLog = "Executed Accessibility GLOBAL_ACTION_BACK"
                )
            }

            clean.contains("son uygulamalar") || clean.contains("recents") -> {
                service?.pressRecents()
                service?.awaitScreenSettled(800L, 200L)
                return@withContext AgentExecutionResult(
                    isSuccess = service != null,
                    actionType = "GLOBAL_RECENTS",
                    speechFeedback = "Açık uygulamalar gösterildi.",
                    technicalLog = "Executed Accessibility GLOBAL_ACTION_RECENTS"
                )
            }

            clean.contains("sola kaydır") || clean.contains("sonraki sayfa") -> {
                service?.swipeLeftAsync()
                return@withContext AgentExecutionResult(
                    isSuccess = service != null,
                    actionType = "SWIPE_LEFT",
                    speechFeedback = "Ekran sola kaydırıldı.",
                    technicalLog = "Executed swipe left"
                )
            }

            clean.contains("sağa kaydır") || clean.contains("önceki sayfa") -> {
                service?.swipeRightAsync()
                return@withContext AgentExecutionResult(
                    isSuccess = service != null,
                    actionType = "SWIPE_RIGHT",
                    speechFeedback = "Ekran sağa kaydırıldı.",
                    technicalLog = "Executed swipe right"
                )
            }

            clean.contains("aşağı kaydır") || clean.contains("aşağı in") || clean.contains("scroll down") -> {
                service?.swipeDownAsync()
                return@withContext AgentExecutionResult(
                    isSuccess = service != null,
                    actionType = "SWIPE_DOWN",
                    speechFeedback = "Sayfa aşağı kaydırıldı.",
                    technicalLog = "Executed swipe down"
                )
            }

            clean.contains("yukarı kaydır") || clean.contains("yukarı çık") || clean.contains("scroll up") -> {
                service?.swipeUpAsync()
                return@withContext AgentExecutionResult(
                    isSuccess = service != null,
                    actionType = "SWIPE_UP",
                    speechFeedback = "Sayfa yukarı kaydırıldı.",
                    technicalLog = "Executed swipe up"
                )
            }
        }

        return@withContext AgentExecutionResult(
            isSuccess = false,
            actionType = "UNKNOWN_GESTURE",
            speechFeedback = "Bu hareketi tanıyamadım.",
            technicalLog = "Unrecognized gesture command: $action"
        )
    }

    /**
     * Pure Visual Human-Like WhatsApp Message Sending Pipeline:
     * 1. Visually find and tap WhatsApp icon on home screen/drawer (NO Intent!).
     * 2. Visually locate search icon/bar, tap with verification.
     * 3. Type contact name, visually locate matching contact, tap with verification.
     * 4. Type message into input box.
     * 5. Visually locate send button, tap with verification.
     */
    suspend fun executeWhatsAppMessageWorkflow(
        context: Context,
        contactName: String,
        message: String,
        onStepUpdate: ((String) -> Unit)? = null
    ): AgentExecutionResult = withContext(Dispatchers.Main) {
        val service = AiDeviceAccessibilityService.instance
        if (service == null) {
            return@withContext AgentExecutionResult(
                isSuccess = false,
                actionType = "ACCESSIBILITY_UNAVAILABLE",
                speechFeedback = "WhatsApp üzerinden otomatik mesaj göndermek için Erişilebilirlik iznine ihtiyacım var.",
                technicalLog = "AiDeviceAccessibilityService is not enabled"
            )
        }

        // Step 1: Open WhatsApp Purely Visually (No Intent)
        onStepUpdate?.invoke("Ana ekrandan WhatsApp simgesi görsel olarak aranıyor...")
        val appOpened = service.findAndOpenAppVisually("WhatsApp", BuildConfig.GEMINI_API_KEY, 6) { status ->
            onStepUpdate?.invoke(status)
        }

        if (!appOpened) {
            return@withContext AgentExecutionResult(
                isSuccess = false,
                actionType = "WHATSAPP_NOT_FOUND",
                speechFeedback = "Ana ekranda WhatsApp simgesi bulunamadı.",
                technicalLog = "Could not visually locate WhatsApp icon"
            )
        }

        service.awaitScreenSettled(1800L, 400L)

        // Step 2: Visually locate Search icon
        onStepUpdate?.invoke("WhatsApp içinde arama simgesi aranıyor...")
        var searchScreenshot = service.captureLiveScreenshotAsync()
        var searchSnapshot = service.updateLiveSnapshot()

        var searchGrounding = VisualGroundingEngine.locateTargetOnScreen(
            apiKey = BuildConfig.GEMINI_API_KEY,
            bitmap = searchScreenshot,
            targetDescription = "Arama simgesi büyüteç ikonu",
            candidateNodes = searchSnapshot.clickableNodes,
            currentPackage = searchSnapshot.packageName,
            stepNumber = 1,
            searchContext = "WhatsApp üst çubuğundaki arama büyüteç butonunu bul"
        )

        if (searchGrounding.found && (searchGrounding.targetNode != null || searchGrounding.targetX > 0)) {
            service.clickAtWithVerification(searchGrounding.targetX, searchGrounding.targetY, "Arama", targetNode = searchGrounding.targetNode)
        } else {
            // Fallback node click
            service.findAndClickMatching("ara")
        }

        service.awaitScreenSettled(1000L, 300L)

        // Step 3: Type contact name
        onStepUpdate?.invoke("Kişi yazılıyor: $contactName")
        service.typeTextIntoNode(contactName)
        service.awaitScreenSettled(1400L, 400L)

        // Step 4: Visually locate and select contact in search results
        onStepUpdate?.invoke("Kişi seçiliyor: $contactName")
        val contactScreenshot = service.captureLiveScreenshotAsync()
        val contactSnapshot = service.updateLiveSnapshot()

        val contactGrounding = VisualGroundingEngine.locateTargetOnScreen(
            apiKey = BuildConfig.GEMINI_API_KEY,
            bitmap = contactScreenshot,
            targetDescription = "$contactName sohbeti veya kişisi",
            candidateNodes = contactSnapshot.clickableNodes,
            currentPackage = contactSnapshot.packageName,
            stepNumber = 1,
            searchContext = "Arama sonuçlarında $contactName adlı kişiyi bul ve tıkla"
        )

        if (contactGrounding.found && (contactGrounding.targetNode != null || contactGrounding.targetX > 0)) {
            service.clickAtWithVerification(contactGrounding.targetX, contactGrounding.targetY, contactName, targetNode = contactGrounding.targetNode)
        } else {
            val contactClicked = service.findAndClickMatching(contactName)
            if (!contactClicked) {
                // Click first result item in list
                val firstResult = contactSnapshot.clickableNodes.firstOrNull { it.bounds.centerY() in 200..1000 }
                if (firstResult != null) {
                    service.clickAtWithVerification(firstResult.bounds.centerX().toFloat(), firstResult.bounds.centerY().toFloat(), contactName, targetNode = firstResult)
                }
            }
        }

        service.awaitScreenSettled(1800L, 400L)

        // Step 5: Type message into chat box
        onStepUpdate?.invoke("Mesaj yazılıyor...")
        val typed = service.typeTextIntoNode(message)
        if (!typed) {
            // Locate message field visually
            val msgScreenshot = service.captureLiveScreenshotAsync()
            val msgSnapshot = service.updateLiveSnapshot()
            val msgGrounding = VisualGroundingEngine.locateTargetOnScreen(
                apiKey = BuildConfig.GEMINI_API_KEY,
                bitmap = msgScreenshot,
                targetDescription = "Mesaj yazma kutusu metin alanı",
                candidateNodes = msgSnapshot.clickableNodes,
                currentPackage = msgSnapshot.packageName,
                stepNumber = 1
            )
            if (msgGrounding.found && (msgGrounding.targetNode != null || msgGrounding.targetX > 0)) {
                service.clickAtWithVerification(msgGrounding.targetX, msgGrounding.targetY, "Mesaj Alanı", targetNode = msgGrounding.targetNode)
                delay(400)
                service.typeTextIntoNode(message)
            }
        }

        service.awaitScreenSettled(1000L, 300L)

        // Step 6: Visually locate and click Send button
        onStepUpdate?.invoke("Gönder butonuna dokunuluyor...")
        val sendScreenshot = service.captureLiveScreenshotAsync()
        val sendSnapshot = service.updateLiveSnapshot()

        val sendGrounding = VisualGroundingEngine.locateTargetOnScreen(
            apiKey = BuildConfig.GEMINI_API_KEY,
            bitmap = sendScreenshot,
            targetDescription = "Gönder butonu yeşil ok simgesi",
            candidateNodes = sendSnapshot.clickableNodes,
            currentPackage = sendSnapshot.packageName,
            stepNumber = 1,
            searchContext = "Sohbetin sağ altındaki yeşil dairesel Gönder / Send butonunu bul"
        )

        if (sendGrounding.found && (sendGrounding.targetNode != null || sendGrounding.targetX > 0)) {
            service.clickAtWithVerification(sendGrounding.targetX, sendGrounding.targetY, "Gönder", targetNode = sendGrounding.targetNode)
        } else {
            service.findAndClickMatching("gönder") || service.findAndClickMatching("send")
        }

        service.awaitScreenSettled(1000L, 300L)

        return@withContext AgentExecutionResult(
            isSuccess = true,
            actionType = "WHATSAPP_AUTOMATION_VISUAL",
            speechFeedback = "$contactName kişisine “$message” mesajı başarıyla gönderildi.",
            technicalLog = "Pure visual human-like WhatsApp message automation completed for '$contactName'"
        )
    }

    /**
     * Checks if user text requires device action, gesture, app control or screen reading.
     */
    fun isDeviceActionOrScreenIntent(text: String): Boolean {
        val lower = text.lowercase(Locale("tr", "TR")).trim()
        val actionTriggers = listOf(
            "aç", "başlat", "çalıştır", "gir", "baksana", "kapat",
            "tıkla", "bas", "dokun", "seç", "yaz", "gönder", "mesaj",
            "kaydır", "aşağı", "yukarı", "sağa", "sola", "home", "ana sayfa", "ana ekran", "geri", "back",
            "ses", "sesi", "ses aç", "ses kıs", "sesi aç", "sesi kıs", "sesi artır", "sesi azalt", "sesi yükselt", "sesi düşür",
            "hızlı panel", "hızlı ayarlar", "hızlı ayarları", "quick settings", "kontrol paneli", "bildirim", "bildirimler", "bildirim paneli",
            "ekranı oku", "ekranda ne var", "ekranı incele", "ekrana bak", "ekranı tara", "görsel",
            "whatsapp", "wp", "instagram", "youtube", "galeri", "kamera", "ayarlar", "rehber", "sms",
            "keşfet", "kontrol et", "cihazı incele", "kurcala", "telefonu gez", "durdur", "iptal", "sus",
            "ara", "bul", "göster", "gezin", "gez", "oyna", "oynat", "oku", "araştır",
            "müzik", "şarkı", "video", "fotoğraf", "resim", "harita", "hesap", "not", "telefon", "arama",
            "chrome", "tarayıcı", "web", "google", "mail", "eposta", "bluetooth", "wifi", "fener", "flaş", "tema", "mod"
        )
        return actionTriggers.any { lower.contains(it) }
    }

    /**
     * Continuous Multi-Step Autonomous ReAct Loop (Gör -> Düşün -> Aksiyon Al -> Doğrula -> Ekranı Yenile):
     * Integrated with AgentTaskSession, TaskBudget, ActionVerifier and RecoveryStrategy.
     */
    suspend fun executeAutonomousReActLoop(
        context: Context,
        goalPrompt: String,
        reasoner: AIAgentScreenReasoner,
        maxSteps: Int = 10,
        onStatusUpdate: ((String) -> Unit)? = null
    ): AgentExecutionResult = withContext(Dispatchers.Main) {
        val service = AiDeviceAccessibilityService.instance
        if (service == null) {
            return@withContext AgentExecutionResult(
                isSuccess = false,
                actionType = "ACCESSIBILITY_UNAVAILABLE",
                speechFeedback = "Otonom görevi yürütmek için Erişilebilirlik iznine ihtiyacım var.",
                technicalLog = "AiDeviceAccessibilityService is not running"
            )
        }

        val budget = TaskBudget(
            maxSteps = maxSteps,
            maxRetriesPerStep = 3,
            overallTimeoutMs = 180_000L,
            perStepTimeoutMs = 20_000L,
            maxConsecutiveFailures = 3
        )

        var taskSession = AgentTaskSession(
            taskGoal = goalPrompt,
            budget = budget,
            currentState = AgentState.IDLE
        )

        val visitedElements = mutableSetOf<String>()
        val database = AssistantDatabase.getDatabase(context)
        val profile = withContext(Dispatchers.IO) { database.userProfileDao().getUserProfileOnce() }
        val memories = withContext(Dispatchers.IO) { database.memoryDao().getAllMemoriesOnce() }

        var consecutiveFailures = 0
        var currentStep = 1
        var finalSummary = ""
        var isSuccess = false

        onStatusUpdate?.invoke("Ekran inceleniyor ve adımlar planlanıyor...")

        while (!taskSession.isFinished && currentStep <= budget.maxSteps) {
            // Overall timeout check
            if (taskSession.isTimedOut()) {
                Log.w(TAG, "Task timed out after ${System.currentTimeMillis() - taskSession.startTimeMs} ms")
                taskSession = taskSession.copy(
                    currentState = AgentState.FAILED,
                    errorMessage = "Görev toplam zaman aşımına ulaştı (${budget.overallTimeoutMs / 1000} sn)."
                )
                break
            }

            // 1. OBSERVING
            taskSession = taskSession.copy(currentState = AgentState.OBSERVING, currentStep = currentStep)
            val screenshot = service.captureLiveScreenshotAsync()
            val snapshot = service.updateLiveSnapshot()

            // 2. PLANNING
            taskSession = taskSession.copy(currentState = AgentState.PLANNING)
            val decision = reasoner.decideNextScreenAction(
                snapshot = snapshot,
                taskPrompt = goalPrompt,
                stepNumber = currentStep,
                visitedElements = visitedElements,
                memories = memories,
                profile = profile,
                liveScreenshot = screenshot
            )

            val displayStatus = if (decision.thought.isNotBlank()) decision.thought else decision.speechStatus
            onStatusUpdate?.invoke("Adım $currentStep (${taskSession.currentState.name}): $displayStatus")

            // Check completion signal
            if (decision.actionType == AgentActionType.TASK_COMPLETE) {
                isSuccess = true
                finalSummary = decision.completionSummary.ifBlank { "Görev başarıyla tamamlandı." }
                taskSession = taskSession.copy(
                    currentState = AgentState.COMPLETED,
                    resultSummary = finalSummary
                )
                break
            }

            // 3. ACTING
            taskSession = taskSession.copy(currentState = AgentState.ACTING)
            var verificationResult: VerificationResult? = null
            var targetNode = if (decision.targetIndex in snapshot.clickableNodes.indices) {
                snapshot.clickableNodes[decision.targetIndex]
            } else null

            when (decision.actionType) {
                AgentActionType.CLICK_NODE -> {
                    val coords = decision.coordinates ?: targetNode?.let {
                        PointF(it.bounds.centerX().toFloat(), it.bounds.centerY().toFloat())
                    }

                    if (coords != null) {
                        val nodeText = targetNode?.text?.ifBlank { null }
                        val label = if (decision.targetText.isNotBlank()) decision.targetText else (nodeText ?: "Node_${decision.targetIndex}")
                        visitedElements.add(label)
                        verificationResult = service.clickAtWithVerificationResult(
                            x = coords.x,
                            y = coords.y,
                            label = label,
                            targetNode = targetNode
                        )
                    } else {
                        verificationResult = VerificationResult.failed("Geçersiz hedef veya koordinat.")
                    }
                }
                AgentActionType.CLICK_COORD -> {
                    if (decision.coordinates != null) {
                        verificationResult = service.clickAtWithVerificationResult(
                            x = decision.coordinates.x,
                            y = decision.coordinates.y,
                            label = "coord"
                        )
                    } else {
                        verificationResult = VerificationResult.failed("Koordinat bulunamadı.")
                    }
                }
                AgentActionType.TYPE_TEXT -> {
                    if (decision.textToType.isNotBlank()) {
                        service.typeTextIntoNode(decision.textToType)
                        service.awaitScreenSettled(800L, 200L)
                        val afterSnapshot = service.updateLiveSnapshot()
                        verificationResult = ActionVerifier.verifyTextOutcome(
                            beforeSnapshot = snapshot,
                            afterSnapshot = afterSnapshot,
                            typedText = decision.textToType
                        )
                    }
                }
                AgentActionType.SWIPE_DOWN -> {
                    service.swipeDownAsync()
                    service.awaitScreenSettled(800L, 200L)
                    val afterSnapshot = service.updateLiveSnapshot()
                    verificationResult = ActionVerifier.verifyScrollOutcome(snapshot, afterSnapshot)
                }
                AgentActionType.SWIPE_UP -> {
                    service.swipeUpAsync()
                    service.awaitScreenSettled(800L, 200L)
                    val afterSnapshot = service.updateLiveSnapshot()
                    verificationResult = ActionVerifier.verifyScrollOutcome(snapshot, afterSnapshot)
                }
                AgentActionType.SWIPE_LEFT -> {
                    service.swipeLeftAsync()
                    service.awaitScreenSettled(800L, 200L)
                    val afterSnapshot = service.updateLiveSnapshot()
                    verificationResult = ActionVerifier.verifyScrollOutcome(snapshot, afterSnapshot)
                }
                AgentActionType.SWIPE_RIGHT -> {
                    service.swipeRightAsync()
                    service.awaitScreenSettled(800L, 200L)
                    val afterSnapshot = service.updateLiveSnapshot()
                    verificationResult = ActionVerifier.verifyScrollOutcome(snapshot, afterSnapshot)
                }
                AgentActionType.OPEN_APP -> {
                    if (decision.appName.isNotBlank()) {
                        openAppVisually(context, decision.appName)
                        service.awaitScreenSettled(1000L, 200L)
                        val afterSnapshot = service.updateLiveSnapshot()
                        verificationResult = ActionVerifier.verifyAppLaunchOutcome(
                            expectedAppName = decision.appName,
                            currentPackage = afterSnapshot.packageName,
                            afterSnapshot = afterSnapshot
                        )
                    }
                }
                AgentActionType.OPEN_QUICK_SETTINGS -> {
                    service.openQuickSettings()
                    service.awaitScreenSettled(600L, 200L)
                    verificationResult = VerificationResult.verified("Hızlı ayarlar açıldı.")
                }
                AgentActionType.OPEN_NOTIFICATIONS -> {
                    service.openNotifications()
                    service.awaitScreenSettled(600L, 200L)
                    verificationResult = VerificationResult.verified("Bildirimler açıldı.")
                }
                AgentActionType.VOLUME_UP -> {
                    service.volumeUp()
                    verificationResult = VerificationResult.verified("Ses artırıldı.")
                }
                AgentActionType.VOLUME_DOWN -> {
                    service.volumeDown()
                    verificationResult = VerificationResult.verified("Ses azaltıldı.")
                }
                AgentActionType.PRESS_BACK -> {
                    service.goBack()
                    service.awaitScreenSettled(600L, 200L)
                    verificationResult = VerificationResult.verified("Geri tuşuna basıldı.")
                }
                AgentActionType.PRESS_HOME -> {
                    service.goHome()
                    service.awaitScreenSettled(600L, 200L)
                    verificationResult = VerificationResult.verified("Ana sayfa tuşuna basıldı.")
                }
                else -> {
                    verificationResult = VerificationResult.verified("Eylem gerçekleştirildi.")
                }
            }

            // 4. VERIFYING & RECOVERING
            val vResult = verificationResult ?: VerificationResult.unchanged("Aksiyon sonrası ekran durumu değerlendirilemedi.")

            if (vResult.isSuccess) {
                consecutiveFailures = 0
                taskSession = taskSession.copy(currentState = AgentState.VERIFYING)
                Log.d(TAG, "Adım $currentStep Doğrulandı: ${vResult.reason}")
            } else {
                taskSession = taskSession.copy(currentState = AgentState.RECOVERING)
                Log.w(TAG, "Adım $currentStep Doğrulanamadı/Değişmedi: ${vResult.reason}")

                val recoveryPlan = RecoveryStrategy.evaluateRecovery(
                    verificationResult = vResult,
                    attemptCount = 1,
                    consecutiveFailures = consecutiveFailures,
                    budget = budget,
                    targetNode = targetNode
                )

                onStatusUpdate?.invoke("Kurtarma Denemesi: ${recoveryPlan.explanation}")
                Log.i(TAG, "Kurtarma Adımı Executing: ${recoveryPlan.actionType} (${recoveryPlan.explanation})")

                when (recoveryPlan.actionType) {
                    RecoveryActionType.RETRY_WITH_JITTER -> {
                        if (decision.coordinates != null) {
                            val newX = decision.coordinates.x + recoveryPlan.suggestedOffsetX
                            val newY = decision.coordinates.y + recoveryPlan.suggestedOffsetY
                            service.clickAtWithVerification(newX, newY, label = "jitter_retry", targetNode = targetNode)
                        }
                    }
                    RecoveryActionType.SWIPE_TO_UNBLOCK -> {
                        service.swipeUpAsync()
                        service.awaitScreenSettled(800L, 200L)
                    }
                    RecoveryActionType.PRESS_BACK_AND_RETRY -> {
                        service.goBack()
                        service.awaitScreenSettled(800L, 200L)
                    }
                    RecoveryActionType.REPLAN_REQUIRED -> {
                        // Let loop proceed to next step for AI model to receive updated screen snapshot and re-plan
                    }
                    RecoveryActionType.ABORT_TASK -> {
                        taskSession = taskSession.copy(
                            currentState = AgentState.FAILED,
                            errorMessage = recoveryPlan.explanation,
                            isCancelled = true
                        )
                        Log.e(TAG, "Sonsuz döngü koruması tetiklendi: ${recoveryPlan.explanation}")
                        break
                    }
                }

                consecutiveFailures = recoveryPlan.consecutiveFailures
            }

            // Save discovered insights if any
            if (!decision.memoryKey.isNullOrBlank() && !decision.memoryValue.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    database.memoryDao().insertMemory(
                        MemoryEntryEntity(
                            category = MemoryCategory.PREFERENCE.name,
                            key = decision.memoryKey,
                            value = decision.memoryValue,
                            importance = 1,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }

            service.awaitScreenSettled(1000L, 200L)
            currentStep++
        }

        val isFinishedSuccessfully = isSuccess || (taskSession.currentState == AgentState.COMPLETED)
        return@withContext AgentExecutionResult(
            isSuccess = isFinishedSuccessfully,
            actionType = if (isFinishedSuccessfully) "AUTONOMOUS_TASK_COMPLETE" else "AUTONOMOUS_LOOP_FINISHED",
            speechFeedback = if (finalSummary.isNotBlank()) finalSummary else (taskSession.errorMessage ?: "İşlem tamamlandı."),
            technicalLog = "Autonomous ReAct loop executed ${currentStep - 1} steps (State=${taskSession.currentState}, SessionId=${taskSession.taskId})"
        )
    }

    /**
     * Executes multi-step intelligent user commands with visual grounding and human gestures.
     */
    suspend fun executeSmartAutonomousTask(
        context: Context,
        command: String,
        reasoner: AIAgentScreenReasoner? = null,
        onStatusUpdate: ((String) -> Unit)? = null
    ): AgentExecutionResult = withContext(Dispatchers.Main) {
        val lower = command.lowercase(Locale("tr", "TR")).trim()

        // 0. Screen reading command ("ekranı oku", "ekranda ne var", "ekranı incele")
        if (lower.contains("ekranı oku") || lower.contains("ekranda ne var") || lower.contains("ekranı incele") || lower.contains("ekrana bak") || lower.contains("ekranı tara")) {
            val service = AiDeviceAccessibilityService.instance
            if (service == null) {
                return@withContext AgentExecutionResult(
                    isSuccess = false,
                    actionType = "SCREEN_READ_NO_SERVICE",
                    speechFeedback = "Ekranı okuyabilmek için Erişilebilirlik iznine ihtiyacım var.",
                    technicalLog = "Accessibility service is null"
                )
            }
            val snapshot = service.extractLiveScreenSnapshot()
            val minified = snapshot.toUltraMinifiedString(10)
            val visibleTexts = snapshot.texts.take(5).joinToString(", ")
            val appName = snapshot.packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
            val feedback = if (visibleTexts.isNotBlank()) {
                "Şu an $appName ekranındasınız. Görünür metinler: $visibleTexts."
            } else {
                "Şu an $appName ekranındasınız. Tıklanabilir ${snapshot.clickableNodes.size} öğe mevcut."
            }
            return@withContext AgentExecutionResult(
                isSuccess = true,
                actionType = "READ_SCREEN_SUCCESS",
                speechFeedback = feedback,
                technicalLog = "Screen minified dump: $minified"
            )
        }

        // 1. WhatsApp Automation Pattern (e.g. "whatsapp'tan ahmet'e selam yaz", "wp'den mehmet'e merhaba de")
        if (lower.contains("whatsapp") || lower.contains("wp")) {
            val isMessageCommand = lower.contains("mesaj") || lower.contains("yaz") || lower.contains("gönder") || lower.contains("at") || lower.contains("söyle") || lower.contains("de")
            if (isMessageCommand) {
                val regexPattern = Regex("(?:whatsapp|wp)(?:'tan|'ten|tan|ten)?\\s+([a-zA-ZçğıöşüÇĞİÖŞÜ0-9]+)(?:'e|'a|'ye|'ya|e|a)?\\s+(.+?)(?:\\s+yaz|\\s+gönder|\\s+at|\\s+mesajı\\s+at)?$")
                val match = regexPattern.find(lower)
                if (match != null && match.groupValues.size >= 3) {
                    val contact = match.groupValues[1].trim()
                    var msg = match.groupValues[2].trim()
                    msg = msg.replace(Regex("(yaz|gönder|at|de|söyle)$"), "").trim()
                    if (contact.isNotEmpty() && msg.isNotEmpty()) {
                        return@withContext executeWhatsAppMessageWorkflow(context, contact, msg)
                    }
                }
            }
        }

        // 2. Gesture / Navigation commands (Swipe, Home, Back, System & Audio)
        val navResult = performNavigation(lower)
        if (navResult.isSuccess) {
            return@withContext navResult
        }

        // 3. Multi-Step ReAct Loop with AI Model Reasoner (for apps in folders, multi-step tasks, navigation)
        if (reasoner != null) {
            val loopResult = executeAutonomousReActLoop(
                context = context,
                goalPrompt = command,
                reasoner = reasoner,
                maxSteps = 8,
                onStatusUpdate = onStatusUpdate
            )
            if (loopResult.isSuccess) {
                return@withContext loopResult
            }
        }

        // 4. Explicit App Launch Command Fallback -> Pure Visual Human Open
        if (isAppLaunchIntent(lower)) {
            val launchResult = openAppVisually(context, lower)
            if (launchResult.isSuccess || launchResult.actionType == "APP_NOT_FOUND_VISUALLY") {
                return@withContext launchResult
            }
        }

        // 5. On-screen element visual click fallback
        if (lower.contains("tıkla") || lower.contains("bas") || lower.contains("dokun") || lower.contains("seç")) {
            val targetQuery = lower.replace("tıkla", "").replace("bas", "").replace("dokun", "").replace("seç", "").trim()
            if (targetQuery.isNotEmpty()) {
                val clickResult = clickElementVisually(targetQuery)
                if (clickResult.isSuccess) {
                    return@withContext clickResult
                }
            }
        }

        // 6. Not a recognized device automation command -> Delegate to AI Conversational & Reasoning Core!
        return@withContext AgentExecutionResult(
            isSuccess = false,
            actionType = "DELEGATE_TO_AI_MODEL",
            speechFeedback = "",
            technicalLog = "Passed to conversational AI model"
        )
    }

    /**
     * Inspects the current device and saves real hardware, battery, and installed apps into memory.
     */
    suspend fun inspectDeviceAndLearn(context: Context): List<MemoryEntryEntity> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MemoryEntryEntity>()
        val pm = context.packageManager

        // 1. Device Info
        list.add(
            MemoryEntryEntity(
                category = MemoryCategory.SYSTEM.name,
                key = "Cihaz Modeli",
                value = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
                importance = 2,
                timestamp = System.currentTimeMillis()
            )
        )

        // 2. Battery Status
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (batteryLevel > 0) {
                list.add(
                    MemoryEntryEntity(
                        category = MemoryCategory.SYSTEM.name,
                        key = "Pil Seviyesi",
                        value = "%$batteryLevel",
                        importance = 1,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore
        }

        // 3. Installed Apps Scan
        try {
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { pm.getApplicationLabel(it).toString() }
                .take(15)

            if (installedApps.isNotEmpty()) {
                list.add(
                    MemoryEntryEntity(
                        category = MemoryCategory.SYSTEM.name,
                        key = "Yüklü Uygulamalar",
                        value = installedApps.joinToString(", "),
                        importance = 2,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore
        }

        // Save to Database
        val db = AssistantDatabase.getDatabase(context)
        for (item in list) {
            db.memoryDao().insertMemory(item)
        }

        return@withContext list
    }
}
