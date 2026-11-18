package com.roadbuddy.app.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.roadbuddy.app.BuildConfig
import com.roadbuddy.core.ui.navigation.RoadBuddyBottomBar
import com.roadbuddy.feature.hazards.HazardsRoute
import com.roadbuddy.feature.hazards.createHazardsRepository

private object Routes {
    const val MAP = "map"
    const val HAZARDS = "hazards"
    const val REPORTS = "reports"
    const val SETTINGS = "settings"
}

@Composable
fun RoadBuddyApp() {
    val navController = rememberNavController()
    val hazardsRepository = remember {
        createHazardsRepository(BuildConfig.HAZARDS_API_BASE_URL)
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            RoadBuddyBottomBar(
                selectedRoute = currentRoute,
                onDestinationSelected = { route ->
                    if (route != currentRoute) {
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HAZARDS,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable(Routes.MAP) { PlaceholderScreen("Map") }
            composable(Routes.HAZARDS) { HazardsRoute(hazardsRepository) }
            composable(Routes.REPORTS) { PlaceholderScreen("Reports") }
            composable(Routes.SETTINGS) { PlaceholderScreen("Settings") }
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
