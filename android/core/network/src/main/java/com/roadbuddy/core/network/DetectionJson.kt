package com.roadbuddy.core.network

import com.roadbuddy.core.model.DetectionMeta
import com.roadbuddy.core.model.DetectionOutcome
import com.roadbuddy.core.model.DetectionResult
import com.roadbuddy.core.model.LatLng
import com.roadbuddy.core.model.ReportedHazard
import com.roadbuddy.core.model.Severity
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Maps the detection models to and from the JSON in shared/openapi.yaml. */
internal object DetectionJson {
    fun encodeMeta(meta: DetectionMeta): String = Json.stringify(
        buildMap {
            put("client_detection_id", meta.clientDetectionId)
            // Whole seconds, e.g. "2026-11-13T14:48:02Z", as in the contract examples.
            put("captured_at", meta.capturedAt.truncatedTo(ChronoUnit.SECONDS).toString())
            put("location", mapOf("lat" to meta.location.lat, "lng" to meta.location.lng))
            meta.headingDeg?.let { put("heading_deg", it) }
            put("local_confidence", meta.localConfidence)
        },
    )

    fun decodeResult(body: String): DetectionResult {
        val root = Json.parse(body).asObject("response")
        val hazard = root["hazard"]?.asObject("hazard")?.let { h ->
            ReportedHazard(
                id = h.string("id"),
                // The contract example omits `type`; potholes are the only type for now.
                type = h["type"] as? String ?: "pothole",
                severity = Severity.fromWire(h.string("severity")),
                location = LatLng(h.number("lat"), h.number("lng")),
                sightingCount = (h["sighting_count"] as? Double)?.toInt() ?: 1,
            )
        }
        return DetectionResult(
            detectionId = root["detection_id"] as? String,
            outcome = DetectionOutcome.fromWire(root.string("outcome")),
            hazard = hazard,
            undoExpiresAt = (root["undo_expires_at"] as? String)?.let(Instant::parse),
        )
    }

    fun decodeUndo(body: String): Boolean =
        Json.parse(body).asObject("response")["undone"] as? Boolean
            ?: throw IllegalArgumentException("Missing 'undone'")

    /** Reads `{"error": {"code", "message"}}`, or null if [body] isn't that shape. */
    fun decodeError(body: String): Pair<String, String>? = runCatching {
        val error = Json.parse(body).asObject("response")["error"]!!.asObject("error")
        error.string("code") to (error["message"] as? String ?: "")
    }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObject(name: String): Map<String, Any?> =
        this as? Map<String, Any?> ?: throw IllegalArgumentException("'$name' is not an object")

    private fun Map<String, Any?>.string(key: String): String =
        this[key] as? String ?: throw IllegalArgumentException("Missing string '$key'")

    private fun Map<String, Any?>.number(key: String): Double =
        this[key] as? Double ?: throw IllegalArgumentException("Missing number '$key'")
}
