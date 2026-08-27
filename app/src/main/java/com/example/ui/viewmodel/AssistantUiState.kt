package com.example.ui.viewmodel

import android.graphics.Bitmap
import com.example.data.model.ChatMessageEntity
import com.example.data.model.MemoryEntryEntity
import com.example.data.model.UserProfileEntity
import com.example.service.ScreenSnapshot
import com.example.service.VirtualFingerState
import com.example.ui.components.OrbState

data class AssistantUiState(
    val isInitialized: Boolean = false,
    val profile: UserProfileEntity? = null,
    val memories: List<MemoryEntryEntity> = emptyList(),
    val messages: List<ChatMessageEntity> = emptyList(),
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val spokenLiveText: String = "",
    val isVoiceListening: Boolean = false,
    val isVoiceSpeaking: Boolean = false,
    val isContinuousListening: Boolean = true,
    val isAgentControlling: Boolean = false,
    val remainingControlSeconds: Int = 0,
    val totalControlSeconds: Int = 120,
    val currentTaskName: String = "Cihazı Keşfet",
    val discoveredCount: Int = 0,
    val explorationStatusText: String = "",
    val isAccessibilityEnabled: Boolean = false,
    val isForegroundServiceRunning: Boolean = false,
    val orbState: OrbState = OrbState.IDLE,
    val activeProviderId: String = "gemini",
    val selectedModel: String = "",
    val availableModels: List<String> = emptyList(),
    val liveSnapshot: ScreenSnapshot = ScreenSnapshot(
        packageName = "",
        activityName = "",
        nodeCount = 0,
        texts = emptyList(),
        clickableNodes = emptyList()
    ),
    val liveScreenshot: Bitmap? = null,
    val virtualFingerState: VirtualFingerState? = null,
    val error: String? = null
)
