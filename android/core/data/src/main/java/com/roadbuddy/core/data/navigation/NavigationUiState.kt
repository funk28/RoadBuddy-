package com.roadbuddy.core.data.navigation

import com.roadbuddy.core.model.Severity

/**
 * Everything the Navigation screen (Figma screens 10 and 11) renders.
 * Produced by [NavigationSession.state]; the UI only reads it.
 */
data class NavigationUiState(
    val route: Route,
    /** True between Start and End (or arrival). */
    val driving: Boolean = false,
    /** True once the simulated drive reached the end of the route. */
    val arrived: Boolean = false,
    /** The "you are here" marker. */
    val position: RoutePosition,
    /** 0..1 along the route, handy for drawing the travelled part of the line. */
    val progress: Float = 0f,
    /** Turn card. Null once only arrival is left. */
    val turn: TurnCardState? = null,
    /** Trip bar. */
    val trip: TripBarState,
    /** Screen 11 "New hazard reported" alert, or null when none is showing. */
    val alert: HazardAlertState? = null,
    /** Detections saved on the phone that still have to be sent ("1 report waiting to send"). */
    val pendingUploads: Int = 0,
    /** One-off feedback to show as a toast/snackbar, then clear with [NavigationSession.messageShown]. */
    val message: NavigationMessage? = null,
)

data class TurnCardState(
    val instruction: String,
    val distanceM: Double,
)

data class TripBarState(
    val remainingDistanceM: Double,
    val remainingTimeSeconds: Long,
)

data class HazardAlertState(
    val clientDetectionId: String,
    val hazardType: String,
    /** Filled in once the server has classified the frame. */
    val severity: Severity?,
    /** Seconds left on the Undo button; the alert disappears when it reaches 0. */
    val undoSecondsLeft: Int,
    val phase: AlertPhase,
)

enum class AlertPhase {
    /** `POST /v1/detections` is in flight. */
    SENDING,

    /** The server stored the detection. */
    REPORTED,

    /** Couldn't reach the server; the detection is queued and will be retried. */
    QUEUED,

    /** The driver tapped Undo and `DELETE /v1/detections/{id}` is in flight. */
    UNDOING,
}

/** Short feedback for the driver, worded as in the API contract. */
enum class NavigationMessage(val text: String) {
    HAZARD_REMOVED("Hazard removed"),
    UNDO_FAILED("Couldn't undo, remove it from My reports"),
    NOT_A_HAZARD("Not confirmed as a hazard"),
    REPORT_FAILED("Couldn't report hazard"),
}
