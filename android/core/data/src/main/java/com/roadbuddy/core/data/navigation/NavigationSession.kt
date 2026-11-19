package com.roadbuddy.core.data.navigation

import com.roadbuddy.core.model.DetectionMeta
import com.roadbuddy.core.model.DetectionOutcome
import com.roadbuddy.core.network.ApiException
import com.roadbuddy.core.network.DetectionApi
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Navigation and detection logic for the Navigation screen (issue #38).
 *
 * - Drives a simulated position along a mocked [Route].
 * - [reportPothole] stands in for the YOLO model: it takes a mock pothole frame
 *   tagged with EXIF GPS for the current position ([frameSource]), sends
 *   `POST /v1/detections`, and shows the "New hazard reported" alert.
 * - [undo] calls `DELETE /v1/detections/{id}` while the 5 s window is open.
 * - Failed reports are kept on the phone and retried with the same
 *   `client_detection_id`, as the API contract describes.
 *
 * The UI reads [state] and calls the action functions; it holds no logic itself.
 * Create one per app (like `DriveSessionGate`) with a main-thread [scope], and
 * call the functions from the main thread.
 */
class NavigationSession(
    private val scope: CoroutineScope,
    private val api: DetectionApi,
    val route: Route = MockRoutes.DEFAULT,
    private val speedMps: Double = SimulatedGps.DEFAULT_SPEED_MPS,
    private val positionSource: (startAtM: Double) -> Flow<RoutePosition> =
        SimulatedGps(route, speedMps)::positions,
    private val clock: () -> Instant = Instant::now,
    private val newClientDetectionId: () -> String = { UUID.randomUUID().toString() },
    private val undoWindow: Duration = Duration.ofSeconds(5),
    private val countdownTickMillis: Long = 250,
    private val retryDelayMillis: Long = 5_000,
    /** The JPEG to upload for a detection at a position and time; must carry EXIF GPS. */
    private val frameSource: (RoutePosition, Instant) -> ByteArray = MockPotholeFrames::frameAt,
) {
    private val mutableState = MutableStateFlow(stateAt(distanceM = 0.0))

    val state: StateFlow<NavigationUiState> = mutableState.asStateFlow()

    /** One detection from trigger to server, kept until it's sent or undone. */
    private class Detection(
        val meta: DetectionMeta,
        val jpeg: ByteArray,
        val undoDeadline: Instant,
    ) {
        var detectionId: String? = null
        var undoRequested = false
        var sending = false
    }

    private var driveJob: Job? = null
    private var countdownJob: Job? = null
    private var retryJob: Job? = null
    private var current: Detection? = null
    private val pending = ArrayDeque<Detection>()

    // ---- Driving --------------------------------------------------------------------

    /** Start (or resume) the simulated drive. Starts over if the last drive arrived. */
    fun startDrive() {
        if (driveJob?.isActive == true) return
        val startAt = if (state.value.arrived) 0.0 else state.value.position.distanceAlongM
        mutableState.update { it.copy(driving = true, arrived = false) }
        driveJob = scope.launch {
            positionSource(startAt).collect { position ->
                mutableState.update { stateAt(position.distanceAlongM, it).copy(position = position) }
            }
            mutableState.update { it.copy(driving = false, arrived = true) }
        }
    }

    /** Stop the drive. Queued reports keep retrying in the background. */
    fun endDrive() {
        driveJob?.cancel()
        driveJob = null
        mutableState.update { it.copy(driving = false) }
    }

    // ---- Detection ------------------------------------------------------------------

    /**
     * The fake "pothole detected" trigger: reports a pothole at the current position.
     * Does nothing unless a drive is in progress. Returns the `client_detection_id`.
     *
     * The uploaded image comes from [frameSource] for the same position and time
     * as the `meta` JSON, unless [jpeg] is given.
     */
    fun reportPothole(
        localConfidence: Double = 0.81,
        jpeg: ByteArray? = null,
    ): String? {
        val snapshot = state.value
        if (!snapshot.driving) return null

        val now = clock()
        val detection = Detection(
            meta = DetectionMeta(
                clientDetectionId = newClientDetectionId(),
                capturedAt = now,
                location = snapshot.position.location,
                headingDeg = snapshot.position.headingDeg,
                localConfidence = localConfidence,
            ),
            jpeg = jpeg ?: frameSource(snapshot.position, now),
            undoDeadline = now.plus(undoWindow),
        )
        current = detection
        showAlert(detection, AlertPhase.SENDING)
        startCountdown(detection)
        scope.launch { send(detection) }
        return detection.meta.clientDetectionId
    }

    /** The alert's Undo button. Ignored once the window has closed. */
    fun undo() {
        val detection = current ?: return
        if (detection.undoRequested || clock().isAfter(detection.undoDeadline)) return
        detection.undoRequested = true
        updateAlert(detection) { it.copy(phase = AlertPhase.UNDOING) }

        when {
            // Queued and not being retried right now: it never reached the
            // server, so dropping it locally is the whole undo.
            !detection.sending && pending.remove(detection) -> {
                publishPendingCount()
                finishAlert(detection, NavigationMessage.HAZARD_REMOVED)
            }
            detection.detectionId != null -> scope.launch { deleteOnServer(detection) }
            // Still in flight: send() deletes it as soon as the server returns an id.
            else -> Unit
        }
    }

    /** Call after showing [NavigationUiState.message]. */
    fun messageShown() {
        mutableState.update { it.copy(message = null) }
    }

    // ---- Internals ------------------------------------------------------------------

    private suspend fun send(detection: Detection) {
        detection.sending = true
        val result = try {
            api.reportDetection(detection.meta, detection.jpeg)
        } catch (e: IOException) {
            detection.sending = false
            onSendFailed(detection, e)
            return
        }
        detection.sending = false
        detection.detectionId = result.detectionId
        if (pending.remove(detection)) publishPendingCount()

        when {
            result.outcome == DetectionOutcome.REJECTED ->
                finishAlert(detection, NavigationMessage.NOT_A_HAZARD)
            detection.undoRequested -> deleteOnServer(detection)
            else -> updateAlert(detection) {
                it.copy(phase = AlertPhase.REPORTED, severity = result.hazard?.severity)
            }
        }
    }

    private fun onSendFailed(detection: Detection, error: IOException) {
        val retryable = error !is ApiException || error.isRetryable
        if (retryable && !detection.undoRequested) {
            if (detection !in pending) pending.addLast(detection)
            publishPendingCount()
            updateAlert(detection) { it.copy(phase = AlertPhase.QUEUED) }
            ensureRetryLoop()
            return
        }
        if (pending.remove(detection)) publishPendingCount()
        finishAlert(
            detection,
            // If the driver already undid it, there's nothing worth sending.
            if (detection.undoRequested) NavigationMessage.HAZARD_REMOVED else NavigationMessage.REPORT_FAILED,
        )
    }

    private suspend fun deleteOnServer(detection: Detection) {
        val id = detection.detectionId ?: return
        val undone = try {
            api.undoDetection(id)
        } catch (_: IOException) {
            false
        }
        finishAlert(
            detection,
            if (undone) NavigationMessage.HAZARD_REMOVED else NavigationMessage.UNDO_FAILED,
        )
    }

    private fun ensureRetryLoop() {
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (pending.isNotEmpty()) {
                delay(retryDelayMillis)
                // Oldest first; stop at the first failure and wait before trying again.
                // send() takes each one off the queue once the server has it.
                for (next in pending.toList()) {
                    if (next !in pending || next.sending) continue
                    send(next)
                    if (next in pending) break
                }
            }
        }
    }

    private fun startCountdown(detection: Detection) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            while (true) {
                val leftMillis = Duration.between(clock(), detection.undoDeadline).toMillis()
                if (leftMillis <= 0) break
                updateAlert(detection) { it.copy(undoSecondsLeft = ceilSeconds(leftMillis)) }
                delay(minOf(countdownTickMillis, leftMillis))
            }
            // Window closed. An undo already in flight clears the alert itself.
            if (!detection.undoRequested) {
                mutableState.update { state ->
                    if (state.alert?.clientDetectionId == detection.meta.clientDetectionId) {
                        state.copy(alert = null)
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun showAlert(detection: Detection, phase: AlertPhase) {
        mutableState.update {
            it.copy(
                alert = HazardAlertState(
                    clientDetectionId = detection.meta.clientDetectionId,
                    hazardType = "pothole",
                    severity = null,
                    undoSecondsLeft = ceilSeconds(undoWindow.toMillis()),
                    phase = phase,
                ),
            )
        }
    }

    /** Changes the alert only if it still belongs to [detection]. */
    private fun updateAlert(detection: Detection, change: (HazardAlertState) -> HazardAlertState) {
        mutableState.update { state ->
            val alert = state.alert
            if (alert?.clientDetectionId == detection.meta.clientDetectionId) {
                state.copy(alert = change(alert))
            } else {
                state
            }
        }
    }

    /** Hides [detection]'s alert (if still showing) and shows [message]. */
    private fun finishAlert(detection: Detection, message: NavigationMessage) {
        mutableState.update { state ->
            val ownsAlert = state.alert?.clientDetectionId == detection.meta.clientDetectionId
            state.copy(alert = if (ownsAlert) null else state.alert, message = message)
        }
        if (current === detection) {
            countdownJob?.cancel()
            current = null
        }
    }

    private fun publishPendingCount() {
        mutableState.update { it.copy(pendingUploads = pending.size) }
    }

    private fun stateAt(distanceM: Double, previous: NavigationUiState? = null): NavigationUiState {
        val position = route.positionAt(distanceM)
        val remainingM = (route.lengthM - position.distanceAlongM).coerceAtLeast(0.0)
        val turn = route.nextStepAfter(position.distanceAlongM)?.let {
            TurnCardState(it.instruction, it.atDistanceM - position.distanceAlongM)
        }
        val trip = TripBarState(remainingM, (remainingM / speedMps).toLong())
        return previous?.copy(
            position = position,
            progress = route.progressAt(position.distanceAlongM),
            turn = turn,
            trip = trip,
        ) ?: NavigationUiState(
            route = route,
            position = position,
            progress = route.progressAt(position.distanceAlongM),
            turn = turn,
            trip = trip,
        )
    }

    private fun ceilSeconds(millis: Long): Int = ((millis + 999) / 1_000).toInt()
}
