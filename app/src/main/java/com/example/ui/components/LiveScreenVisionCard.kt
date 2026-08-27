package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.ScreenNodeData
import com.example.service.ScreenSnapshot
import com.example.service.VirtualFingerState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveScreenVisionCard(
    snapshot: ScreenSnapshot,
    screenshot: Bitmap?,
    virtualFinger: VirtualFingerState?,
    isAccessibilityActive: Boolean,
    onRefresh: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onNodeClick: (ScreenNodeData) -> Unit,
    onActionHome: () -> Unit,
    onActionBack: () -> Unit,
    onActionSwipeDown: () -> Unit,
    onActionSwipeUp: () -> Unit,
    onActionReadScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "radar_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_screen_vision_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (isAccessibilityActive)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.errorContainer
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = "Görüş",
                            tint = if (isAccessibilityActive)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Canlı Ekran & Parmak Görüşü",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            if (isAccessibilityActive) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50).copy(alpha = pulseAlpha))
                                )
                            }
                        }

                        val appLabel = if (snapshot.packageName.isNotBlank()) {
                            snapshot.packageName.split(".").lastOrNull()?.replaceFirstChar { it.uppercase() } ?: snapshot.packageName
                        } else {
                            "Bağlantı Bekleniyor"
                        }
                        Text(
                            text = if (isAccessibilityActive) "$appLabel • ${snapshot.clickableNodes.size} Tıklanabilir Buton" else "Erişilebilirlik Servisi Kapalı",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row {
                    IconButton(
                        onClick = onCaptureScreenshot,
                        modifier = Modifier.testTag("btn_capture_screenshot")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Ekran Görüntüsü Al",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("btn_refresh_screen")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Yenile",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Virtual Finger Live State Banner
            if (virtualFinger != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "Parmak Hareketi",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Sanal Parmak: ${virtualFinger.actionType} (${virtualFinger.x.toInt()}, ${virtualFinger.y.toInt()}) ${if (virtualFinger.targetLabel.isNotBlank()) "→ “${virtualFinger.targetLabel}”" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Visual Screen Preview or Structural Coordinate Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (screenshot != null) {
                    Image(
                        bitmap = screenshot.asImageBitmap(),
                        contentDescription = "Canlı Ekran Görüntüsü",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Structural Node Radar Visualizer
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val canvasW = size.width
                        val canvasH = size.height

                        // Grid lines
                        val stepX = canvasW / 6f
                        val stepY = canvasH / 6f
                        for (i in 1..5) {
                            drawLine(
                                color = Color.White.copy(alpha = 0.05f),
                                start = Offset(stepX * i, 0f),
                                end = Offset(stepX * i, canvasH),
                                strokeWidth = 1f
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.05f),
                                start = Offset(0f, stepY * i),
                                end = Offset(canvasW, stepY * i),
                                strokeWidth = 1f
                            )
                        }

                        // Draw on-screen detected nodes
                        val screenW = 1080f // normalize reference
                        val screenH = 2400f

                        snapshot.clickableNodes.take(25).forEach { node ->
                            val b = node.bounds
                            val normLeft = (b.left / screenW) * canvasW
                            val normTop = (b.top / screenH) * canvasH
                            val normRight = (b.right / screenW) * canvasW
                            val normBottom = (b.bottom / screenH) * canvasH
                            val w = (normRight - normLeft).coerceAtLeast(10f)
                            val h = (normBottom - normTop).coerceAtLeast(8f)

                            drawRect(
                                color = Color(0xFF38BDF8).copy(alpha = 0.15f),
                                topLeft = Offset(normLeft, normTop),
                                size = Size(w, h)
                            )
                            drawRect(
                                color = Color(0xFF38BDF8).copy(alpha = 0.6f),
                                topLeft = Offset(normLeft, normTop),
                                size = Size(w, h),
                                style = Stroke(width = 1.2f)
                            )
                        }

                        // Draw Virtual Finger Touch Indicator Ripple
                        if (virtualFinger != null) {
                            val fx = (virtualFinger.x / screenW) * canvasW
                            val fy = (virtualFinger.y / screenH) * canvasH

                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFFF0055).copy(alpha = 0.8f),
                                        Color(0xFFFF0055).copy(alpha = 0.0f)
                                    ),
                                    center = Offset(fx, fy),
                                    radius = 28f
                                ),
                                center = Offset(fx, fy),
                                radius = 28f
                            )
                            drawCircle(
                                color = Color.White,
                                center = Offset(fx, fy),
                                radius = 4f
                            )
                        }
                    }
                }

                // Overlay Status Text
                if (!isAccessibilityActive) {
                    Text(
                        text = "Erişilebilirlik izni verildiğinde bu alanda ekran canlı taranacaktır.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // Detected On-Screen Interactive Nodes (Clickable Badges)
            if (snapshot.clickableNodes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Algılanan Ögeler (Dokunmak için bas):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        snapshot.clickableNodes.take(12).forEach { node ->
                            val label = node.text.ifBlank { node.contentDescription.ifBlank { node.viewId.split("/").lastOrNull() ?: "Buton" } }
                            if (label.isNotBlank() && label.length in 2..30) {
                                AssistChip(
                                    onClick = { onNodeClick(node) },
                                    label = { Text(label, maxLines = 1, fontSize = 12.sp) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.TouchApp,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        labelColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Quick Finger Actions Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = onActionHome) {
                    Text("🏠 Ana Ekran")
                }
                FilledTonalButton(onClick = onActionBack) {
                    Text("⬅️ Geri")
                }
                FilledTonalButton(onClick = onActionSwipeDown) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Aşağı Kaydır")
                }
                FilledTonalButton(onClick = onActionSwipeUp) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Yukarı Kaydır")
                }
                FilledTonalButton(onClick = onActionReadScreen) {
                    Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ekranı Oku")
                }
            }
        }
    }
}
