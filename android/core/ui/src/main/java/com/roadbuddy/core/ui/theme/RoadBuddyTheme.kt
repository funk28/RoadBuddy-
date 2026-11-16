package com.roadbuddy.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RoadBuddyColorScheme = lightColorScheme(
    primary = Color(0xFF1E6BE3),
    onPrimary = Color.White,
    background = Color.White,
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun RoadBuddyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RoadBuddyColorScheme,
        content = content,
    )
}
