package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.AiDeviceAccessibilityService
import com.example.ui.awakening.AwakeningScreen
import com.example.ui.chat.MainAssistantScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.PureWhite
import com.example.ui.viewmodel.AssistantViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = PureWhite
                ) {
                    val isAwakened = uiState.profile?.isAwakened == true

                    Crossfade(
                        targetState = isAwakened,
                        animationSpec = tween(800),
                        label = "main_screen_crossfade"
                    ) { awakened ->
                        if (awakened) {
                            MainAssistantScreen(
                                uiState = uiState,
                                onSendMessage = { prompt -> viewModel.sendMessage(prompt) },
                                onStartVoiceListening = { viewModel.startContinuousVoiceListening() },
                                onStopVoiceListening = { viewModel.stopContinuousVoiceListening() },
                                onToggleVoiceListening = { viewModel.toggleContinuousVoiceListening() },
                                onToggleContinuousMode = { viewModel.toggleContinuousVoiceListening() },
                                onStartExploration = { minutes -> viewModel.startAutonomousDeviceControl(minutes) },
                                onStopAgentControl = { AiDeviceAccessibilityService.instance?.stopAgentControl() },
                                onRefreshLiveScreen = { viewModel.refreshLiveScreen() },
                                onCaptureScreenshot = { viewModel.captureLiveScreenshot() },
                                onNodeClick = { node ->
                                    viewModel.performVirtualTap(
                                        node.bounds.centerX().toFloat(),
                                        node.bounds.centerY().toFloat(),
                                        node.text.ifBlank { node.contentDescription.ifBlank { node.viewId } }
                                    )
                                },
                                onActionHome = { viewModel.performGlobalAction("ana sayfa") },
                                onActionBack = { viewModel.performGlobalAction("geri") },
                                onActionSwipeDown = { viewModel.performVirtualSwipe(true) },
                                onActionSwipeUp = { viewModel.performVirtualSwipe(false) },
                                onActionReadScreen = { viewModel.handleSpokenCommand("ekranı oku") },
                                onAddMemory = { cat, key, value -> viewModel.addManualMemory(cat, key, value) },
                                onDeleteMemory = { id -> viewModel.deleteMemory(id) },
                                onUpdateTone = { tone -> viewModel.updatePersonalityTone(tone) },
                                onUpdateAiName = { name -> viewModel.updateAiName(name) },
                                onUpdateUserName = { name -> viewModel.updateUserName(name) },
                                onUpdateApiKey = { key -> viewModel.updateCustomApiKey(key) },
                                onUpdateProvider = { providerId -> viewModel.updatePreferredProvider(providerId) },
                                onUpdateSelectedModel = { model -> viewModel.updateSelectedModel(model) },
                                onClearChatHistory = { viewModel.clearChatHistory() },
                                onReplayAwakening = { viewModel.replayAwakening() },
                                onRefreshPermissions = { viewModel.checkAccessibilityStatus() },
                                onClearError = { viewModel.clearError() }
                            )
                        } else {
                            AwakeningScreen(
                                availableProviders = viewModel.availableProviders,
                                onValidateCredentials = { providerId, key ->
                                    viewModel.validateCredentials(providerId, key)
                                },
                                onAwakeningComplete = { aiName, userName, tone, expectation, providerId, apiKey ->
                                    viewModel.completeAwakening(aiName, userName, tone, expectation, providerId, apiKey)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkAccessibilityStatus()
    }
}
