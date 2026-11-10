package com.ftork.gamma.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Violet = Color(0xFF5B3FD6)
private val Amber = Color(0xFFFFC94D)

/** FTork-Gamma 색. Android 12+ 에서는 배경화면 색(다이내믹 컬러)을 따라가요. */
@Composable
fun FTorkTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFFC8B8FF), secondary = Amber)
        else -> lightColorScheme(primary = Violet, secondary = Color(0xFF8A6200))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
