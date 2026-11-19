package com.roadbuddy.feature.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.roadbuddy.core.data.DriveSettingsRepository
import com.roadbuddy.core.model.DriveSettings
import java.io.IOException
import kotlinx.coroutines.launch

private val SecondaryTextColor = Color(0xFF5D687A)
private val MutedTextColor = Color(0xFF8A94A5)
private val PermissionAllowedColor = Color(0xFF159447)
private val PermissionDeniedColor = Color(0xFFB3261E)
private val ButtonOutlineColor = Color(0xFFCBD3E0)

@Composable
fun SettingsRoute(repository: DriveSettingsRepository) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var locationAllowed by remember { mutableStateOf(context.hasLocationPermission()) }
    var cameraAllowed by remember { mutableStateOf(context.hasCameraPermission()) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var loadState by remember(repository) {
        mutableStateOf<SettingsLoadState>(SettingsLoadState.Loading)
    }

    LaunchedEffect(repository, loadAttempt) {
        loadState = SettingsLoadState.Loading
        try {
            repository.settings.collect { settings ->
                loadState = SettingsLoadState.Ready(settings)
            }
        } catch (_: IOException) {
            loadState = SettingsLoadState.Failed
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationAllowed = context.hasLocationPermission()
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        cameraAllowed = context.hasCameraPermission()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                locationAllowed = context.hasLocationPermission()
                cameraAllowed = context.hasCameraPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun updateSettings(update: suspend () -> Unit) {
        coroutineScope.launch {
            try {
                update()
            } catch (_: IOException) {
                snackbarHostState.showSnackbar("Couldn't save settings. Try again.")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = loadState) {
            SettingsLoadState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            SettingsLoadState.Failed -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Couldn't load drive settings.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { loadAttempt++ }) {
                        Text("Retry")
                    }
                }
            }
            is SettingsLoadState.Ready -> {
                SettingsScreen(
                    modifier = Modifier.fillMaxSize(),
                    settings = state.settings,
                    onAudibleAlertsChange = { enabled ->
                        updateSettings { repository.setAudibleAlerts(enabled) }
                    },
                    onDetectionEnabledChange = { enabled ->
                        updateSettings { repository.setDetectionEnabled(enabled) }
                    },
                    onSuggestReroutesChange = { enabled ->
                        updateSettings { repository.setSuggestReroutes(enabled) }
                    },
                    locationAllowed = locationAllowed,
                    onLocationClick = {
                        if (!locationAllowed) {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                ),
                            )
                        }
                    },
                    cameraAllowed = cameraAllowed,
                    onCameraClick = {
                        if (!cameraAllowed) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onReset = { updateSettings { repository.reset() } },
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private sealed interface SettingsLoadState {
    data object Loading : SettingsLoadState
    data object Failed : SettingsLoadState
    data class Ready(val settings: DriveSettings) : SettingsLoadState
}

@Composable
private fun SettingsScreen(
    modifier: Modifier,
    settings: DriveSettings,
    onAudibleAlertsChange: (Boolean) -> Unit,
    onDetectionEnabledChange: (Boolean) -> Unit,
    onSuggestReroutesChange: (Boolean) -> Unit,
    locationAllowed: Boolean,
    onLocationClick: () -> Unit,
    cameraAllowed: Boolean,
    onCameraClick: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 14.dp, top = 26.dp, end = 14.dp, bottom = 18.dp),
    ) {
        Text(
            text = "Settings",
            color = Color(0xFF171A20),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(22.dp))

        SectionHeading("DRIVING")
        Spacer(Modifier.height(12.dp))
        SettingsToggleRow(
            title = "Audible hazard alerts",
            description = "Spoken warnings for hazards ahead",
            checked = settings.audibleAlerts,
            onCheckedChange = onAudibleAlertsChange,
        )
        Spacer(Modifier.height(27.dp))
        SettingsToggleRow(
            title = "Road hazard detection",
            description = "Uses the rear camera while navigating",
            checked = settings.detectionEnabled,
            onCheckedChange = onDetectionEnabledChange,
        )
        Spacer(Modifier.height(28.dp))
        SettingsToggleRow(
            title = "Suggest reroutes for severe hazards",
            checked = settings.suggestReroutes,
            onCheckedChange = onSuggestReroutesChange,
        )

        Spacer(Modifier.height(22.dp))
        SectionHeading("PERMISSIONS")
        Spacer(Modifier.height(8.dp))
        PermissionRow(
            title = "Location",
            allowed = locationAllowed,
            onClick = onLocationClick,
        )
        Spacer(Modifier.height(7.dp))
        PermissionRow(
            title = "Rear camera",
            allowed = cameraAllowed,
            onClick = onCameraClick,
        )

        OutlinedButton(
            onClick = onReset,
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.5.dp, ButtonOutlineColor),
        ) {
            Text(
                text = "Reset to default drive settings",
                color = Color(0xFF183456),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(11.dp))
        Text(
            text = "Only flagged frames, location, time, and severity are shared.",
            color = MutedTextColor,
            fontSize = 14.sp,
            lineHeight = 19.sp,
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        color = MutedTextColor,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color(0xFF20232A),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp,
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    color = SecondaryTextColor,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics {
                contentDescription = title
            },
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    allowed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = Color(0xFF20232A),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = if (allowed) "Allowed" else "Not allowed",
            color = if (allowed) PermissionAllowedColor else PermissionDeniedColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun Context.hasLocationPermission(): Boolean =
    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun Context.hasCameraPermission(): Boolean =
    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
