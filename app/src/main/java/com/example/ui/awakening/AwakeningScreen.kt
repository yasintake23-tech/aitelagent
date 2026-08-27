package com.example.ui.awakening

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.example.ai.AIProvider
import com.example.ai.ProviderValidationResult
import com.example.data.model.PersonalityTone
import com.example.ui.theme.CharcoalCore
import com.example.ui.theme.DarkGraphite
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.OffWhiteCanvas
import com.example.ui.theme.PureWhite
import com.example.ui.theme.SubtleBorderGray
import com.example.ui.theme.SubtleGrayBg
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

enum class AwakeningStage {
    CHOOSE_BRAIN_PROMPT,        // Question appears: "Beynimi seçmek ister misin?"
    BRAIN_CAROUSEL,             // Apple-style horizontal provider selection carousel
    API_EXPLANATION_AND_INPUT,  // "Güzel seçim. Şimdi beni çalıştırmak için birkaç bilgiye ihtiyacım var."
    VERIFICATION_ACTIVE,        // Living sphere active with real status: "Bağlantı kuruluyor..."
    BREAKOUT_SURGE,             // Sphere pauses, then breaks through the top circle boundary!
    HELLO_TRANSFORMATION,       // Sphere decelerates and smoothly morphs into "Merhaba."
    FIRST_SIGHT,                // "Seni yeni görüyorum."
    ASK_AI_NAME,                // "Bana bir isim vermek ister misin?"
    AI_NAME_ACK,                // "Nova... Güzel."
    ASK_USER_NAME               // "Peki ya sen? Sana nasıl hitap etmeliyim?"
}

data class ProviderDisplayItem(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val description: String,
    val requiresKey: Boolean,
    val placeholder: String
)

