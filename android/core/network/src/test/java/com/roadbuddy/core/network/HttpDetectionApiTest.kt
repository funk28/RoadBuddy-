package com.roadbuddy.core.network

import com.roadbuddy.core.model.DetectionMeta
import com.roadbuddy.core.model.LatLng
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HttpDetectionApiTest {
    private class Captured(
        val method: String,
        val path: String,
        val contentType: String?,
        val authorization: String?,
        val body: ByteArray,
    )

    private lateinit var server: HttpServer
    private val requests = mutableListOf<Captured>()
    private var status = 200
    private var responseBody = ""

    private val meta = DetectionMeta(
        clientDetectionId = "8f1c",
        capturedAt = Instant.parse("2026-11-13T14:48:02Z"),
        location = LatLng(40.4237, -86.9212),
        headingDeg = 92.0,
        localConfidence = 0.81,
    )

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            synchronized(requests) {
                requests += Captured(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.rawPath,
                    contentType = exchange.requestHeaders.getFirst("Content-Type"),
                    authorization = exchange.requestHeaders.getFirst("Authorization"),
                    body = exchange.requestBody.readBytes(),
                )
            }
            val bytes = responseBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun api(token: String? = "test-token") =
        HttpDetectionApi("http://127.0.0.1:${server.address.port}/v1/", idTokenProvider = { token })

    @Test
    fun postsMultipartWithMetaAndImage() = runBlocking {
        responseBody = """{"detection_id": "d_1", "outcome": "created", "hazard": null, "undo_expires_at": null}"""
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

        val result = api().reportDetection(meta, jpeg)

        assertEquals("d_1", result.detectionId)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/detections", request.path)
        assertEquals("Bearer test-token", request.authorization)
        val boundary = request.contentType!!.substringAfter("boundary=")
        assertTrue(request.contentType!!.startsWith("multipart/form-data"))

        val body = String(request.body, Charsets.ISO_8859_1)
        assertTrue(body.startsWith("--$boundary\r\n"))
        assertTrue(body.endsWith("--$boundary--\r\n"))
        assertTrue(body.contains("name=\"meta\""))
        assertTrue(body.contains("\"client_detection_id\":\"8f1c\""))
        assertTrue(body.contains("name=\"image\"; filename=\"frame.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"))

        val imageStart = body.indexOf("Content-Type: image/jpeg\r\n\r\n") + "Content-Type: image/jpeg\r\n\r\n".length
        val imageBytes = request.body.copyOfRange(imageStart, imageStart + jpeg.size)
        assertArrayEquals(jpeg, imageBytes)
    }

    @Test
    fun deletesDetectionById() = runBlocking {
        responseBody = """{"detection_id": "d_123", "undone": true}"""

        assertTrue(api(token = null).undoDetection("d_123"))

        val request = requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/detections/d_123", request.path)
        assertEquals(null, request.authorization)
    }

    @Test
    fun turnsVlmTimeoutIntoRetryableApiException() = runBlocking {
        status = 503
        responseBody = """{"error": {"code": "vlm_unavailable", "message": "Hazard classifier did not respond in time."}}"""

        try {
            api().reportDetection(meta, byteArrayOf())
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals(503, e.httpStatus)
            assertEquals("vlm_unavailable", e.code)
            assertTrue(e.isRetryable)
        }
    }

    @Test
    fun badRequestIsNotRetryable() = runBlocking {
        status = 400
        responseBody = """{"error": {"code": "missing_field", "message": "client_detection_id is required"}}"""

        try {
            api().reportDetection(meta, byteArrayOf())
            fail("Expected ApiException")
        } catch (e: ApiException) {
            assertEquals("missing_field", e.code)
            assertFalse(e.isRetryable)
        }
    }

    @Test
    fun unreachableServerThrowsIOException() = runBlocking {
        val port = server.address.port
        server.stop(0)
        try {
            HttpDetectionApi("http://127.0.0.1:$port/v1", connectTimeoutMillis = 1_000).undoDetection("d_1")
            fail("Expected IOException")
        } catch (e: IOException) {
            assertFalse(e is ApiException)
        }
    }
}
