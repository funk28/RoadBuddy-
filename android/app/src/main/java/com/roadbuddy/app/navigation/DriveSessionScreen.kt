package com.roadbuddy.app.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.roadbuddy.core.data.DriveSessionGate

@Composable
fun DriveSessionScreen(
    gate: DriveSessionGate,
    cameraRuntime: DriveCameraRuntimeState,
) {
    val context = LocalContext.current
    val sessionActive by gate.sessionActive.collectAsState()
    val canCapture = sessionActive &&
        cameraRuntime.captureActive &&
        cameraRuntime.cameraAllowed &&
        !cameraRuntime.settingsReadError &&
        !cameraRuntime.cameraError

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraRuntime.cameraAllowed = granted
        if (granted) {
            gate.startSession()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Map", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("Start a drive session to capture rear-camera frames while the app is in the foreground.")
        Spacer(Modifier.height(8.dp))
        Text("No hazard classifier is integrated yet. Frames are discarded locally and never uploaded.")
        Spacer(Modifier.height(20.dp))

        when {
            cameraRuntime.settingsReadError -> {
                Text("Couldn't read drive settings. Camera capture is paused.")
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { cameraRuntime.settingsRetryAttempt++ }) {
                    Text("Retry")
                }
            }
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
                Text("Rear-camera frame capture is active")
                Text("${cameraRuntime.framesObserved} frames received and discarded")
            }
            sessionActive && !cameraRuntime.captureActive -> {
                Text("Detection is off; camera capture is stopped.")
            }
            sessionActive && !cameraRuntime.cameraAllowed -> {
                Text("Camera permission is required to start detection.")
            }
        }

        Spacer(Modifier.height(20.dp))
        if (sessionActive) {
            OutlinedButton(onClick = gate::stopSession) {
                Text("End drive session")
            }
        } else {
            Button(
                onClick = {
                    if (context.hasCameraPermission()) {
                        cameraRuntime.cameraAllowed = true
                        gate.startSession()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
            ) {
                Text("Start drive session")
            }
        }
    }
}