@Composable
fun AwakeningScreen(
    availableProviders: List<AIProvider> = emptyList(),
    onValidateCredentials: suspend (providerId: String, key: String) -> ProviderValidationResult = { _, _ -> ProviderValidationResult(true) },
    onAwakeningComplete: (aiName: String, userName: String, tone: PersonalityTone, expectation: String, providerId: String, apiKey: String) -> Unit
) {
    var stage by remember { mutableStateOf(AwakeningStage.CHOOSE_BRAIN_PROMPT) }
    var selectedProviderId by remember { mutableStateOf("gemini") }
    var enteredApiKey by remember { mutableStateOf("") }
    var enteredAiName by remember { mutableStateOf("Nova") }
    var enteredUserName by remember { mutableStateOf("") }

    // Verification live state
    var verificationStepText by remember { mutableStateOf("Bağlantı kuruluyor...") }
    var verificationError by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

    // Smooth stage timeline transitions
    LaunchedEffect(stage) {
        when (stage) {
            AwakeningStage.BREAKOUT_SURGE -> {
                delay(1800)
                stage = AwakeningStage.HELLO_TRANSFORMATION
            }
            AwakeningStage.HELLO_TRANSFORMATION -> {
                delay(2200)
                stage = AwakeningStage.FIRST_SIGHT
            }
            AwakeningStage.FIRST_SIGHT -> {
                delay(1800)
                stage = AwakeningStage.ASK_AI_NAME
            }
            AwakeningStage.AI_NAME_ACK -> {
                delay(2200)
                stage = AwakeningStage.ASK_USER_NAME
            }
            else -> { /* User interaction controlled */ }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OffWhiteCanvas)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        // Subtle, barely noticeable spatial depth (pure white to very soft light gray)
        WhiteSpatialDepthBackground()

        AnimatedContent(
            targetState = stage,
            transitionSpec = {
                (fadeIn(animationSpec = tween(400, easing = FastOutSlowInEasing)) +
                        scaleIn(initialScale = 0.98f, animationSpec = tween(400)))
                    .togetherWith(
                        fadeOut(animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                                scaleOut(targetScale = 1.02f, animationSpec = tween(300))
                    )
            },
            label = "awakening_stage_content"
        ) { currentStage ->
            when (currentStage) {
                AwakeningStage.CHOOSE_BRAIN_PROMPT -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Spacer(modifier = Modifier.height(20.dp))

                        // Center minimalist AI Circle with floating organic black sphere
                        LivingCoreView(isEnergetic = false)

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(bottom = 32.dp)
                        ) {
                            Text(
                                text = "Beynimi seçmek ister misin?",
                                color = TextPrimary,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Normal,
                                letterSpacing = (-0.5).sp,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(28.dp))

                            Button(
                                onClick = { stage = AwakeningStage.BRAIN_CAROUSEL },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ObsidianBlack
                                ),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .height(52.dp)
                                    .fillMaxWidth(0.65f)
                                    .shadow(6.dp, RoundedCornerShape(24.dp), ambientColor = Color(0x22000000))
                                    .testTag("start_brain_selection_button")
                            ) {
                                Text(
                                    text = "Başlayalım",
                                    color = PureWhite,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = PureWhite,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                AwakeningStage.BRAIN_CAROUSEL -> {
                    BrainCarouselView(
                        onProviderSelected = { provider ->
                            selectedProviderId = provider.id
                            if (!provider.requiresKey) {
                                enteredApiKey = ""
                                stage = AwakeningStage.VERIFICATION_ACTIVE
                                coroutineScope.launch {
                                    runVerificationProcess(
                                        providerId = provider.id,
                                        apiKey = "",
                                        onValidate = onValidateCredentials,
                                        onStepUpdate = { verificationStepText = it },
                                        onError = { verificationError = it },
                                        onSuccess = { stage = AwakeningStage.BREAKOUT_SURGE }
                                    )
                                }
                            } else {
                                stage = AwakeningStage.API_EXPLANATION_AND_INPUT
                            }
                        }
                    )
                }

                AwakeningStage.API_EXPLANATION_AND_INPUT -> {
                    ApiExplanationAndInputView(
                        providerId = selectedProviderId,
                        initialApiKey = enteredApiKey,
                        onSubmitKey = { key ->
                            enteredApiKey = key
                            stage = AwakeningStage.VERIFICATION_ACTIVE
                            coroutineScope.launch {
                                runVerificationProcess(
                                    providerId = selectedProviderId,
                                    apiKey = key,
                                    onValidate = onValidateCredentials,
                                    onStepUpdate = { verificationStepText = it },
                                    onError = { verificationError = it },
                                    onSuccess = { stage = AwakeningStage.BREAKOUT_SURGE }
                                )
                            }
                        },
                        onBack = { stage = AwakeningStage.BRAIN_CAROUSEL }
                    )
                }

                AwakeningStage.VERIFICATION_ACTIVE -> {
                    VerificationActiveView(
                        stepText = verificationStepText,
                        errorMessage = verificationError,
                        onRetry = {
                            verificationError = null
                            coroutineScope.launch {
                                runVerificationProcess(
                                    providerId = selectedProviderId,
                                    apiKey = enteredApiKey,
                                    onValidate = onValidateCredentials,
                                    onStepUpdate = { verificationStepText = it },
                                    onError = { verificationError = it },
                                    onSuccess = { stage = AwakeningStage.BREAKOUT_SURGE }
                                )
                            }
                        },
                        onChangeProvider = {
                            verificationError = null
                            stage = AwakeningStage.BRAIN_CAROUSEL
                        }
                    )
                }

                AwakeningStage.BREAKOUT_SURGE -> {
                    BreakoutSurgeView()
                }

                AwakeningStage.HELLO_TRANSFORMATION -> {
                    HelloTransformationView()
                }

                AwakeningStage.FIRST_SIGHT -> {
                    FirstSightView()
                }

                AwakeningStage.ASK_AI_NAME -> {
                    AskAiNameView(
                        initialName = enteredAiName,
                        onSubmitName = { name ->
                            enteredAiName = name.ifBlank { "Nova" }
                            stage = AwakeningStage.AI_NAME_ACK
                        }
                    )
                }

                AwakeningStage.AI_NAME_ACK -> {
                    AiNameAckView(aiName = enteredAiName)
                }

                AwakeningStage.ASK_USER_NAME -> {
                    AskUserNameView(
                        onSubmitUserName = { name ->
                            enteredUserName = name.ifBlank { "Dostum" }
                            onAwakeningComplete(
                                enteredAiName,
                                enteredUserName,
                                PersonalityTone.SAMIMI,
                                "Günlük Yaşam & Genel Asistan",
                                selectedProviderId,
                                enteredApiKey
                            )
                        }
                    )
                }
            }
        }
    }
}

// ---------------------- 3. SUBTLE WHITE SPATIAL DEPTH ----------------------

@Composable
fun WhiteSpatialDepthBackground() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height * 0.35f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    PureWhite,
                    Color(0xFFF6F8FB),
                    Color(0xFFEFF2F7)
                ),
                center = center,
                radius = size.width * 0.95f
            ),
            radius = size.width * 0.95f,
            center = center
        )
    }
}

