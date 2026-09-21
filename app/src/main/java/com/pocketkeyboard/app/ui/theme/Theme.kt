package com.pocketkeyboard.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * 口袋键鼠主题：黑白极简、苹果式设计语言。
 *
 * 无论系统设置如何，始终使用纯黑背景 + 纯白前景（OLED 省电、黑白主题）。
 */
private val MonoColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = PureBlack,
    secondary = PureWhite,
    onSecondary = PureBlack,
    background = PureBlack,
    onBackground = PureWhite,
    surface = PureBlack,
    onSurface = PureWhite,
    surfaceVariant = MinimalGray,
    onSurfaceVariant = PureWhite,
    outline = PureWhite,
    outlineVariant = MinimalGray,
    error = PureWhite,
    onError = PureBlack,
)

@Composable
fun PocketKeyboardTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MonoColorScheme,
        typography = PocketKeyboardTypography,
        content = content,
    )
}
