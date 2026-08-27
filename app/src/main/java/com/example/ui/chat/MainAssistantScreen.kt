package com.example.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.model.MemoryCategory
import com.example.data.model.PersonalityTone
import com.example.service.AiDeviceAccessibilityService
import com.example.service.ScreenNodeData
import com.example.ui.awakening.WhiteSpatialDepthBackground
import com.example.ui.components.LiveScreenVisionCard
import com.example.ui.components.MemoryInspectorSheet
import com.example.ui.components.OrbState
import com.example.ui.components.SettingsSheet
import com.example.ui.theme.AppleBlue
import com.example.ui.theme.CharcoalCore
import com.example.ui.theme.DarkGraphite
import com.example.ui.theme.GentleRose
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SubtleBorderGray
import com.example.ui.theme.SubtleGrayBg
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.AssistantUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAssistantScreen(
    uiState: AssistantUiState,
    onSendMessage: (String) -> Unit,
    onStartVoiceListening: () -> Unit,
    onStopVoiceListening: () -> Unit,
    onToggleVoiceListening: () -> Unit,
    onToggleContinuousMode: () -> Unit,
    onStartExploration: (durationMinutes: Int) -> Unit,
    onStopAgentControl: () -> Unit,
    onRefreshLiveScreen: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onNodeClick: (ScreenNodeData) -> Unit,
    onActionHome: () -> Unit,
    onActionBack: () -> Unit,
    onActionSwipeDown: () -> Unit,
    onActionSwipeUp: () -> Unit,
    onActionReadScreen: () -> Unit,
    onAddMemory: (MemoryCategory, String, String) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onUpdateTone: (PersonalityTone) -> Unit,
    onUpdateAiName: (String) -> Unit,
    onUpdateUserName: (String) -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onUpdateProvider: (String) -> Unit = {},
    onUpdateSelectedModel: (String) -> Unit = {},
    onClearChatHistory: () -> Unit,
    onReplayAwakening: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onClearError: () -> Unit = {}
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showMemorySheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showPermissionsDialog by remember { mutableStateOf(false) }
    var showLiveVisionHUD by remember { mutableStateOf(false) }
    var selectedDurationMinutes by remember { mutableIntStateOf(30) }

    val memorySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val aiName = uiState.profile?.aiName?.ifBlank { "Nova" } ?: "Nova"

    // Permission state
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            onStartVoiceListening()
        } else {
            Toast.makeText(context, "Sürekli sesli konuşma için mikrofon izni gereklidir", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        onRefreshPermissions()
        if (hasMicPermission && uiState.isContinuousListening && !uiState.isVoiceListening) {
            onStartVoiceListening()
        }
    }

    // Error Notification Observer
    LaunchedEffect(uiState.error) {
        uiState.error?.let { msg ->
            snackbarHostState.showSnackbar(
                message = msg,
                withDismissAction = true
            )
            onClearError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PureWhite)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        // Pure White Ambient Lighting Background
        WhiteSpatialDepthBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ----------------- TOP STATUS & SYSTEM CONTROLS -----------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Continuous Listening Status Chip (Clickable to toggle)
                Surface(
                    color = if (uiState.isContinuousListening) SubtleGrayBg else Color(0xFFFEE2E2),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .border(1.dp, SubtleBorderGray, RoundedCornerShape(20.dp))
                        .clickable { onToggleContinuousMode() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    when {
                                        uiState.isAgentControlling -> AppleBlue
                                        uiState.isVoiceListening -> SuccessGreen
                                        uiState.isVoiceSpeaking -> ObsidianBlack
                                        uiState.isContinuousListening -> SuccessGreen
                                        else -> GentleRose
                                    },
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = when {
                                uiState.isAgentControlling -> "Cihazı Geziyor"
                                uiState.isVoiceSpeaking -> "Konuşuyor"
                                uiState.isVoiceListening -> "Sürekli Dinliyor"
                                uiState.isContinuousListening -> "Sürekli Dinleme Açık"
                                else -> "Dinleme Kapalı"
                            },
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Action icons: Live Screen Vision, Permissions, Memory Drawer, Settings
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { showLiveVisionHUD = !showLiveVisionHUD },
                        modifier = Modifier.testTag("btn_toggle_vision_hud")
                    ) {
                        Icon(
                            imageVector = if (showLiveVisionHUD) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Canlı Ekran Görüşü",
                            tint = if (showLiveVisionHUD) AppleBlue else DarkGraphite,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = { showPermissionsDialog = true },
                        modifier = Modifier.testTag("permissions_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "İzinler",
                            tint = if (uiState.isAccessibilityEnabled && hasMicPermission) SuccessGreen else TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = { showMemorySheet = true },
                        modifier = Modifier.testTag("memory_button")
                    ) {
                        Box {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = "Hafıza",
                                tint = DarkGraphite,
                                modifier = Modifier.size(22.dp)
                            )
                            if (uiState.memories.isNotEmpty()) {
                                Surface(
                                    color = ObsidianBlack,
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(15.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = uiState.memories.size.toString(),
                                            color = PureWhite,
                                            fontSize = 8.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    IconButton(
                        onClick = { showSettingsSheet = true },
                        modifier = Modifier.testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Ayarlar",
                            tint = DarkGraphite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ----------------- LIVE SCREEN VISION & VIRTUAL FINGER RADAR (WHEN EXPANDED OR CONTROLLING) -----------------
            AnimatedVisibility(
                visible = showLiveVisionHUD || uiState.isAgentControlling,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                    LiveScreenVisionCard(
                        snapshot = uiState.liveSnapshot,
                        screenshot = uiState.liveScreenshot,
                        virtualFinger = uiState.virtualFingerState,
                        isAccessibilityActive = uiState.isAccessibilityEnabled,
                        onRefresh = onRefreshLiveScreen,
                        onCaptureScreenshot = onCaptureScreenshot,
                        onNodeClick = onNodeClick,
                        onActionHome = onActionHome,
                        onActionBack = onActionBack,
                        onActionSwipeDown = onActionSwipeDown,
                        onActionSwipeUp = onActionSwipeUp,
                        onActionReadScreen = onActionReadScreen
                    )
                }
            }

            // ----------------- CENTER LIVING CONSCIOUSNESS & VOICE DIALOGUE -----------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Living Orb with dynamic reactive aura
                ConsciousLivingOrb(
                    orbState = uiState.orbState,
                    isListening = uiState.isVoiceListening,
                    isSpeaking = uiState.isVoiceSpeaking,
                    isExploring = uiState.isAgentControlling
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Real-time voice transcription, agent action, or prompt dialogue
                val displayDialogue = when {
                    uiState.isAgentControlling && uiState.explorationStatusText.isNotBlank() ->
                        uiState.explorationStatusText
                    uiState.isVoiceListening && uiState.spokenLiveText.isNotBlank() ->
                        "“${uiState.spokenLiveText}”"
                    uiState.isVoiceSpeaking && uiState.streamingText.isNotBlank() ->
                        uiState.streamingText
                    uiState.streamingText.isNotBlank() ->
                        uiState.streamingText
                    uiState.isVoiceListening ->
                        "Seni dinliyorum, konuşabilirsin..."
                    else ->
                        "“YouTube'da arama yap”, “30 dakika gez” veya “Ekranı oku” demen yeterli."
                }

                Text(
                    text = displayDialogue,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = (-0.2).sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (uiState.isAgentControlling) {
                        val mins = uiState.remainingControlSeconds / 60
                        val secs = uiState.remainingControlSeconds % 60
                        "Kalan Süre: ${String.format("%02d:%02d", mins, secs)} • Öğrenilen: ${uiState.discoveredCount}"
                    } else {
                        "İnsan gibi parmaklarıyla tıklar, gezer ve arayüzü canlı görür."
                    },
                    color = if (uiState.isAgentControlling) AppleBlue else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (uiState.isAgentControlling) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ----------------- BOTTOM CONTROLS & TIMED EXPLORATION -----------------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Duration Selectors if not controlling
                AnimatedVisibility(visible = !uiState.isAgentControlling) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(5, 15, 30, 60).forEach { mins ->
                            val isSel = selectedDurationMinutes == mins
                            Surface(
                                color = if (isSel) ObsidianBlack else SubtleGrayBg,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .padding(horizontal = 4.dp)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSel) ObsidianBlack else SubtleBorderGray,
                                        shape = RoundedCornerShape(14.dp)
                                    )
                                    .clickable { selectedDurationMinutes = mins }
                            ) {
                                Text(
                                    text = "$mins Dk",
                                    color = if (isSel) PureWhite else TextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Autonomous Agent Control Card / Keşfet Tuşu
                Surface(
                    color = PureWhite,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x10000000))
                        .border(1.dp, SubtleBorderGray, RoundedCornerShape(24.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(
                                        if (uiState.isAgentControlling) GentleRose.copy(alpha = 0.15f) else SubtleGrayBg,
                                        CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (uiState.isAgentControlling) Icons.Default.Stop else Icons.Default.Explore,
                                    contentDescription = null,
                                    tint = if (uiState.isAgentControlling) GentleRose else ObsidianBlack,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = if (uiState.isAgentControlling) "Cihazı Canlı Geziyor..." else "Cihazı Keşfet ($selectedDurationMinutes Dk)",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (uiState.isAgentControlling) "Canlı parmakla gezip hafızaya yazar" else "Telefonu kurcalar & her yeri öğrenir",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (uiState.isAgentControlling) {
                                    onStopAgentControl()
                                } else {
                                    onStartExploration(selectedDurationMinutes)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.isAgentControlling) GentleRose else ObsidianBlack
                            ),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Text(
                                text = if (uiState.isAgentControlling) "Durdur" else "Keşfet",
                                color = PureWhite,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Microphone Living Pulse Button
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    if (uiState.isVoiceListening) {
                        AcousticPulseRing()
                    }

                    Surface(
                        color = if (uiState.isVoiceListening) ObsidianBlack else PureWhite,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(68.dp)
                            .shadow(6.dp, CircleShape, ambientColor = Color(0x20000000))
                            .border(
                                width = if (uiState.isVoiceListening) 0.dp else 1.5.dp,
                                color = SubtleBorderGray,
                                shape = CircleShape
                            )
                            .clickable {
                                if (hasMicPermission) {
                                    onToggleVoiceListening()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            .testTag("voice_mic_main_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (uiState.isVoiceListening) Icons.Default.Mic else Icons.Default.MicNone,
                                contentDescription = "Sesli Konuş",
                                tint = if (uiState.isVoiceListening) PureWhite else ObsidianBlack,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (uiState.isVoiceListening) "Seni Dinliyor • Durdurmak için dokun" else "Konuşmak için dokun",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }

        // Snackbar Host at the bottom
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp),
            snackbar = { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFFFEE2E2), // Soft red background
                    contentColor = Color(0xFFB91C1C), // Deep red text
                    dismissActionContentColor = Color(0xFFB91C1C),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        )
    }

    // ----------------- PERMISSIONS DIALOG (ACCESSIBILITY & RECORD AUDIO) -----------------
    if (showPermissionsDialog) {
        PermissionsModalDialog(
            isAccessibilityEnabled = uiState.isAccessibilityEnabled,
            hasMicPermission = hasMicPermission,
            onRequestMic = {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            onOpenAccessibilitySettings = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            },
            onDismiss = {
                showPermissionsDialog = false
                onRefreshPermissions()
            }
        )
    }

    // ----------------- MEMORY INSPECTOR SHEET -----------------
    if (showMemorySheet) {
        MemoryInspectorSheet(
            sheetState = memorySheetState,
            memories = uiState.memories,
            aiName = aiName,
            selectedProviderId = uiState.activeProviderId,
            selectedModel = uiState.selectedModel,
            availableModels = uiState.availableModels,
            onDismiss = { showMemorySheet = false },
            onUpdateSelectedModel = onUpdateSelectedModel,
            onAddMemory = onAddMemory,
            onDeleteMemory = onDeleteMemory
        )
    }

    // ----------------- SETTINGS SHEET -----------------
    if (showSettingsSheet) {
        SettingsSheet(
            sheetState = settingsSheetState,
            profile = uiState.profile,
            selectedProviderId = uiState.activeProviderId,
            selectedModel = uiState.selectedModel,
            availableModels = uiState.availableModels,
            onDismiss = { showSettingsSheet = false },
            onUpdateProvider = onUpdateProvider,
            onUpdateSelectedModel = onUpdateSelectedModel,
            onUpdateTone = onUpdateTone,
            onUpdateAiName = onUpdateAiName,
            onUpdateUserName = onUpdateUserName,
            onUpdateApiKey = onUpdateApiKey,
            onReplayAwakening = {
                showSettingsSheet = false
                onReplayAwakening()
            },
            onClearChatHistory = {
                onClearChatHistory()
                showSettingsSheet = false
                Toast.makeText(context, "Sohbet geçmişi temizlendi", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

// ----------------- LIVING CONSCIOUSNESS ORB -----------------

@Composable
fun ConsciousLivingOrb(
    orbState: OrbState,
    isListening: Boolean,
    isSpeaking: Boolean,
    isExploring: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = if (isListening || isSpeaking || isExploring) 1.08f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening || isSpeaking) 700 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val auraAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (isListening || isSpeaking) 0.85f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening || isSpeaking) 800 else 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aura_alpha"
    )

    Box(
        modifier = Modifier.size(190.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer Delicate Aura Waves
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .scale(pulseScale)
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.width * 0.45f

            drawCircle(
                color = SubtleBorderGray.copy(alpha = auraAlpha),
                radius = outerRadius,
                center = center,
                style = Stroke(width = 1.2.dp.toPx())
            )

            drawCircle(
                color = Color(0xFFE2E8F0).copy(alpha = auraAlpha * 0.6f),
                radius = outerRadius - 14.dp.toPx(),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Central Obsidian Black Core
        Box(
            modifier = Modifier.size(90.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2f

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            CharcoalCore,
                            ObsidianBlack,
                            Color(0xFF000000)
                        ),
                        center = Offset(center.x - radius * 0.25f, center.y - radius * 0.25f),
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )

                // Top rim light
                drawCircle(
                    color = Color(0x33FFFFFF),
                    radius = radius - 0.5.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }
    }
}

@Composable
fun AcousticPulseRing() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_ring")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha"
    )

    Box(
        modifier = Modifier
            .size(68.dp)
            .scale(scale)
            .background(ObsidianBlack.copy(alpha = alpha), CircleShape)
    )
}

// ----------------- PERMISSIONS MODAL DIALOG -----------------

@Composable
fun PermissionsModalDialog(
    isAccessibilityEnabled: Boolean,
    hasMicPermission: Boolean,
    onRequestMic: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = Color(0x80000000),
        modifier = Modifier
            .fillMaxSize()
            .clickable { onDismiss() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = PureWhite),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(24.dp))
                    .clickable(enabled = false) {}
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "Gerekli İzinler & Yetkiler",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "AI Asistanın telefonu gezmesi, ekranı canlı görüp parmaklarıyla yönetmesi ve sesinizi sürekli dinlemesi için bu izinler gereklidir.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // 1. Accessibility Service
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SubtleGrayBg, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Erişilebilirlik Servisi",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (isAccessibilityEnabled) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                                }
                            }
                            Text(
                                text = if (isAccessibilityEnabled) "Aktif (Canlı ekran görüşü ve parmak kontrolü devrede)" else "Kapalı (Ayarlardan açılmalı)",
                                color = if (isAccessibilityEnabled) SuccessGreen else TextSecondary,
                                fontSize = 11.sp
                            )
                        }

                        if (!isAccessibilityEnabled) {
                            Button(
                                onClick = onOpenAccessibilitySettings,
                                colors = ButtonDefaults.buttonColors(containerColor = ObsidianBlack),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Aç", fontSize = 12.sp, color = PureWhite)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2. Microphone Permission
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SubtleGrayBg, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Mikrofon İzni",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (hasMicPermission) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                                }
                            }
                            Text(
                                text = if (hasMicPermission) "Verildi" else "Gerekli (Sürekli dinleme için)",
                                color = if (hasMicPermission) SuccessGreen else TextSecondary,
                                fontSize = 11.sp
                            )
                        }

                        if (!hasMicPermission) {
                            Button(
                                onClick = onRequestMic,
                                colors = ButtonDefaults.buttonColors(containerColor = ObsidianBlack),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("İzin Ver", fontSize = 12.sp, color = PureWhite)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianBlack),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth().height(46.dp)
                    ) {
                        Text("Tamam", color = PureWhite, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
