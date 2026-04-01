package com.example.adaptapp.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val AdaptColorScheme = lightColorScheme(
    primary = AdaptBlue,
    onPrimary = AdaptWhite,
    secondary = AdaptGrayDark,
    background = AdaptWhite,
    onBackground = AdaptTextPrimary,
    surface = AdaptGray,
    onSurface = AdaptTextPrimary,
    error = AdaptRed,
    onError = AdaptWhite
)

@Composable
fun ADAPTAppTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.White.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = AdaptColorScheme,
        typography = Typography,
        content = content
    )
}