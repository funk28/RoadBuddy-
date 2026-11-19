package com.roadbuddy.core.network

import com.roadbuddy.core.model.DetectionMeta
import com.roadbuddy.core.model.DetectionOutcome
import com.roadbuddy.core.model.DetectionResult
import com.roadbuddy.core.model.ReportedHazard
import com.roadbuddy.core.model.Severity
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/**
 * In-memory stand-in for the Flask detection endpoints, so the navigation
 * screen can be demoed and tested without a server.
 *
 * It follows the contract: the same `client_detection_id` returns the same
 * detection, and undo only works until `undo_expires_at`.
 *
 * Set [offline] or [failNextReports] to demo the "report waiting to send" path.
 */
class StubDetectionApi(
    private val clock: () -> Instant = Instant::now,
    private val undoWindow: Duration = Duration.ofSeconds(5),
    private val latencyMillis: Long = 300,
    private val severity: Severity = Severity.SEVERE,
) : DetectionApi {
    private data class Stored(val result: DetectionResult, var undone: Boolean = false)

    private val byClientId = LinkedHashMap<String, Stored>()
    private val byDetectionId = HashMap<String, Stored>()
    private var nextId = 1

    /** While true, every call fails as if the phone had no connection. */
    @Volatile
    var offline: Boolean = false

    /** The next N report calls fail with `503 vlm_unavailable`. */
    @Volatile
    var failNextReports: Int = 0

    /** Number of detections the stub has stored and not undone. */
    val activeDetectionCount: Int
        @Synchronized get() = byClientId.values.count { !it.undone }

    override suspend fun reportDetection(meta: DetectionMeta, jpeg: ByteArray): DetectionResult {
        delay(latencyMillis)
        if (offline) throw IOException("Stub is offline")
        synchronized(this) {
            if (failNextReports > 0) {
                failNextReports--
                throw ApiException(503, "vlm_unavailable", "Hazard classifier did not respond in time.")
            }
            byClientId[meta.clientDetectionId]?.let { return it.result }

            val id = nextId++
            val result = DetectionResult(
                detectionId = "d_$id",
                outcome = DetectionOutcome.CREATED,
                hazard = ReportedHazard(
                    id = "h_$id",
                    type = "pothole",
                    severity = severity,
                    location = meta.location,
                    sightingCount = 1,
                ),
                undoExpiresAt = clock().plus(undoWindow),
            )
            val stored = Stored(result)
            byClientId[meta.clientDetectionId] = stored
            byDetectionId[result.detectionId!!] = stored
            return result
        }
    }

    override suspend fun undoDetection(detectionId: String): Boolean {
        delay(latencyMillis)
        if (offline) throw IOException("Stub is offline")
        synchronized(this) {
            val stored = byDetectionId[detectionId]
                ?: throw ApiException(404, "not_found", "No detection $detectionId")
            val expiresAt = stored.result.undoExpiresAt
            if (expiresAt != null && clock().isAfter(expiresAt)) {
                throw ApiException(409, "undo_expired", "The undo window has closed.")
            }
            stored.undone = true
            return true
        }
    }
}
