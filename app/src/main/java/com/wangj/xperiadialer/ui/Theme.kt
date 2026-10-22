package com.wangj.xperiadialer.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 配色对齐系统自带 Sony Dialer（提取自 Dialer.apk）。
 * 主色 #2A56C6、强调品红 #F50057、背景 #FAFAFA。
 */
private val LightColors = lightColorScheme(
    primary = SonyColors.Theme,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3F8),
    onPrimaryContainer = Color(0xFF0E1E4D),
    secondary = SonyColors.Accent,
    onSecondary = Color.White,
    background = SonyColors.Background,
    surface = Color.White,
    onSurface = SonyColors.TextPrimary,
    error = SonyColors.EndCallRed,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DB0EE),
    onPrimary = Color(0xFF0E1E4D),
    primaryContainer = Color(0xFF233A7A),
    secondary = Color(0xFFFF80AB),
    onSecondary = Color(0xFF3E0021),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFECECEC),
    error = Color(0xFFEF9A9A),
)

@Composable
fun XperiaDialerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
