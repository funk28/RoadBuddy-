package com.roadbuddy.app.navigation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.roadbuddy.core.data.DriveSessionGate
import kotlinx.coroutines.delay

private data class MockAlert(
    val id: Int,
    val type: String = "Pothole",
)

private val RouteBlue = Color(0xFF1E6BE3)
private val MapRoad = Color(0xFFFFFFFF)
private val MapRoadEdge = Color(0xFFDDE2E8)
private val MapBackground = Color(0xFFEAF0ED)
private val MapBlock = Color(0xFFDDE8DF)

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
    var routeProgress by remember { mutableFloatStateOf(0.18f) }
    var alert by remember { mutableStateOf<MockAlert?>(null) }
    var undoSeconds by remember { mutableIntStateOf(5) }
    var nextAlertId by remember { mutableIntStateOf(1) }
    var undoMessage by remember { mutableStateOf<String?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraRuntime.cameraAllowed = granted
        if (granted) {
            gate.startSession()
        }
    }

    LaunchedEffect(sessionActive) {
        if (sessionActive) {
            while (routeProgress < 0.92f) {
                delay(1_500)
                routeProgress = (routeProgress + 0.025f).coerceAtMost(0.92f)
            }
        }
    }

    LaunchedEffect(alert) {
        if (alert != null) {
            undoSeconds = 5
            undoMessage = null
            while (undoSeconds > 0) {
                delay(1_000)
                undoSeconds -= 1
            }
            alert = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MockMap(routeProgress)

        TurnCard(
            sessionActive = sessionActive,
            routeProgress = routeProgress,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
        )

        DetectionChip(
            sessionActive = sessionActive,
            detectionEnabled = canCapture,
            settingsReadError = cameraRuntime.settingsReadError,
            cameraError = cameraRuntime.cameraError,
            framesObserved = cameraRuntime.framesObserved,
            onSimulate = {
                alert = MockAlert(nextAlertId)
                nextAlertId += 1
            },
            onRetrySettings = { cameraRuntime.settingsRetryAttempt++ },
            onRetryCamera = { cameraRuntime.cameraRetryAttempt++ },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp),
        )

        TripBar(
            sessionActive = sessionActive,
            routeProgress = routeProgress,
            onStart = {
                if (context.hasCameraPermission()) {
                    cameraRuntime.cameraAllowed = true
                    gate.startSession()
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            onEnd = gate::stopSession,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )

        alert?.let {
            HazardAlert(
                alert = it,
                undoSeconds = undoSeconds,
                undoMessage = undoMessage,
                onUndo = {
                    undoMessage = "Hazard removed"
                    alert = null
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 112.dp),
            )
        }
    }
}

@Composable
private fun MockMap(routeProgress: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        drawRect(MapBackground)

        listOf(
            listOf(Offset(0f, height * 0.14f), Offset(width, height * 0.28f)),
            listOf(Offset(0f, height * 0.43f), Offset(width, height * 0.36f)),
            listOf(Offset(0f, height * 0.68f), Offset(width, height * 0.62f)),
            listOf(Offset(width * 0.18f, 0f), Offset(width * 0.32f, height)),
            listOf(Offset(width * 0.52f, 0f), Offset(width * 0.44f, height)),
            listOf(Offset(width * 0.78f, 0f), Offset(width * 0.69f, height)),
        ).forEach { road ->
            drawLine(
                color = MapRoadEdge,
                start = road[0],
                end = road[1],
                strokeWidth = 42.dp.toPx(),
                cap = StrokeCap.Round,
            )
            drawLine(
                color = MapRoad,
                start = road[0],
                end = road[1],
                strokeWidth = 34.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }

        val blocks = listOf(
            Offset(width * 0.05f, height * 0.30f) to Offset(width * 0.22f, height * 0.38f),
            Offset(width * 0.34f, height * 0.10f) to Offset(width * 0.48f, height * 0.25f),
            Offset(width * 0.56f, height * 0.35f) to Offset(width * 0.74f, height * 0.44f),
            Offset(width * 0.80f, height * 0.70f) to Offset(width * 0.96f, height * 0.86f),
        )
        blocks.forEach { (topLeft, bottomRight) ->
            drawRoundRect(
                color = MapBlock,
                topLeft = topLeft,
                size = androidx.compose.ui.geometry.Size(
                    width = bottomRight.x - topLeft.x,
                    height = bottomRight.y - topLeft.y,
                ),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()),
            )
        }

        val route = listOf(
            Offset(width * 0.12f, height * 0.82f),
            Offset(width * 0.25f, height * 0.72f),
            Offset(width * 0.32f, height * 0.60f),
            Offset(width * 0.48f, height * 0.55f),
            Offset(width * 0.58f, height * 0.44f),
            Offset(width * 0.72f, height * 0.38f),
            Offset(width * 0.82f, height * 0.24f),
        )
        val routePath = Path().apply {
            moveTo(route.first().x, route.first().y)
            route.drop(1).forEach { lineTo(it.x, it.y) }
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

        val segment = (routeProgress * (route.size - 1)).toInt().coerceIn(0, route.size - 2)
        val segmentProgress = (routeProgress * (route.size - 1) - segment).coerceIn(0f, 1f)
        val start = route[segment]
        val end = route[segment + 1]
        val position = Offset(
            x = start.x + (end.x - start.x) * segmentProgress,
            y = start.y + (end.y - start.y) * segmentProgress,
        )
        drawCircle(color = Color.White, radius = 17.dp.toPx(), center = position)
        drawCircle(color = RouteBlue, radius = 11.dp.toPx(), center = position)
    }
}

@Composable
private fun TurnCard(
    sessionActive: Boolean,
    routeProgress: Float,
    modifier: Modifier = Modifier,
) {
    val (instruction, distance) = when {
        !sessionActive -> "Route ready" to "Start drive"
        routeProgress < 0.45f -> "Turn right" to "in 500 ft"
        routeProgress < 0.75f -> "Continue straight" to "for 1.2 mi"
        else -> "Destination ahead" to "in 300 ft"
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "→",
                style = MaterialTheme.typography.headlineSmall,
                color = RouteBlue,
            )
            Text(
                text = instruction,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(text = distance, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DetectionChip(
    sessionActive: Boolean,
    detectionEnabled: Boolean,
    settingsReadError: Boolean,
    cameraError: Boolean,
    framesObserved: Long,
    onSimulate: () -> Unit,
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
                containerColor = if (detectionEnabled) Color(0xFFE8F0FE) else Color.White,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        ) {
            Text(
                text = if (detectionEnabled) "Detection on" else "Detection off",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                color = if (detectionEnabled) RouteBlue else Color(0xFF5D687A),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        if (sessionActive && detectionEnabled) {
            OutlinedButton(
                onClick = onSimulate,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White),
            ) {
                Text("Detect pothole")
            }
        }
        if (settingsReadError) {
            OutlinedButton(onClick = onRetrySettings) { Text("Retry settings") }
        }
        if (cameraError) {
            OutlinedButton(onClick = onRetryCamera) { Text("Retry camera") }
        }
        if (detectionEnabled) {
            Text(
                text = "$framesObserved frames",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF5D687A),
            )
        }
    }
}

@Composable
private fun TripBar(
    sessionActive: Boolean,
    routeProgress: Float,
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
                    text = if (sessionActive) "${12 - (routeProgress * 8).toInt()} min" else "4.8 mi",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (sessionActive) "Arrival 2:42 PM" else "Mock route",
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
private fun HazardAlert(
    alert: MockAlert,
    undoSeconds: Int,
    undoMessage: String?,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                Text(
                    text = "${alert.type} detected",
                    color = Color(0xFFE2E5EA),
                    style = MaterialTheme.typography.bodyMedium,
                )
                undoMessage?.let {
                    Text(
                        text = it,
                        color = Color(0xFF86E89B),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Button(
                onClick = onUndo,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF20262E),
                ),
            ) {
                Text("Undo ($undoSeconds s)")
            }
        }
    }
}
