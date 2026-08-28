package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.core.AgentLifecycleManager
import com.example.agent.core.AgentState
import com.example.agent.core.AgentTaskSession
import com.example.agent.core.IntentRouter
import com.example.agent.core.UserIntent
import com.example.ai.AIProvider
import com.example.ai.AIProviderManager
import com.example.ai.ProviderValidationResult
import com.example.service.VoiceAssistantManager
import com.example.data.local.AssistantDatabase
import com.example.data.security.CredentialStore
import com.example.data.local.MemoryFileManager
import com.example.data.model.ChatMessageEntity
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.PersonalityTone
import com.example.data.model.UserProfileEntity
import com.example.data.repository.ChatRepository
import com.example.data.repository.MemoryRepository
import com.example.service.AiDeviceAccessibilityService
import com.example.service.DeviceAgentExecutor
import com.example.service.ScreenSnapshot
import com.example.service.VirtualFingerState
import com.example.ui.components.OrbState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AssistantDatabase.getDatabase(application)
    private val credentialStore = CredentialStore(application)
    private val memoryRepository = MemoryRepository(database.userProfileDao(), database.memoryDao())
    private val chatRepository = ChatRepository(database.chatMessageDao())
    private val aiProviderManager = AIProviderManager(memoryRepository, credentialStore)
    private val screenReasoner = com.example.ai.AIAgentScreenReasoner(aiProviderManager)

    private val _isGenerating = MutableStateFlow(false)
    private val _streamingText = MutableStateFlow("")
    private val _spokenLiveText = MutableStateFlow("")
    private val _isVoiceListening = MutableStateFlow(false)
    private val _isVoiceSpeaking = MutableStateFlow(false)
    private val _isContinuousListening = MutableStateFlow(true)
    private val _isAgentControlling = MutableStateFlow(false)
    private val _remainingControlSeconds = MutableStateFlow(0)
    private val _totalControlSeconds = MutableStateFlow(120)
    private val _currentTaskName = MutableStateFlow("Cihazı Keşfet")
    private val _discoveredCount = MutableStateFlow(0)
    private val _explorationStatusText = MutableStateFlow("")
    private val _isAccessibilityEnabled = MutableStateFlow(false)
    private val _isForegroundServiceRunning = MutableStateFlow(false)
    private val _orbState = MutableStateFlow(OrbState.IDLE)
    private val _selectedModel = MutableStateFlow("")
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var voiceManager: VoiceAssistantManager? = null

    val availableProviders: List<AIProvider> = aiProviderManager.getAvailableProviders()

    init {
        initVoiceManager()
        checkAccessibilityStatus()
        syncExternalDownloadsMemoryOnStartup()
        viewModelScope.launch {
            val profile = memoryRepository.getUserProfileOnce()
            val provider = profile?.preferredAiProvider ?: "gemini"
            _selectedModel.value = aiProviderManager.getSelectedModel(provider)
        }
    }

    /**
     * Reads external JSON memory and system config from Downloads folder on startup if available,
     * ensuring persistence across reinstalls and API keys changes.
     */
    private fun syncExternalDownloadsMemoryOnStartup() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            try {
                // 1. Sync Profile / System Config if missing or existing in Downloads
                val importedProfile = MemoryFileManager.importProfileFromDownloads(context)
                val currentProfile = memoryRepository.getUserProfileOnce()
                if (currentProfile == null && importedProfile != null) {
                    memoryRepository.saveUserProfile(importedProfile)
                    Log.i("AssistantViewModel", "Restored user profile from Downloads/AgentMemory/")
                }

                // 2. Sync Memories if missing or existing in Downloads
                val importedMemories = MemoryFileManager.importMemoriesFromDownloads(context)
                val currentMemories = memoryRepository.getAllMemoriesOnce()
                if (currentMemories.isEmpty() && !importedMemories.isNullOrEmpty()) {
                    for (entry in importedMemories) {
                        memoryRepository.insertMemory(
                            category = MemoryCategory.valueOf(entry.category),
                            key = entry.key,
                            value = entry.value,
                            importance = entry.importance
                        )
                    }
                    Log.i("AssistantViewModel", "Restored ${importedMemories.size} memories from Downloads/AgentMemory/")
                }
            } catch (e: Exception) {
                Log.e("AssistantViewModel", "Error synchronizing external memory on startup", e)
            }
        }
    }

    private fun initVoiceManager() {
        voiceManager = VoiceAssistantManager(
            context = getApplication(),
            onCommandReceived = { text ->
                _spokenLiveText.value = text
                handleSpokenCommand(text)
            }
        )

        viewModelScope.launch {
            voiceManager?.isListening?.collect { listening ->
                _isVoiceListening.value = listening
                if (listening) {
                    _orbState.value = OrbState.SPEAKING
                } else if (!_isGenerating.value && !_isVoiceSpeaking.value && !_isAgentControlling.value) {
                    _orbState.value = OrbState.IDLE
                }
            }
        }

        viewModelScope.launch {
            voiceManager?.isSpeaking?.collect { speaking ->
                _isVoiceSpeaking.value = speaking
                if (speaking) {
                    _orbState.value = OrbState.SPEAKING
                } else if (!_isGenerating.value && !_isVoiceListening.value && !_isAgentControlling.value) {
                    _orbState.value = OrbState.IDLE
                }
            }
        }

        viewModelScope.launch {
            voiceManager?.spokenText?.collect { text ->
                _spokenLiveText.value = text
            }
        }

        viewModelScope.launch {
            voiceManager?.isContinuousModeActive?.collect { continuous ->
                _isContinuousListening.value = continuous
            }
        }

        viewModelScope.launch {
            AgentLifecycleManager.agentState.collect { state ->
                val active = !state.isTerminal && state != AgentState.IDLE
                _isAgentControlling.value = active
                if (active) {
                    _orbState.value = OrbState.THINKING
                } else if (!_isGenerating.value && !_isVoiceListening.value && !_isVoiceSpeaking.value) {
                    _orbState.value = OrbState.IDLE
                }
            }
        }

        viewModelScope.launch {
            AgentLifecycleManager.statusText.collect { status ->
                if (status.isNotBlank()) {
                    _explorationStatusText.value = status
                }
            }
        }

        viewModelScope.launch {
            AgentLifecycleManager.currentSession.collect { session ->
                if (session != null && session.taskGoal.isNotBlank()) {
                    _currentTaskName.value = session.taskGoal
                }
            }
        }

        viewModelScope.launch {
            AiDeviceAccessibilityService.remainingTimeSeconds.collect { rem ->
                _remainingControlSeconds.value = rem
            }
        }

        viewModelScope.launch {
            AiDeviceAccessibilityService.totalControlDurationSeconds.collect { tot ->
                _totalControlSeconds.value = tot
            }
        }

        viewModelScope.launch {
            AiDeviceAccessibilityService.discoveredCount.collect { count ->
                _discoveredCount.value = count
            }
        }
    }

    fun checkAccessibilityStatus() {
        val enabled = AiDeviceAccessibilityService.isAccessibilityEnabled(getApplication()) ||
                AiDeviceAccessibilityService.isServiceActive.value
        _isAccessibilityEnabled.value = enabled
    }

    private val _dbFlow = combine(
        memoryRepository.userProfile,
        memoryRepository.allMemories,
        chatRepository.allMessages
    ) { profile, memories, messages ->
        Triple(profile, memories, messages)
    }

    val uiState: StateFlow<AssistantUiState> = combine(
        _dbFlow,
        _isGenerating,
        _streamingText,
        _spokenLiveText,
        _isVoiceListening,
        _isVoiceSpeaking,
        _isContinuousListening,
        _isAgentControlling,
        _remainingControlSeconds,
        _explorationStatusText,
        _isAccessibilityEnabled,
        _orbState,
        _selectedModel,
        _error,
        AiDeviceAccessibilityService.liveScreenSnapshot,
        AiDeviceAccessibilityService.liveScreenshotBitmap,
        AiDeviceAccessibilityService.virtualFingerState,
        AgentLifecycleManager.agentState,
        AgentLifecycleManager.currentSession
    ) { params ->
        val (profile, memories, messages) = params[0] as Triple<UserProfileEntity?, List<MemoryEntryEntity>, List<ChatMessageEntity>>
        val isGenerating = params[1] as Boolean
        val streamingText = params[2] as String
        val spokenLiveText = params[3] as String
        val isVoiceListening = params[4] as Boolean
        val isVoiceSpeaking = params[5] as Boolean
        val isContinuousListening = params[6] as Boolean
        val isAgentControlling = params[7] as Boolean
        val remainingControlSeconds = params[8] as Int
        val explorationStatusText = params[9] as String
        val isAccessibilityEnabled = params[10] as Boolean
        val orbState = params[11] as OrbState
        val selectedModelRaw = params[12] as String
        val errorMsg = params[13] as String?
        val liveSnapshot = params[14] as ScreenSnapshot
        val liveScreenshot = params[15] as Bitmap?
        val virtualFinger = params[16] as VirtualFingerState?
        val agentState = params[17] as AgentState
        val agentTaskSession = params[18] as AgentTaskSession?

        val activeProvider = profile?.preferredAiProvider ?: "gemini"
        val availableModels = aiProviderManager.getAvailableModels(activeProvider)
        val currentModel = selectedModelRaw.ifBlank { aiProviderManager.getSelectedModel(activeProvider) }

        AssistantUiState(
            isInitialized = true,
            profile = profile,
            memories = memories,
            messages = messages,
            isGenerating = isGenerating,
            streamingText = streamingText,
            spokenLiveText = spokenLiveText,
            isVoiceListening = isVoiceListening,
            isVoiceSpeaking = isVoiceSpeaking,
            isContinuousListening = isContinuousListening,
            isAgentControlling = isAgentControlling,
            agentState = agentState,
            agentTaskSession = agentTaskSession,
            remainingControlSeconds = remainingControlSeconds,
            totalControlSeconds = _totalControlSeconds.value,
            currentTaskName = _currentTaskName.value,
            discoveredCount = _discoveredCount.value,
            explorationStatusText = explorationStatusText,
            isAccessibilityEnabled = isAccessibilityEnabled,
            isForegroundServiceRunning = _isForegroundServiceRunning.value,
            orbState = orbState,
            activeProviderId = activeProvider,
            selectedModel = currentModel,
            availableModels = availableModels,
            liveSnapshot = liveSnapshot,
            liveScreenshot = liveScreenshot,
            virtualFingerState = virtualFinger,
            error = errorMsg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AssistantUiState()
    )

    fun startContinuousVoiceListening() {
        voiceManager?.setContinuousMode(true)
    }

    fun stopContinuousVoiceListening() {
        voiceManager?.setContinuousMode(false)
    }

    fun toggleContinuousVoiceListening() {
        val next = !_isContinuousListening.value
        voiceManager?.setContinuousMode(next)
    }

    fun startVoiceListeningOnce() {
        _spokenLiveText.value = "Dinliyorum..."
        voiceManager?.startListening()
    }

    fun refreshLiveScreen() {
        AiDeviceAccessibilityService.instance?.updateLiveSnapshot()
    }

    fun captureLiveScreenshot() {
        viewModelScope.launch {
            AiDeviceAccessibilityService.instance?.captureLiveScreenshotAsync()
        }
    }

    fun performVirtualTap(x: Float, y: Float, label: String = "") {
        AiDeviceAccessibilityService.instance?.clickAt(x, y, label)
    }

    fun performGlobalAction(actionName: String) {
        viewModelScope.launch {
            DeviceAgentExecutor.performNavigation(actionName)
        }
    }

    fun performVirtualSwipe(swipeDown: Boolean) {
        viewModelScope.launch {
            if (swipeDown) {
                DeviceAgentExecutor.performNavigation("aşağı kaydır")
            } else {
                DeviceAgentExecutor.performNavigation("yukarı kaydır")
            }
        }
    }

    fun handleSpokenCommand(command: String) {
        // On any new command, interrupt ongoing speech to prevent overlap
        stopSpeaking()

        val classification = IntentRouter.classifyIntent(command)

        // 1. Explicit Stop / Cancel commands
        if (classification.isExplicitCancel) {
            IntentRouter.logRoutingDecision(command, classification, agentStarted = false)
            stopAutonomousDeviceControl()
            speakText("Kontrol ve tüm işlemler durduruldu.")
            return
        }

        // If previously controlling and receiving a new non-stop command, clean up prior task first
        if (_isAgentControlling.value) {
            stopAutonomousDeviceControl()
        }

        when (classification.intent) {
            UserIntent.EXPLORATION_TASK -> {
                IntentRouter.logRoutingDecision(command, classification, agentStarted = true)
                val minutes = classification.durationMinutes ?: 15
                startAutonomousDeviceControl(minutes, "Cihazı Keşfet ($minutes dk)")
            }

            UserIntent.DEVICE_TASK -> {
                val isAccessibilityActive = _isAccessibilityEnabled.value || AiDeviceAccessibilityService.isServiceActive.value
                if (!isAccessibilityActive) {
                    IntentRouter.logRoutingDecision(command, classification, agentStarted = false)
                    val feedback = "Bu cihaz işlemini gerçekleştirebilmem için Ayarlar'dan Erişilebilirlik iznini açmanız gerekiyor."
                    speakText(feedback)
                    viewModelScope.launch {
                        chatRepository.sendUserMessage(command)
                        chatRepository.saveAssistantMessage(feedback)
                    }
                    return
                }

                IntentRouter.logRoutingDecision(command, classification, agentStarted = true)
                viewModelScope.launch {
                    // Pause speech listening during autonomous execution to avoid mic feedback loop
                    voiceManager?.stopListening()

                    // ONLY transition UI to agent mode after intent has been strictly validated
                    _orbState.value = OrbState.THINKING
                    _isAgentControlling.value = true
                    _currentTaskName.value = command
                    _explorationStatusText.value = "Görev analiz ediliyor..."

                    val execResult = DeviceAgentExecutor.executeSmartAutonomousTask(
                        context = getApplication(),
                        command = command,
                        reasoner = screenReasoner,
                        onStatusUpdate = { status ->
                            _explorationStatusText.value = status
                        }
                    )

                    _isAgentControlling.value = false
                    _orbState.value = OrbState.IDLE

                    if (execResult.isSuccess || execResult.actionType != "DELEGATE_TO_AI_MODEL") {
                        if (execResult.speechFeedback.isNotEmpty()) {
                            speakText(execResult.speechFeedback)
                        }
                        // Save interaction to chat repository
                        chatRepository.sendUserMessage(command)
                        chatRepository.saveAssistantMessage(execResult.speechFeedback)
                    } else {
                        // Not a device navigation action -> Fallback cleanly to conversation
                        sendMessage(command, speakResponse = true)
                    }
                }
            }

            UserIntent.AMBIGUOUS -> {
                IntentRouter.logRoutingDecision(command, classification, agentStarted = false)
                val clarification = "Tam olarak ne yapmak istediğinizi anlayamadım. Bir uygulama açmamı, mesaj göndermemi veya cihazda bir işlem yapmamı ister misiniz?"
                speakText(clarification)
                viewModelScope.launch {
                    chatRepository.sendUserMessage(command)
                    chatRepository.saveAssistantMessage(clarification)
                }
            }

            UserIntent.CONVERSATIONAL -> {
                IntentRouter.logRoutingDecision(command, classification, agentStarted = false)
                // Pure conversational interaction -> Never triggers agent mode or live screen navigation
                sendMessage(command, speakResponse = true)
            }
        }
    }

    fun startAutonomousDeviceControl(durationMinutes: Int = 30, taskName: String = "Cihazı Keşfet") {
        val service = AiDeviceAccessibilityService.instance
        if (service != null) {
            // Real on-screen touch and navigation exploration
            speakText("$durationMinutes dakika boyunca cihazı canlı parmakla gezeceğim, arayüzü öğrenip hafızama kaydedeceğim. Dilediğin an durdur diyebilirsin.")

            viewModelScope.launch {
                val profile = memoryRepository.getUserProfileOnce()
                service.startTimedAgentControl(
                    context = getApplication<Application>(),
                    durationMinutes = durationMinutes,
                    taskPrompt = taskName,
                    reasoner = screenReasoner,
                    profile = profile,
                    onStatusUpdate = { status ->
                        _explorationStatusText.value = status
                    },
                    onFinished = { learnedCount ->
                        _explorationStatusText.value = "Oturum bitti ($learnedCount bilgi kaydedildi)."
                        speakText("Cihaz kontrol oturumu tamamlandı. $learnedCount yeni bilgi hafızaya alındı. Şimdi senin komutlarını bekliyorum.")
                    }
                )
            }
        } else {
            // Fallback System Diagnostic & App Explorer
            viewModelScope.launch {
                _isAgentControlling.value = true
                _currentTaskName.value = "Sistem ve Donanım Taraması"
                _explorationStatusText.value = "Erişilebilirlik kapalı olduğu için donanım ve yüklü uygulamalar taranıyor..."
                speakText("Cihaz hafızası ve donanım özellikleri inceleniyor...")

                val learned = DeviceAgentExecutor.inspectDeviceAndLearn(getApplication())
                _discoveredCount.value = learned.size
                _explorationStatusText.value = "Cihaz analizi tamamlandı (${learned.size} bilgi hafızaya alındı)."
                speakText("Cihaz analizi tamamlandı. Donanım ve yüklü uygulamaları öğrendim.")
                _isAgentControlling.value = false
            }
        }
    }

    fun stopAutonomousDeviceControl() {
        viewModelScope.launch {
            AgentLifecycleManager.cancelCurrentSession("Kullanıcı tarafından durduruldu.")
        }
        AiDeviceAccessibilityService.instance?.stopAgentControl()
        _isAgentControlling.value = false
        _remainingControlSeconds.value = 0
        speakText("Cihaz kontrolü sonlandırıldı.")
    }

    fun speakText(text: String, isAppend: Boolean = false) {
        val queueMode = if (isAppend) android.speech.tts.TextToSpeech.QUEUE_ADD else android.speech.tts.TextToSpeech.QUEUE_FLUSH
        voiceManager?.speak(text, queueMode)
    }

    fun stopSpeaking() {
        voiceManager?.stopSpeaking()
    }

    fun clearError() {
        _error.value = null
    }

    fun sendMessage(userText: String, speakResponse: Boolean = false) {
        if (userText.isBlank() || _isGenerating.value) return

        // If a new user command or query is sent, immediately interrupt/flush previous speech
        stopSpeaking()

        viewModelScope.launch {
            _isGenerating.value = true
            _orbState.value = OrbState.THINKING
            _streamingText.value = ""

            // Save user message to database
            chatRepository.sendUserMessage(userText)

            val profile = memoryRepository.getUserProfileOnce()
            // Sliding Context Window: Only fetch the last 2 messages (1 Q + 1 A) to keep token size ultra minimal
            val history = chatRepository.getRecentMessages(2)

            // Dynamic Context Loading: Only attach ultra-minified screen context if user is asking about the screen
            val lowerText = userText.lowercase(Locale("tr", "TR"))
            val effectivePrompt = if (lowerText.contains("ekranda ne var") || lowerText.contains("ekranı açıkla") || lowerText.contains("şu an ne açık") || lowerText.contains("ekranı oku")) {
                val snapshot = AiDeviceAccessibilityService.instance?.extractLiveScreenSnapshot()
                if (snapshot != null) {
                    "$userText\n[Mevcut Ekran Verisi:\n${snapshot.toUltraMinifiedString(8)}]"
                } else {
                    userText
                }
            } else {
                userText
            }

            try {
                val fullResponse = StringBuilder()

                // 1. Text streams cleanly to the UI state ONLY without triggering TTS
                aiProviderManager.processUserPrompt(
                    prompt = effectivePrompt,
                    conversationHistory = history,
                    profile = profile,
                    onError = { errorMsg ->
                        _error.value = errorMsg
                        Log.e("AssistantViewModel", "API Error received: $errorMsg")
                    }
                ).collect { chunk ->
                    fullResponse.append(chunk)
                    _streamingText.value = fullResponse.toString()
                }

                val finalMessage = fullResponse.toString().trim()
                chatRepository.saveAssistantMessage(finalMessage)

                // 2. ONLY when generation is 100% complete, send the final complete text to TTS once if requested or in continuous mode
                if ((speakResponse || _isContinuousListening.value) && finalMessage.isNotEmpty()) {
                    speakText(finalMessage, isAppend = false)
                }

            } catch (e: Exception) {
                val errorMsg = "Üzgünüm, yanıt oluşturulurken bir hata oluştu: ${e.localizedMessage}"
                chatRepository.saveAssistantMessage(errorMsg)
                _error.value = "Hata: ${e.localizedMessage}"
                if (speakResponse || _isContinuousListening.value) {
                    speakText("Bir hata oluştu.", isAppend = false)
                }
            } finally {
                _isGenerating.value = false
                _streamingText.value = ""
                if (!_isVoiceListening.value && !_isVoiceSpeaking.value && !_isAgentControlling.value) {
                    _orbState.value = OrbState.IDLE
                }
            }
        }
    }

    suspend fun validateCredentials(providerId: String, apiKey: String): ProviderValidationResult {
        return aiProviderManager.validateProviderCredentials(providerId, apiKey)
    }

    fun completeAwakening(
        aiName: String,
        userName: String,
        tone: PersonalityTone,
        expectation: String,
        providerId: String,
        apiKey: String
    ) {
        viewModelScope.launch {
            if (apiKey.isNotBlank()) {
                credentialStore.saveApiKey(providerId, apiKey)
            }
            val existing = memoryRepository.getUserProfileOnce()
            val updated = (existing ?: UserProfileEntity()).copy(
                aiName = aiName,
                userName = userName,
                personalityTone = tone.name,
                primaryExpectation = expectation,
                preferredAiProvider = providerId,
                isAwakened = true,
                updatedAt = System.currentTimeMillis()
            )
            memoryRepository.saveUserProfile(updated)

            // Export to external JSON in Downloads folder
            val allMemories = memoryRepository.getAllMemoriesOnce()
            MemoryFileManager.exportMemoryToDownloads(getApplication(), updated, allMemories)

            val welcome = "Merhaba $userName, ben $aiName. Sürekli dinleme ve canlı ekran parmak kontrol modundayım. 'WhatsApp'tan Ahmet'e mesaj at', 'YouTube'da kedi ara' veya '30 dk gez' demen yeterli."
            speakText(welcome)
            startContinuousVoiceListening()
        }
    }

    fun addManualMemory(category: MemoryCategory, key: String, value: String) {
        viewModelScope.launch {
            memoryRepository.insertMemory(category, key, value, importance = 2)
            val profile = memoryRepository.getUserProfileOnce()
            val allMemories = memoryRepository.getAllMemoriesOnce()
            MemoryFileManager.exportMemoryToDownloads(getApplication(), profile, allMemories)
        }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id)
            val profile = memoryRepository.getUserProfileOnce()
            val allMemories = memoryRepository.getAllMemoriesOnce()
            MemoryFileManager.exportMemoryToDownloads(getApplication(), profile, allMemories)
        }
    }

    fun updatePersonalityTone(tone: PersonalityTone) {
        viewModelScope.launch {
            memoryRepository.updatePersonalityTone(tone)
            val profile = memoryRepository.getUserProfileOnce()
            val allMemories = memoryRepository.getAllMemoriesOnce()
            MemoryFileManager.exportMemoryToDownloads(getApplication(), profile, allMemories)
        }
    }

    fun updateAiName(name: String) {
        if (name.isNotBlank()) {
            viewModelScope.launch {
                memoryRepository.updateAiName(name.trim())
                val profile = memoryRepository.getUserProfileOnce()
                val allMemories = memoryRepository.getAllMemoriesOnce()
                MemoryFileManager.exportMemoryToDownloads(getApplication(), profile, allMemories)
            }
        }
    }

    fun updateUserName(name: String) {
        if (name.isNotBlank()) {
            viewModelScope.launch {
                memoryRepository.updateUserName(name.trim())
                val profile = memoryRepository.getUserProfileOnce()
                val allMemories = memoryRepository.getAllMemoriesOnce()
                MemoryFileManager.exportMemoryToDownloads(getApplication(), profile, allMemories)
            }
        }
    }

    fun updateCustomApiKey(apiKey: String) {
        viewModelScope.launch {
            val current = memoryRepository.getUserProfileOnce()
            val provider = current?.preferredAiProvider ?: "gemini"
            credentialStore.saveApiKey(provider, apiKey.trim())
            val profile = memoryRepository.getUserProfileOnce()
            val allMemories = memoryRepository.getAllMemoriesOnce()
            MemoryFileManager.exportMemoryToDownloads(getApplication(), profile, allMemories)
        }
    }

    fun updatePreferredProvider(providerId: String) {
        viewModelScope.launch {
            val current = memoryRepository.getUserProfileOnce() ?: return@launch
            val updated = current.copy(preferredAiProvider = providerId, updatedAt = System.currentTimeMillis())
            memoryRepository.saveUserProfile(updated)
            val model = aiProviderManager.getSelectedModel(providerId)
            _selectedModel.value = model
            val allMemories = memoryRepository.getAllMemoriesOnce()
            MemoryFileManager.exportMemoryToDownloads(getApplication(), updated, allMemories)
        }
    }

    fun updateSelectedModel(model: String) {
        viewModelScope.launch {
            val current = memoryRepository.getUserProfileOnce()
            val provider = current?.preferredAiProvider ?: "gemini"
            aiProviderManager.setSelectedModel(provider, model)
            _selectedModel.value = model
            val profile = memoryRepository.getUserProfileOnce()
            val allMemories = memoryRepository.getAllMemoriesOnce()
            MemoryFileManager.exportMemoryToDownloads(getApplication(), profile, allMemories)
        }
    }

    fun getApiKeyForProvider(providerId: String): String {
        return credentialStore.getApiKey(providerId)
    }

    fun saveApiKeyForProvider(providerId: String, apiKey: String) {
        credentialStore.saveApiKey(providerId, apiKey)
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            chatRepository.clearHistory()
        }
    }

    fun replayAwakening() {
        viewModelScope.launch {
            val current = memoryRepository.getUserProfileOnce()
            if (current != null) {
                memoryRepository.saveUserProfile(current.copy(isAwakened = false))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager?.destroy()
    }
}
