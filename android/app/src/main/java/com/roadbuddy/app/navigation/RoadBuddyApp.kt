package com.roadbuddy.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.roadbuddy.app.BuildConfig
import com.roadbuddy.core.data.DriveSettingsRepository
import com.roadbuddy.core.data.DriveSessionGate
import com.roadbuddy.core.data.navigation.NavigationSession
import com.roadbuddy.core.network.createDetectionApi
import com.roadbuddy.core.ui.navigation.RoadBuddyBottomBar
import com.roadbuddy.feature.hazards.HazardsRoute
import com.roadbuddy.feature.hazards.createHazardsRepository
import com.roadbuddy.feature.reports.ReportsRoute
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
    // One session for the whole app, so a drive keeps going while another tab is open.
    val appScope = rememberCoroutineScope()
    val navigationSession = remember(appScope) {
        NavigationSession(
            scope = appScope,
            api = createDetectionApi(BuildConfig.API_BASE_URL, BuildConfig.DEV_AUTH_TOKEN),
        )
    }
    val navController = rememberNavController()
    val hazardsRepository = remember {
        createHazardsRepository(BuildConfig.API_BASE_URL, BuildConfig.DEV_AUTH_TOKEN)
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
            startDestination = Routes.MAP,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable(Routes.MAP) {
                DriveSessionScreen(driveSessionGate, driveCameraRuntime, navigationSession)
            }
            composable(Routes.HAZARDS) {
                HazardsRoute(hazardsRepository)
            }
            composable(Routes.REPORTS) {
                ReportsRoute()
            }
            composable(Routes.SETTINGS) {
                SettingsRoute(settingsRepository)
            }
        }
    }
}
