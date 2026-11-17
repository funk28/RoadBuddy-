package com.roadbuddy.core.network

import com.roadbuddy.core.model.DetectionMeta
import com.roadbuddy.core.model.LatLng
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StubDetectionApiTest {
    private var now = Instant.parse("2026-11-17T22:00:00Z")
    private val stub = StubDetectionApi(clock = { now }, latencyMillis = 0)

    private fun meta(id: String) = DetectionMeta(id, now, LatLng(40.4237, -86.9212), 90.0, 0.8)

    @Test
    fun sameClientIdReturnsSameDetection() = runBlocking {
        val first = stub.reportDetection(meta("a"), byteArrayOf())
        val retry = stub.reportDetection(meta("a"), byteArrayOf())
        val other = stub.reportDetection(meta("b"), byteArrayOf())

        assertEquals(first, retry)
        assertTrue(first.detectionId != other.detectionId)
        assertEquals(2, stub.activeDetectionCount)
    }

    @Test
    fun undoWorksOnlyInsideTheWindow() = runBlocking {
        val inWindow = stub.reportDetection(meta("a"), byteArrayOf())
        val late = stub.reportDetection(meta("b"), byteArrayOf())

        now = now.plusSeconds(4)
        assertTrue(stub.undoDetection(inWindow.detectionId!!))

        now = now.plusSeconds(2)
        try {
            stub.undoDetection(late.detectionId!!)
            fail("Expected undo_expired")
        } catch (e: ApiException) {
            assertEquals("undo_expired", e.code)
        }
        assertEquals(1, stub.activeDetectionCount)
    }

    @Test
    fun simulatesOutagesAndVlmTimeouts() = runBlocking {
        stub.offline = true
        try {
            stub.reportDetection(meta("a"), byteArrayOf())
            fail("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e !is ApiException)
        }

        stub.offline = false
        stub.failNextReports = 1
        try {
            stub.reportDetection(meta("a"), byteArrayOf())
            fail("Expected vlm_unavailable")
        } catch (e: ApiException) {
            assertEquals(503, e.httpStatus)
        }
        assertEquals("d_1", stub.reportDetection(meta("a"), byteArrayOf()).detectionId)
    }
}