// ---------------------- 4. AI ÇEKİRDEĞİ (ORGANIC FLOATING BLACK SPHERE IN CIRCLE) ----------------------

@Composable
fun LivingCoreView(
    isEnergetic: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sphere_physics")

    // Floating harmonic organic physics: drifts up, pulled gently down, rebounds
    val offsetY by infiniteTransition.animateFloat(
        initialValue = -20f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isEnergetic) 900 else 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sphere_y"
    )

    val offsetX by infiniteTransition.animateFloat(
        initialValue = -14f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isEnergetic) 1300 else 3400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sphere_x"
    )

    val shadowScale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isEnergetic) 900 else 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shadow_scale"
    )

    Box(
        modifier = Modifier.size(220.dp),
        contentAlignment = Alignment.Center
    ) {
        // Crisp, delicate circle boundary on white canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val boundaryRadius = 88.dp.toPx()

            // Subtle outer perimeter ring
            drawCircle(
                color = SubtleBorderGray,
                radius = boundaryRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            // Extremely faint inner aura ring
            drawCircle(
                color = Color(0xFFE2E8F0).copy(alpha = if (isEnergetic) 0.8f else 0.4f),
                radius = boundaryRadius - 8.dp.toPx(),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Soft contact shadow underneath sphere inside the circle
        Canvas(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt() / 2, (offsetY.roundToInt() / 2) + 36) }
                .size(46.dp)
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x18000000), Color.Transparent),
                    center = center,
                    radius = (size.width / 2f) * shadowScale
                ),
                radius = (size.width / 2f) * shadowScale,
                center = center
            )
        }

        // Deep obsidian black sphere with organic physics
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(if (isEnergetic) 62.dp else 54.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2f

                // Deep Charcoal to Obsidian Sphere with soft 3D top-left light highlight
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            CharcoalCore,
                            ObsidianBlack,
                            Color(0xFF000000)
                        ),
                        center = Offset(center.x - radius * 0.3f, center.y - radius * 0.3f),
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )

                // Very subtle top rim light reflection
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

// ---------------------- 6 & 7. API SEÇİMİ (APPLE-STYLE HORIZONTAL PAGER CAROUSEL) ----------------------

