package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppleBlue
import com.example.ui.theme.CharcoalCore
import com.example.ui.theme.ObsidianBlack

enum class OrbState {
    IDLE,
    THINKING,
    SPEAKING
}

@Composable
fun AiOrb(
    modifier: Modifier = Modifier,
    orbSize: Dp = 80.dp,
    state: OrbState = OrbState.IDLE
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = when (state) {
            OrbState.IDLE -> 0.94f
            OrbState.THINKING -> 0.88f
            OrbState.SPEAKING -> 0.96f
        },
        targetValue = when (state) {
            OrbState.IDLE -> 1.06f
            OrbState.THINKING -> 1.14f
            OrbState.SPEAKING -> 1.10f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    OrbState.IDLE -> 2400
                    OrbState.THINKING -> 800
                    OrbState.SPEAKING -> 1200
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val shadowPulse by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shadow_pulse"
    )

    Box(
        modifier = modifier.size(orbSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = (size.minDimension / 2) * 0.75f

            // Soft contact / ambient shadow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0x18000000),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.5f * shadowPulse
                ),
                radius = baseRadius * 1.5f * shadowPulse,
                center = center
            )

            // Outer subtle aura when thinking/speaking
            if (state != OrbState.IDLE) {
                drawCircle(
                    color = AppleBlue.copy(alpha = if (state == OrbState.THINKING) 0.25f else 0.15f),
                    radius = baseRadius * pulseScale * 1.2f,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Living Obsidian Charcoal Core Sphere with 3D soft light highlight
            val currentRadius = baseRadius * pulseScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        CharcoalCore,
                        ObsidianBlack,
                        Color(0xFF000000)
                    ),
                    center = Offset(center.x - currentRadius * 0.28f, center.y - currentRadius * 0.28f),
                    radius = currentRadius
                ),
                radius = currentRadius,
                center = center
            )

            // Delicate top rim light
            drawCircle(
                color = Color(0x33FFFFFF),
                radius = currentRadius - 0.5.dp.toPx(),
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            // Inner focus spark when thinking
            if (state == OrbState.THINKING) {
                drawCircle(
                    color = AppleBlue.copy(alpha = 0.8f),
                    radius = 3.dp.toPx(),
                    center = center
                )
            }
        }
    }
}

