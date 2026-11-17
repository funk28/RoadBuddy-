package com.roadbuddy.app.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.LifecycleCameraController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.roadbuddy.core.data.DriveSessionGate
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

class DriveCameraRuntimeState internal constructor(context: Context) {
    val controller = LifecycleCameraController(context).apply {
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
    }
    internal val analysisExecutor = Executors.newSingleThreadExecutor()
    internal val frameCounter = AtomicLong()
    var cameraAllowed by mutableStateOf(context.hasCameraPermission())
        internal set
    var captureActive by mutableStateOf(false)
        internal set
    var settingsReadError by mutableStateOf(false)
        internal set
    var cameraError by mutableStateOf(false)
        internal set
    var framesObserved by mutableLongStateOf(0L)
        internal set
    var settingsRetryAttempt by mutableIntStateOf(0)
    var cameraRetryAttempt by mutableIntStateOf(0)

    internal fun close() {
        controller.clearImageAnalysisAnalyzer()
        controller.unbind()
        analysisExecutor.shutdown()
    }
}

internal fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

@Composable
fun rememberDriveCameraRuntime(gate: DriveSessionGate): DriveCameraRuntimeState {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val runtime = remember(context) { DriveCameraRuntimeState(context) }
    val sessionActive by gate.sessionActive.collectAsState()

    LaunchedEffect(gate, runtime.settingsRetryAttempt) {
        runtime.settingsReadError = false
        try {
            gate.frameCaptureActive.collect { active ->
                runtime.captureActive = active
            }
        } catch (_: IOException) {
            runtime.captureActive = false
            runtime.settingsReadError = true
        }
    }

    DisposableEffect(lifecycleOwner, runtime) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                runtime.cameraAllowed = context.hasCameraPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val canCapture = sessionActive &&
        runtime.captureActive &&
        runtime.cameraAllowed &&
        !runtime.settingsReadError

    DisposableEffect(
        runtime.controller,
        lifecycleOwner,
        canCapture,
        runtime.cameraRetryAttempt,
    ) {
        runtime.cameraError = false
        if (canCapture) {
            runtime.controller.setImageAnalysisAnalyzer(runtime.analysisExecutor) { image ->
                try {
                    runtime.frameCounter.incrementAndGet()
                } finally {
                    image.close()
                }
            }
            try {
                runtime.controller.bindToLifecycle(lifecycleOwner)
            } catch (_: IllegalStateException) {
                runtime.cameraError = true
                runtime.controller.clearImageAnalysisAnalyzer()
                runtime.controller.unbind()
            } catch (_: SecurityException) {
                runtime.cameraError = true
                runtime.controller.clearImageAnalysisAnalyzer()
                runtime.controller.unbind()
            }
        } else {
            runtime.controller.clearImageAnalysisAnalyzer()
            runtime.controller.unbind()
        }
        onDispose {
            runtime.controller.clearImageAnalysisAnalyzer()
            runtime.controller.unbind()
        }
    }

    LaunchedEffect(canCapture) {
        while (canCapture) {
            delay(1_000)
            runtime.framesObserved = runtime.frameCounter.get()
        }
    }

    DisposableEffect(runtime) {
        onDispose { runtime.close() }
    }

    return runtime
}