@Composable
fun BrainCarouselView(
    onProviderSelected: (ProviderDisplayItem) -> Unit
) {
    val providers = remember {
        listOf(
            ProviderDisplayItem(
                id = "gemini",
                name = "Google Gemini",
                icon = Icons.Default.AutoAwesome,
                description = "Google'ın yapay zekâ modelleri.",
                requiresKey = true,
                placeholder = "AIzaSy..."
            ),
            ProviderDisplayItem(
                id = "huggingface",
                name = "Hugging Face",
                icon = Icons.Default.Hub,
                description = "Açık modeller ve çeşitli API seçenekleri.",
                requiresKey = true,
                placeholder = "hf_..."
            ),
            ProviderDisplayItem(
                id = "groq",
                name = "Groq Cloud",
                icon = Icons.Default.FlashOn,
                description = "Yüksek hızlı açık modeller.",
                requiresKey = true,
                placeholder = "gsk_..."
            ),
            ProviderDisplayItem(
                id = "local",
                name = "Cihaz İçi Yerel Çekirdek",
                icon = Icons.Default.Psychology,
                description = "Çevrimdışı çalışan yerel zekâ.",
                requiresKey = false,
                placeholder = ""
            )
        )
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { providers.size }
    )
    val coroutineScope = rememberCoroutineScope()
    val activeProvider = providers[pagerState.currentPage]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Minimal Title
        Text(
            text = "Beyin Seçimi",
            color = TextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )

        // Apple-style horizontal pager carousel
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 60.dp),
            pageSpacing = 16.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
        ) { page ->
            val provider = providers[page]
            val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
            val scale = lerp(0.90f, 1.04f, 1f - pageOffset.coerceIn(0f, 1f))
            val alpha = lerp(0.50f, 1f, 1f - pageOffset.coerceIn(0f, 1f))
            val isSelected = pagerState.currentPage == page

            Card(
                colors = CardDefaults.cardColors(containerColor = PureWhite),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .shadow(
                        elevation = if (isSelected) 8.dp else 2.dp,
                        shape = RoundedCornerShape(22.dp),
                        ambientColor = Color(0x14000000)
                    )
                    .border(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) ObsidianBlack else SubtleBorderGray,
                        shape = RoundedCornerShape(22.dp)
                    )
                    .clickable {
                        if (isSelected) {
                            onProviderSelected(provider)
                        } else {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(page)
                            }
                        }
                    }
                    .testTag("provider_card_${provider.id}")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.Start
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .background(
                                if (isSelected) ObsidianBlack else SubtleGrayBg,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = provider.icon,
                            contentDescription = null,
                            tint = if (isSelected) PureWhite else DarkGraphite,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column {
                        Text(
                            text = provider.name,
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = provider.description,
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        // Bottom Clean Action
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Button(
                onClick = { onProviderSelected(activeProvider) },
                colors = ButtonDefaults.buttonColors(containerColor = ObsidianBlack),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth(0.75f)
                    .height(50.dp)
                    .testTag("confirm_provider_selection_button")
            ) {
                Text(
                    text = "Seç",
                    color = PureWhite,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ---------------------- 8. API SEÇİMİNDEN SONRA & BİLGİ ALMA ----------------------

@Composable
fun ApiExplanationAndInputView(
    providerId: String,
    initialApiKey: String,
    onSubmitKey: (String) -> Unit,
    onBack: () -> Unit
) {
    var apiKey by remember { mutableStateOf(initialApiKey) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var showSecondaryText by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(600)
        showSecondaryText = true
    }

    val placeholder = when (providerId.lowercase()) {
        "gemini" -> "AIzaSy..."
        "huggingface" -> "hf_..."
        "groq" -> "gsk_..."
        else -> "API Anahtarı..."
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Minimal Dialogue
        Column {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Güzel seçim.",
                color = TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Normal
            )

            AnimatedVisibility(visible = showSecondaryText) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Şimdi seni çalıştırmak için birkaç bilgiye ihtiyacım var.",
                        color = TextSecondary,
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    )
                }
            }
        }

        // Clean Input Field
        Column(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = { Text(placeholder, color = TextMuted, fontSize = 14.sp) },
                singleLine = true,
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = TextMuted
                            )
                        }
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipData = clipboard.primaryClip
                            if (clipData != null && clipData.itemCount > 0) {
                                val text = clipData.getItemAt(0).text?.toString() ?: ""
                                if (text.isNotBlank()) apiKey = text.trim()
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Yapıştır",
                                tint = ObsidianBlack
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ObsidianBlack,
                    unfocusedBorderColor = SubtleBorderGray,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = PureWhite,
                    unfocusedContainerColor = PureWhite
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("api_key_input_field")
            )
        }

        // Bottom Actions
        Column(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onSubmitKey(apiKey.trim()) },
                enabled = apiKey.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ObsidianBlack,
                    disabledContainerColor = SubtleGrayBg
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("verify_and_connect_button")
            ) {
                Text(
                    text = "Bağlantıyı Doğrula",
                    color = if (apiKey.isNotBlank()) PureWhite else TextMuted,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Geri dön", color = TextSecondary, fontSize = 13.sp)
            }
        }
    }
}

// ---------------------- 9. GERÇEK API TESTİ & DURUM YAZISI ----------------------

suspend fun runVerificationProcess(
    providerId: String,
    apiKey: String,
    onValidate: suspend (String, String) -> ProviderValidationResult,
    onStepUpdate: (String) -> Unit,
    onError: (String) -> Unit,
    onSuccess: () -> Unit
) {
    onStepUpdate("Bağlantı kuruluyor...")
    delay(500)

    onStepUpdate("Bağlantı doğrulanıyor...")
    val result = onValidate(providerId, apiKey)

    if (!result.isSuccess) {
        onError(result.errorMessage ?: "Bağlantı kurulamadı. Lütfen bilgileri kontrol edin.")
        return
    }

    onStepUpdate("Beyin hazırlanıyor...")
    delay(600)

    onSuccess()
}

