package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = AppleBlue,
    onPrimary = PureWhite,
    primaryContainer = Color(0xFFE8F2FE),
    onPrimaryContainer = AppleBlue,
    secondary = Color(0xFF475569),
    onSecondary = PureWhite,
    secondaryContainer = SubtleGrayBg,
    onSecondaryContainer = TextPrimary,
    tertiary = SuccessGreen,
    background = OffWhiteCanvas,
    onBackground = TextPrimary,
    surface = PureWhite,
    onSurface = TextPrimary,
    surfaceVariant = SubtleGrayBg,
    onSurfaceVariant = TextSecondary,
    outline = SubtleBorderGray
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        content = content
    )
}

