package com.roadbuddy.core.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class NavigationItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val navigationItems = listOf(
    NavigationItem("map", "Map", Icons.Outlined.Navigation),
    NavigationItem("hazards", "Hazards", Icons.Outlined.Warning),
    NavigationItem("reports", "Reports", Icons.AutoMirrored.Outlined.List),
    NavigationItem("settings", "Settings", Icons.Outlined.WbSunny),
)

@Composable
fun RoadBuddyBottomBar(
    selectedRoute: String?,
    onDestinationSelected: (String) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.drawWithContent {
            drawContent()
            drawLine(
                color = Color(0xFFE2E5EA),
                start = Offset(0f, 0f),
                end = Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx(),
            )
        },
        containerColor = Color.White,
        tonalElevation = 0.dp,
    ) {
        navigationItems.forEach { item ->
            NavigationBarItem(
                selected = selectedRoute == item.route,
                onClick = { onDestinationSelected(item.route) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp),
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF1E6BE3),
                    selectedTextColor = Color(0xFF1E6BE3),
                    unselectedIconColor = Color(0xFF5D687A),
                    unselectedTextColor = Color(0xFF5D687A),
                    indicatorColor = Color.Transparent,
                ),
            )
        }
    }
}
