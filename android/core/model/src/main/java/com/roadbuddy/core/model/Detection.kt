package com.roadbuddy.core.model

import java.time.Instant

/**
 * The `meta` part of `POST /v1/detections` (see shared/openapi.yaml, `DetectionMeta`).
 *
 * [clientDetectionId] is generated on the phone and reused on every retry so the
 * server never creates duplicates.
 */
data class DetectionMeta(
    val clientDetectionId: String,
    val capturedAt: Instant,
    val location: LatLng,
    val headingDeg: Double?,
    val localConfidence: Double,
)

/** `outcome` of `POST /v1/detections`. */
enum class DetectionOutcome(val wireValue: String) {
    CREATED("created"),
    MATCHED("matched"),
    REJECTED("rejected"),
    ;

    companion object {
        fun fromWire(value: String): DetectionOutcome =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown detection outcome: $value")
    }
}

/** `minor | moderate | severe` in the API contract. */
enum class Severity(val wireValue: String) {
    MINOR("minor"),
    MODERATE("moderate"),
    SEVERE("severe"),
    ;

    companion object {
        fun fromWire(value: String): Severity =
            entries.firstOrNull { it.wireValue == value }
                ?: throw IllegalArgumentException("Unknown severity: $value")
    }
}

/** The hazard a detection was stored as (or merged into). */
data class ReportedHazard(
    val id: String,
    val type: String,
    val severity: Severity,
    val location: LatLng,
    val sightingCount: Int,
)

/** Response body of `POST /v1/detections`. */
data class DetectionResult(
    val detectionId: String?,
    val outcome: DetectionOutcome,
    val hazard: ReportedHazard?,
    val undoExpiresAt: Instant?,
)
