// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.loxk.pebblephone

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the colors come from the wallpaper (Material You) or from the app icon. Held as state
 * so that the setting applies at once. Phones whose maker left Material You out hand apps a
 * stock palette instead of the wallpaper's: the icon colors are the way out.
 */
object ColorChoice {
    private var wallpaper: MutableState<Boolean>? = null

    fun wallpaper(context: Context): MutableState<Boolean> =
        wallpaper ?: mutableStateOf(Settings.wallpaperColors(context)).also { wallpaper = it }
}

@Composable
fun PhoneTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        !ColorChoice.wallpaper(context).value -> if (dark) BrandDark else BrandLight
        dark -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

// Tonal palettes of the teal of the app icon, #005555, with neutral surfaces of the same hue.
private val BrandLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF006A6A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF6FF7F6),
    onPrimaryContainer = Color(0xFF002020),
    inversePrimary = Color(0xFF4CDADA),
    secondary = Color(0xFF4A6363),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E7),
    onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF4B607C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF041C35),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAFDFC),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFFAFDFC),
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE5E4),
    onSurfaceVariant = Color(0xFF3F4948),
    inverseSurface = Color(0xFF2D3131),
    inverseOnSurface = Color(0xFFEFF1F0),
    outline = Color(0xFF6F7979),
    outlineVariant = Color(0xFFBEC9C8),
    surfaceDim = Color(0xFFD8DBDA),
    surfaceBright = Color(0xFFF8FAF9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F3),
    surfaceContainer = Color(0xFFECEEED),
    surfaceContainerHigh = Color(0xFFE6E9E8),
    surfaceContainerHighest = Color(0xFFE1E3E2),
)

private val BrandDark: ColorScheme = darkColorScheme(
    primary = Color(0xFF4CDADA),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F4F),
    onPrimaryContainer = Color(0xFF6FF7F6),
    inversePrimary = Color(0xFF006A6A),
    secondary = Color(0xFFB0CCCB),
    onSecondary = Color(0xFF1B3534),
    secondaryContainer = Color(0xFF324B4B),
    onSecondaryContainer = Color(0xFFCCE8E7),
    tertiary = Color(0xFFB3C8E8),
    onTertiary = Color(0xFF1C314B),
    tertiaryContainer = Color(0xFF334863),
    onTertiaryContainer = Color(0xFFD3E4FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF191C1C),
    onBackground = Color(0xFFE0E3E2),
    surface = Color(0xFF191C1C),
    onSurface = Color(0xFFE0E3E2),
    surfaceVariant = Color(0xFF3F4948),
    onSurfaceVariant = Color(0xFFBEC9C8),
    inverseSurface = Color(0xFFE0E3E2),
    inverseOnSurface = Color(0xFF2D3131),
    outline = Color(0xFF889392),
    outlineVariant = Color(0xFF3F4948),
    surfaceDim = Color(0xFF101414),
    surfaceBright = Color(0xFF363A3A),
    surfaceContainerLowest = Color(0xFF0B0F0F),
    surfaceContainerLow = Color(0xFF191C1C),
    surfaceContainer = Color(0xFF1D2020),
    surfaceContainerHigh = Color(0xFF272B2B),
    surfaceContainerHighest = Color(0xFF323535),
)
