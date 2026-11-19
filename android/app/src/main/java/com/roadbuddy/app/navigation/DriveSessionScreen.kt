package com.roadbuddy.app.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.roadbuddy.core.data.DriveSessionGate
import com.roadbuddy.core.data.navigation.AlertPhase
import com.roadbuddy.core.data.navigation.HazardAlertState
import com.roadbuddy.core.data.navigation.NavigationSession
import com.roadbuddy.core.data.navigation.NavigationUiState
import java.util.Locale

/**
 * The Map tab's drive session (issue #38 prototype).
 *
 * Starting a session drives the mocked campus route with simulated GPS. "Report pothole"
 * stands in for the on-device detector: [NavigationSession.reportPothole] uploads a bundled
 * pothole photo, tagged with the car's current position, to `POST /v1/detections`. Reports
 * only go out while road hazard detection is on in Settings.
 *
 * The rear-camera preview from issue #41 still runs alongside, but its frames aren't
 * analyzed yet.
 */
@Composable
fun DriveSessionScreen(
    gate: DriveSessionGate,
    cameraRuntime: DriveCameraRuntimeState,
    navigationSession: NavigationSession,
) {
    val context = LocalContext.current
    val sessionActive by gate.sessionActive.collectAsState()
    val nav by navigationSession.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // captureActive is "session active and detection enabled in Settings".
    val detectionOn = cameraRuntime.captureActive && !cameraRuntime.settingsReadError
    val canCapture = sessionActive &&
        detectionOn &&
        cameraRuntime.cameraAllowed &&
        !cameraRuntime.cameraError

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraRuntime.cameraAllowed = granted
    }

    // Reaching the end of the route ends the session.
    LaunchedEffect(nav.arrived) {
        if (nav.arrived) gate.stopSession()
    }

    LaunchedEffect(nav.message) {
        val message = nav.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        navigationSession.messageShown()
    }

    fun startDrive() {
        navigationSession.startDrive()
        gate.startSession()
        if (context.hasCameraPermission()) {
            cameraRuntime.cameraAllowed = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun endDrive() {
        navigationSession.endDrive()
        gate.stopSession()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            Text("Map", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))

            if (!sessionActive) {
                Text(
                    "Start a drive session to follow the mock campus route with simulated GPS. " +
                        "\"Report pothole\" uploads a sample pothole photo tagged with the " +
                        "car's position, standing in for the on-device detector.",
                )
                if (nav.arrived) {
                    Spacer(Modifier.height(8.dp))
                    Text("You arrived. Start again to drive the route from the beginning.")
                }
            } else {
                DriveProgress(nav)
                Spacer(Modifier.height(16.dp))
                when {
                    cameraRuntime.settingsReadError -> {
                        Text("Couldn't read drive settings. Detection is paused.")
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { cameraRuntime.settingsRetryAttempt++ }) {
                            Text("Retry")
                        }
                    }
                    !detectionOn -> Text("Road hazard detection is off in Settings; nothing is reported.")
                    else -> Button(
                        onClick = { navigationSession.reportPothole() },
                        enabled = nav.driving,
                    ) {
                        Text("Report pothole (mock detection)")
                    }
                }
            }

            nav.alert?.let { alert ->
                Spacer(Modifier.height(16.dp))
                HazardAlertCard(alert, onUndo = navigationSession::undo)
            }

            if (nav.pendingUploads > 0) {
                Spacer(Modifier.height(12.dp))
                val reports = if (nav.pendingUploads == 1) "report" else "reports"
                Text("${nav.pendingUploads} $reports waiting to send")
            }

            Spacer(Modifier.height(20.dp))
            if (sessionActive) {
                OutlinedButton(onClick = ::endDrive) {
                    Text("End drive session")
                }
            } else {
                Button(onClick = ::startDrive) {
                    Text("Start drive session")
                }
            }

            if (sessionActive) {
                Spacer(Modifier.height(24.dp))
                CameraSection(cameraRuntime, canCapture, detectionOn)
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun DriveProgress(nav: NavigationUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                nav.turn?.let { "${it.instruction} in ${formatDistance(it.distanceM)}" }
                    ?: "Arriving",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { nav.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${formatDistance(nav.trip.remainingDistanceM)} left · " +
                    "${formatMinutes(nav.trip.remainingTimeSeconds)} · ${nav.route.name}",
            )
            Text(
                String.format(
                    Locale.US,
                    "Simulated GPS: %.5f, %.5f",
                    nav.position.location.lat,
                    nav.position.location.lng,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HazardAlertCard(alert: HazardAlertState, onUndo: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("New hazard reported", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (alert.phase) {
                        AlertPhase.SENDING -> "Sending…"
                        AlertPhase.REPORTED -> alert.severity
                            ?.let { "Pothole · ${it.wireValue.replaceFirstChar(Char::uppercase)}" }
                            ?: "Pothole"
                        AlertPhase.QUEUED -> "Couldn't reach the server; will retry"
                        AlertPhase.UNDOING -> "Removing…"
                    },
                )
            }
            TextButton(onClick = onUndo, enabled = alert.phase != AlertPhase.UNDOING) {
                Text("Undo (${alert.undoSecondsLeft}s)")
            }
        }
    }
}

@Composable
private fun CameraSection(
    cameraRuntime: DriveCameraRuntimeState,
    canCapture: Boolean,
    detectionOn: Boolean,
) {
    Text("Rear camera", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    when {
        !detectionOn -> Text("Camera capture is stopped while detection is off.")
        !cameraRuntime.cameraAllowed ->
            Text("Camera permission was not granted. Mock detection still works.")
        cameraRuntime.cameraError -> {
            Text("Couldn't start the camera. Check camera access and try again.")
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { cameraRuntime.cameraRetryAttempt++ }) {
                Text("Retry camera")
            }
        }
        canCapture -> {
            AndroidView(
                factory = { viewContext ->
                    PreviewView(viewContext).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        controller = cameraRuntime.controller
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${cameraRuntime.framesObserved} frames received. No on-device classifier yet, " +
                    "so camera frames are discarded and never uploaded.",
            )
        }
    }
}

private fun formatDistance(meters: Double): String =
    String.format(Locale.US, "%.1f mi", meters / 1_609.344)

private fun formatMinutes(seconds: Long): String {
    val minutes = (seconds + 59) / 60
    return if (minutes <= 1) "1 min" else "$minutes min"
}
