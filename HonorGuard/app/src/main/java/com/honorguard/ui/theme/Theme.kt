package com.honorguard.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Design Language ───────────────────────────────────────────────────────
//
// HonorGuard uses a deep-navy / steel palette — authoritative and calm,
// not aggressive. The signature element is a pulsing amber "shield ring"
// on the incoming call screen that signals spam level.
//
// Palette:
//   Navy Deep    #0B1120  — app background
//   Navy Card    #131C2E  — card / surface background
//   Navy Border  #1E2D47  — dividers, borders
//   Steel        #8A9BB5  — secondary text
//   White        #E8EDF5  — primary text
//   Amber        #F5A623  — spam warning / recording dot
//   Red          #E84040  — fraud / end call
//   Green        #2ECC71  — safe / answer call
//   Blue Accent  #3D8EFF  — interactive, active state

object GuardColors {
    val NavyDeep    = Color(0xFF0B1120)
    val NavyCard    = Color(0xFF131C2E)
    val NavyBorder  = Color(0xFF1E2D47)
    val Steel       = Color(0xFF8A9BB5)
    val White       = Color(0xFFE8EDF5)
    val Amber       = Color(0xFFF5A623)
    val Red         = Color(0xFFE84040)
    val Green       = Color(0xFF2ECC71)
    val BlueAccent  = Color(0xFF3D8EFF)

    // Spam level colors
    fun forSpam(score: com.honorguard.data.model.SpamScore) = when (score) {
        com.honorguard.data.model.SpamScore.SAFE      -> Green
        com.honorguard.data.model.SpamScore.UNKNOWN   -> Steel
        com.honorguard.data.model.SpamScore.SUSPECTED -> Amber
        com.honorguard.data.model.SpamScore.SPAM      -> Color(0xFFFF6B35)
        com.honorguard.data.model.SpamScore.FRAUD     -> Red
    }
}

private val DarkColorScheme = darkColorScheme(
    primary          = GuardColors.BlueAccent,
    onPrimary        = GuardColors.White,
    primaryContainer = GuardColors.NavyCard,
    secondary        = GuardColors.Steel,
    onSecondary      = GuardColors.White,
    background       = GuardColors.NavyDeep,
    onBackground     = GuardColors.White,
    surface          = GuardColors.NavyCard,
    onSurface        = GuardColors.White,
    surfaceVariant   = GuardColors.NavyBorder,
    error            = GuardColors.Red,
    onError          = GuardColors.White
)

@Composable
fun HonorGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = GuardTypography,
        content     = content
    )
}

val GuardTypography = Typography(
    // Large number display (dial pad, call timer)
    displayLarge = TextStyle(
        fontFamily  = FontFamily.Default,
        fontWeight  = FontWeight.Light,
        fontSize    = 56.sp,
        letterSpacing = (-1).sp,
        color = GuardColors.White
    ),
    // Caller name
    headlineLarge = TextStyle(
        fontWeight  = FontWeight.SemiBold,
        fontSize    = 28.sp,
        color = GuardColors.White
    ),
    // Phone number display
    headlineMedium = TextStyle(
        fontWeight  = FontWeight.Normal,
        fontSize    = 22.sp,
        letterSpacing = 1.sp,
        color = GuardColors.Steel
    ),
    // Section labels
    titleMedium = TextStyle(
        fontWeight  = FontWeight.Medium,
        fontSize    = 14.sp,
        letterSpacing = 0.5.sp,
        color = GuardColors.Steel
    ),
    // Body
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize   = 16.sp,
        color = GuardColors.White
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize   = 14.sp,
        color = GuardColors.Steel
    )
)
