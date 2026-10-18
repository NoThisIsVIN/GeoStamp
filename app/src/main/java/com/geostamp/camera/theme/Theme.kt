package com.geostamp.camera.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = GeoPrimaryDark,
    secondary = GeoSecondaryDark,
    tertiary = GeoTertiaryDark,
    background = GeoBackgroundDark,
    surface = GeoSurfaceDark,
    surfaceVariant = GeoSurfaceVariantDark,
    onPrimary = Color(0xFF03045E),
    onSecondary = Color(0xFF03045E),
    onTertiary = Color(0xFF3A2800),
    onBackground = Color(0xFFE2E8F0),
    onSurface = Color(0xFFE2E8F0)
)

private val LightColorScheme = lightColorScheme(
    primary = GeoPrimaryLight,
    secondary = GeoSecondaryLight,
    tertiary = GeoTertiaryLight,
    background = GeoBackgroundLight,
    surface = GeoSurfaceLight,
    surfaceVariant = GeoSurfaceVariantLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1A202C),
    onSurface = Color(0xFF1A202C)
)

@Composable
fun GeoStampCameraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
