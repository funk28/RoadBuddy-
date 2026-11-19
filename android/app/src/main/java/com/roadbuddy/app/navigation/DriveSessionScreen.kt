package com.roadbuddy.app.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.roadbuddy.core.data.DriveSessionGate
import com.roadbuddy.core.data.navigation.AlertPhase
import com.roadbuddy.core.data.navigation.HazardAlertState
import com.roadbuddy.core.data.navigation.NavigationSession
import com.roadbuddy.core.data.navigation.NavigationUiState
import com.roadbuddy.core.data.navigation.Route
import com.roadbuddy.core.model.LatLng
import java.util.Locale

private val RouteBlue = Color(0xFF1E6BE3)
private val MapRoad = Color.White
private val MapRoadEdge = Color(0xFFDDE2E8)
private val MapBackground = Color(0xFFEAF0ED)
private val MapBlock = Color(0xFFDDE8DF)

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
        MockMap(
            route = nav.route,
            position = nav.position.location,
            modifier = Modifier.fillMaxSize(),
        )

        TurnCard(
            nav = nav,
            sessionActive = sessionActive,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        )

        DetectionChip(
            sessionActive = sessionActive,
            detectionOn = detectionOn,
            nav = nav,
            cameraRuntime = cameraRuntime,
            canCapture = canCapture,
            onReport = { navigationSession.reportPothole() },
            onRetrySettings = { cameraRuntime.settingsRetryAttempt++ },
            onRetryCamera = { cameraRuntime.cameraRetryAttempt++ },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp),
        )

        TripBar(
            nav = nav,
            sessionActive = sessionActive,
            onStart = ::startDrive,
            onEnd = ::endDrive,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )

        nav.alert?.let { alert ->
            HazardAlertCard(
                alert = alert,
                onUndo = navigationSession::undo,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 104.dp),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun MockMap(
    route: Route,
    position: LatLng,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        drawRect(MapBackground)

        val width = size.width
        val height = size.height
        val roads = listOf(
            Offset(0f, height * 0.18f) to Offset(width, height * 0.28f),
            Offset(0f, height * 0.49f) to Offset(width, height * 0.41f),
            Offset(0f, height * 0.76f) to Offset(width, height * 0.66f),
            Offset(width * 0.18f, 0f) to Offset(width * 0.31f, height),
            Offset(width * 0.52f, 0f) to Offset(width * 0.44f, height),
            Offset(width * 0.81f, 0f) to Offset(width * 0.71f, height),
        )
        roads.forEach { (start, end) ->
            drawLine(MapRoadEdge, start, end, 42.dp.toPx(), cap = StrokeCap.Round)
            drawLine(MapRoad, start, end, 34.dp.toPx(), cap = StrokeCap.Round)
        }

        val blocks = listOf(
            Offset(width * 0.04f, height * 0.30f) to Offset(width * 0.20f, height * 0.39f),
            Offset(width * 0.34f, height * 0.10f) to Offset(width * 0.47f, height * 0.24f),
            Offset(width * 0.57f, height * 0.43f) to Offset(width * 0.73f, height * 0.50f),
            Offset(width * 0.81f, height * 0.72f) to Offset(width * 0.96f, height * 0.87f),
        )
        blocks.forEach { (topLeft, bottomRight) ->
            drawRoundRect(
                color = MapBlock,
                topLeft = topLeft,
                size = Size(bottomRight.x - topLeft.x, bottomRight.y - topLeft.y),
                cornerRadius = CornerRadius(18.dp.toPx()),
            )
        }

        val minLat = route.points.minOf { it.lat }
        val maxLat = route.points.maxOf { it.lat }
        val minLng = route.points.minOf { it.lng }
        val maxLng = route.points.maxOf { it.lng }
        val latRange = (maxLat - minLat).coerceAtLeast(0.0001)
        val lngRange = (maxLng - minLng).coerceAtLeast(0.0001)
        fun project(point: LatLng): Offset = Offset(
            x = width * 0.12f + ((point.lng - minLng) / lngRange).toFloat() * width * 0.76f,
            y = height * 0.86f - ((point.lat - minLat) / latRange).toFloat() * height * 0.72f,
        )

        val routePoints = route.points.map(::project)
        val routePath = Path().apply {
            moveTo(routePoints.first().x, routePoints.first().y)
            routePoints.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
            path = routePath,
            color = Color(0x804C79C7),
            style = Stroke(width = 16.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = routePath,
            color = RouteBlue,
            style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        val marker = project(position)
        drawCircle(color = Color.White, radius = 18.dp.toPx(), center = marker)
        drawCircle(color = RouteBlue, radius = 12.dp.toPx(), center = marker)
        drawCircle(color = Color.White, radius = 4.dp.toPx(), center = marker)
    }
}

@Composable
private fun TurnCard(
    nav: NavigationUiState,
    sessionActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val turn = nav.turn
    val instruction = when {
        !sessionActive -> "Route ready"
        turn == null -> "Arriving"
        else -> turn.instruction
    }
    val distance = when {
        !sessionActive -> nav.route.name
        turn == null -> "Destination ahead"
        else -> "in ${formatDistance(turn.distanceM)}"
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "↱",
                style = MaterialTheme.typography.headlineSmall,
                color = RouteBlue,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = instruction,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = distance, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DetectionChip(
    sessionActive: Boolean,
    detectionOn: Boolean,
    nav: NavigationUiState,
    cameraRuntime: DriveCameraRuntimeState,
    canCapture: Boolean,
    onReport: () -> Unit,
    onRetrySettings: () -> Unit,
    onRetryCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(
            shape = RoundedCornerShape(50),
            colors = CardDefaults.cardColors(
                containerColor = if (sessionActive && detectionOn) Color(0xFFE8F0FE) else Color.White,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Text(
                text = if (sessionActive && detectionOn) "Detection on" else "Detection off",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                color = if (sessionActive && detectionOn) RouteBlue else Color(0xFF5D687A),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        if (sessionActive && detectionOn) {
            OutlinedButton(
                onClick = onReport,
                enabled = nav.driving,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
            ) {
                Text("Report pothole")
            }
        }
        if (cameraRuntime.settingsReadError) {
            OutlinedButton(onClick = onRetrySettings) { Text("Retry settings") }
        }
        if (cameraRuntime.cameraError) {
            OutlinedButton(onClick = onRetryCamera) { Text("Retry camera") }
        }
        if (canCapture) {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
            ) {
                AndroidView(
                    factory = { viewContext ->
                        PreviewView(viewContext).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            controller = cameraRuntime.controller
                        }
                    },
                    modifier = Modifier
                        .width(112.dp)
                        .height(144.dp),
                )
            }
            Text(
                text = "${cameraRuntime.framesObserved} frames",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF5D687A),
            )
        }
    }
}

@Composable
private fun TripBar(
    nav: NavigationUiState,
    sessionActive: Boolean,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (sessionActive) {
                        "${formatMinutes(nav.trip.remainingTimeSeconds)} · " +
                            "${formatDistance(nav.trip.remainingDistanceM)} left"
                    } else {
                        "4.8 mi · ${formatMinutes(nav.trip.remainingTimeSeconds)}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (sessionActive) nav.route.name else "Mock route",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF5D687A),
                )
            }
            if (sessionActive) {
                Button(onClick = onEnd, shape = RoundedCornerShape(12.dp)) {
                    Text("End")
                }
            } else {
                Button(onClick = onStart, shape = RoundedCornerShape(12.dp)) {
                    Text("Start")
                }
            }
        }
    }
}

@Composable
private fun HazardAlertCard(
    alert: HazardAlertState,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = when (alert.phase) {
        AlertPhase.SENDING -> "Sending…"
        AlertPhase.REPORTED -> alert.severity
            ?.let { "Pothole · ${it.wireValue.replaceFirstChar(Char::uppercase)}" }
            ?: "Pothole detected"
        AlertPhase.QUEUED -> "Couldn't reach the server; will retry"
        AlertPhase.UNDOING -> "Removing…"
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF20262E)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFFFFC857), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("!", color = Color(0xFF20262E), fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "New hazard reported",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = detail, color = Color(0xFFE2E5EA))
            }
            TextButton(
                onClick = onUndo,
                enabled = alert.phase != AlertPhase.UNDOING && alert.undoSecondsLeft > 0,
            ) {
                Text(
                    text = "Undo (${alert.undoSecondsLeft} s)",
                    color = Color.White,
                )
            }
        }
    }
}

private fun formatDistance(meters: Double): String =
    String.format(Locale.US, "%.1f mi", meters / 1_609.344)

private fun formatMinutes(seconds: Long): String {
    val minutes = (seconds + 59) / 60
    return if (minutes <= 1) "1 min" else "$minutes min"
}
