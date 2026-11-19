package com.example.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Consistent spacing grid (8dp baseline) for all ScreenSight AI screens.
 */
object ScreenSightSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val maxContentWidth = 680.dp
    val minTouchTarget = 48.dp
}

/**
 * Consistent corner radius hierarchy across cards, sheets, buttons, and inputs.
 */
val ScreenSightShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * Respects both in-app animation preference and Android system reduced-motion setting.
 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/**
 * Respects in-app haptic feedback preference.
 */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

private val DarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianNavy,
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = CyanBright,
    secondary = IndigoGlow,
    onSecondary = ObsidianNavy,
    secondaryContainer = Color(0xFF2B2870),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = EmeraldLive,
    onTertiary = ObsidianNavy,
    tertiaryContainer = Color(0xFF064E3B),
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = ObsidianNavy,
    onBackground = Color(0xFFF8FAFC),
    surface = DeepSlate,
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = ElevatedSlate,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = SubtleBorderDark,
    outlineVariant = Color(0xFF1E293B),
    error = CrimsonAlert,
    onError = Color.White,
    errorContainer = Color(0xFF450A0A),
    onErrorContainer = Color(0xFFFECACA)
)

private val LightColorScheme = lightColorScheme(
    primary = CyanDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF075985),
    secondary = IndigoDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF312E81),
    tertiary = EmeraldDeep,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF065F46),
    background = AlabasterLight,
    onBackground = SlateTextDark,
    surface = PureWhite,
    onSurface = SlateTextDark,
    surfaceVariant = SlateSurfaceLight,
    onSurfaceVariant = SlateTextMuted,
    outline = SubtleBorderLight,
    outlineVariant = Color(0xFFE2E8F0),
    error = CrimsonDeep,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B)
)

@Composable
fun ScreenSightTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    animationsEnabled: Boolean = true,
    hapticsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val systemAnimatorScale = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    val effectiveAnimations = animationsEnabled && systemAnimatorScale > 0f

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    CompositionLocalProvider(
        LocalAnimationsEnabled provides effectiveAnimations,
        LocalHapticsEnabled provides hapticsEnabled
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = ScreenSightShapes,
            content = content
        )
    }
}
