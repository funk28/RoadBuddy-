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
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.roadbuddy.core.data.DriveSettingsRepository
import com.roadbuddy.core.data.DriveSessionGate
import com.roadbuddy.core.ui.navigation.RoadBuddyBottomBar
import com.roadbuddy.feature.settings.SettingsRoute

private object Routes {
    const val MAP = "map"
    const val HAZARDS = "hazards"
    const val REPORTS = "reports"
    const val SETTINGS = "settings"
}

@Composable
fun RoadBuddyApp() {
    val context = LocalContext.current.applicationContext
    val settingsRepository = remember(context) { DriveSettingsRepository(context) }
    val driveSessionGate = remember(settingsRepository) { DriveSessionGate(settingsRepository) }
    val driveCameraRuntime = rememberDriveCameraRuntime(driveSessionGate)
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
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
            startDestination = Routes.MAP,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable(Routes.MAP) {
                DriveSessionScreen(driveSessionGate, driveCameraRuntime)
            }
            composable(Routes.HAZARDS) {
                PlaceholderScreen("Hazards")
            }
            composable(Routes.REPORTS) {
                PlaceholderScreen("Reports")
            }
            composable(Routes.SETTINGS) {
                SettingsRoute(settingsRepository)
            }
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