@Composable
fun VerificationActiveView(
    stepText: String,
    errorMessage: String?,
    onRetry: () -> Unit,
    onChangeProvider: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        // Sphere moves more energetically during verification
        LivingCoreView(isEnergetic = true)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 36.dp)
        ) {
            if (errorMessage == null) {
                Text(
                    text = stepText,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = errorMessage,
                    color = Color(0xFFDC2626),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onRetry,
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianBlack),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text("Tekrar Dene", color = PureWhite, fontSize = 13.sp)
                    }
                    TextButton(onClick = onChangeProvider) {
                        Text("Sağlayıcıyı Değiştir", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ---------------------- 10. BAŞARILI TEST SONRASI ANİMASYON (SURGE & BREAKTHROUGH) ----------------------

@Composable
fun BreakoutSurgeView() {
    val surgeProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // 1. Ekrandaki hareket sakinleşsin, kısa sessizlik (500ms)
        delay(500)
        // 4. Top bir anda dairenin dışına doğru fırlasın, sınırı geçsin, yavaşlasın
        surgeProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(1200, easing = FastOutSlowInEasing)
        )
    }

    val progress = surgeProgress.value

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Daire (sınırı geçildikçe hafifçe açılır / silinir)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val boundaryRadius = 88.dp.toPx()

            drawCircle(
                color = SubtleBorderGray.copy(alpha = (1f - progress * 0.7f).coerceIn(0f, 1f)),
                radius = boundaryRadius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // Siyah top dairenin dışına fırlar ve yukarı doğru süzülür
        val offsetY = (-200.dp.value * progress).dp

        Box(
            modifier = Modifier
                .offset(y = offsetY)
                .size((54 + progress * 8).dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.width / 2f

                drawCircle(
                    color = ObsidianBlack,
                    radius = radius,
                    center = center
                )
            }
        }
    }
}

// ---------------------- 10. TOPUN "MERHABA." YAZISINA DÖNÜŞMESİ ----------------------

@Composable
fun HelloTransformationView() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Text(
            text = "Merhaba.",
            color = TextPrimary,
            fontSize = 38.sp,
            fontWeight = FontWeight.Normal,
            letterSpacing = (-0.5).sp,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------- 11. İSİM SEKVANSI ("SENİ YENİ GÖRÜYORUM") ----------------------

@Composable
fun FirstSightView() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Text(
            text = "Seni yeni görüyorum.",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------- 11. BANA BİR İSİM VERMEK İSTER MİSİN? ----------------------

@Composable
fun AskAiNameView(
    initialName: String,
    onSubmitName: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    val suggestions = listOf("Nova", "Aura", "Atlas", "Lumina")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Bana bir isim vermek ister misin?",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("Örn: Nova", color = TextMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ObsidianBlack,
                unfocusedBorderColor = SubtleBorderGray,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = PureWhite,
                unfocusedContainerColor = PureWhite
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ai_name_input")
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Minimal Suggestion Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            suggestions.forEach { suggestion ->
                Surface(
                    color = if (name == suggestion) SubtleGrayBg else PureWhite,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .border(1.dp, if (name == suggestion) ObsidianBlack else SubtleBorderGray, RoundedCornerShape(16.dp))
                        .clickable { name = suggestion }
                ) {
                    Text(
                        text = suggestion,
                        color = if (name == suggestion) TextPrimary else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (name == suggestion) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = { onSubmitName(name) },
            colors = ButtonDefaults.buttonColors(containerColor = ObsidianBlack),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("submit_ai_name_button")
        ) {
            Text("Devam Et", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = PureWhite)
        }
    }
}

// ---------------------- 11. AI NAME ACKNOWLEDGED ("NOVA... GÜZEL.") ----------------------

@Composable
fun AiNameAckView(aiName: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Text(
            text = "$aiName...",
            color = TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Normal
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Güzel.",
            color = TextSecondary,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ---------------------- 11. "PEKİ YA SEN? SANA NASIL HİTAP ETMELİYİM?" ----------------------

@Composable
fun AskUserNameView(
    onSubmitUserName: (String) -> Unit
) {
    var userName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Peki ya sen?",
            color = TextSecondary,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Sana nasıl hitap etmeliyim?",
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        OutlinedTextField(
            value = userName,
            onValueChange = { userName = it },
            placeholder = { Text("Adın...", color = TextMuted) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ObsidianBlack,
                unfocusedBorderColor = SubtleBorderGray,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = PureWhite,
                unfocusedContainerColor = PureWhite
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("user_name_input")
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onSubmitUserName(userName) },
            enabled = userName.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = ObsidianBlack,
                disabledContainerColor = SubtleGrayBg
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("submit_user_name_button")
        ) {
            Text("Tamamla", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (userName.isNotBlank()) PureWhite else TextMuted)
        }
    }
}
