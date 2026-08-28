package com.nikhil.ridetogether.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A fixed palette rather than Material You dynamic colour.
 *
 * Dynamic colour would pull whatever accent the user's wallpaper produces,
 * which on a screen whose whole job is distinguishing riders by colour is a
 * liability -- the rider chips and the route line need to stay legible and
 * distinct regardless of the phone. It also skips a runtime colour extraction
 * pass on old hardware.
 */

private val Orange = Color(0xFFE8590C)
private val OrangeDark = Color(0xFFFF922B)
private val Ink = Color(0xFF14161A)
private val Slate = Color(0xFF1E2228)

private val LightColors = lightColorScheme(
    primary = Orange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE8CC),
    onPrimaryContainer = Color(0xFF7A2E00),
    secondary = Color(0xFF1971C2),
    onSecondary = Color.White,
    background = Color(0xFFF8F9FA),
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFECEEF1),
    onSurfaceVariant = Color(0xFF495057),
    error = Color(0xFFC92A2A),
    outline = Color(0xFFCED4DA)
)

private val DarkColors = darkColorScheme(
    primary = OrangeDark,
    onPrimary = Color(0xFF3D1400),
    primaryContainer = Color(0xFF7A3200),
    onPrimaryContainer = Color(0xFFFFE8CC),
    secondary = Color(0xFF74C0FC),
    onSecondary = Color(0xFF00243D),
    background = Ink,
    onBackground = Color(0xFFE9ECEF),
    surface = Slate,
    onSurface = Color(0xFFE9ECEF),
    surfaceVariant = Color(0xFF2C323A),
    onSurfaceVariant = Color(0xFFADB5BD),
    error = Color(0xFFFF6B6B),
    outline = Color(0xFF495057)
)

private val AppTypography = Typography(
    // Ride code, read at a glance with a helmet on.
    displaySmall = TextStyle(
        fontSize = 34.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 6.sp
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    ),
    labelSmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp
    )
)

@Composable
fun RideTogetherTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
