package com.roadbuddy.core.network

import com.roadbuddy.core.model.DetectionMeta
import com.roadbuddy.core.model.DetectionOutcome
import com.roadbuddy.core.model.LatLng
import com.roadbuddy.core.model.Severity
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionJsonTest {
    @Test
    fun jsonRoundTripsNestedValuesAndEscapes() {
        val text = """{"a": [1, 2.5, -3e2], "s": "q\"uote\\ \n é", "t": true, "n": null, "o": {}}"""
        val parsed = Json.parse(text) as Map<*, *>

        assertEquals(listOf(1.0, 2.5, -300.0), parsed["a"])
        assertEquals("q\"uote\\ \n é", parsed["s"])
        assertEquals(true, parsed["t"])
        assertTrue(parsed.containsKey("n"))
        assertNull(parsed["n"])
        assertEquals(emptyMap<String, Any?>(), parsed["o"])
        assertEquals(parsed, Json.parse(Json.stringify(parsed)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun jsonRejectsTrailingGarbage() {
        Json.parse("""{"a": 1} x""")
    }

    @Test
    fun encodesMetaWithContractFieldNames() {
        val meta = DetectionMeta(
            clientDetectionId = "8f1c",
            capturedAt = Instant.parse("2026-11-13T14:48:02.734Z"),
            location = LatLng(40.4237, -86.9212),
            headingDeg = 92.0,
            localConfidence = 0.81,
        )

        val json = Json.parse(DetectionJson.encodeMeta(meta)) as Map<*, *>

        assertEquals("8f1c", json["client_detection_id"])
        assertEquals("2026-11-13T14:48:02Z", json["captured_at"])
        assertEquals(mapOf("lat" to 40.4237, "lng" to -86.9212), json["location"])
        assertEquals(92.0, json["heading_deg"])
        assertEquals(0.81, json["local_confidence"])
    }

    @Test
    fun omitsHeadingWhenUnknown() {
        val meta = DetectionMeta("id", Instant.EPOCH, LatLng(0.0, 0.0), null, 0.5)
        assertFalse((Json.parse(DetectionJson.encodeMeta(meta)) as Map<*, *>).containsKey("heading_deg"))
    }

    @Test
    fun decodesContractExampleResponse() {
        val body = """
            {"detection_id": "d_123", "outcome": "created",
             "hazard": {"id": "h_45", "severity": "severe", "lat": 40.4237, "lng": -86.9212, "sighting_count": 1},
             "undo_expires_at": "2026-11-13T14:48:09Z"}
        """.trimIndent()

        val result = DetectionJson.decodeResult(body)

        assertEquals("d_123", result.detectionId)
        assertEquals(DetectionOutcome.CREATED, result.outcome)
        assertEquals("h_45", result.hazard!!.id)
        assertEquals("pothole", result.hazard!!.type)
        assertEquals(Severity.SEVERE, result.hazard!!.severity)
        assertEquals(LatLng(40.4237, -86.9212), result.hazard!!.location)
        assertEquals(1, result.hazard!!.sightingCount)
        assertEquals(Instant.parse("2026-11-13T14:48:09Z"), result.undoExpiresAt)
    }

    @Test
    fun decodesFlaskResponseWithExtraFieldsAndMicroseconds() {
        // Shape returned by backend/src/app/api/detections.py.
        val body = """
            {"detection_id": "d_7", "outcome": "matched",
             "hazard": {"id": "h_2", "type": "pothole", "severity": "moderate", "status": "live",
                        "lat": 40.4237, "lng": -86.9212, "sighting_count": 3,
                        "expires_at": "2026-11-18T22:30:00.123456Z"},
             "undo_expires_at": "2026-11-17T22:30:05.123456Z"}
        """.trimIndent()

        val result = DetectionJson.decodeResult(body)

        assertEquals(DetectionOutcome.MATCHED, result.outcome)
        assertEquals(3, result.hazard!!.sightingCount)
        assertEquals(Instant.parse("2026-11-17T22:30:05.123456Z"), result.undoExpiresAt)
    }

    @Test
    fun decodesRejectedResponseWithoutHazard() {
        val body = """{"detection_id": null, "outcome": "rejected", "hazard": null, "undo_expires_at": null}"""

        val result = DetectionJson.decodeResult(body)

        assertEquals(DetectionOutcome.REJECTED, result.outcome)
        assertNull(result.detectionId)
        assertNull(result.hazard)
        assertNull(result.undoExpiresAt)
    }

    @Test
    fun decodesUndoAndErrorBodies() {
        assertTrue(DetectionJson.decodeUndo("""{"detection_id": "d_123", "undone": true}"""))
        assertEquals(
            "vlm_unavailable" to "slow",
            DetectionJson.decodeError("""{"error": {"code": "vlm_unavailable", "message": "slow"}}"""),
        )
        assertNull(DetectionJson.decodeError("<html>Internal Server Error</html>"))
    }
}
