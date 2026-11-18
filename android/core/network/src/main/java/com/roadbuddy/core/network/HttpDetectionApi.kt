package com.roadbuddy.core.network

import com.roadbuddy.core.model.DetectionMeta
import com.roadbuddy.core.model.DetectionResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [DetectionApi] over HTTP, using only [HttpURLConnection] so no networking
 * library is needed yet.
 *
 * @param baseUrl server root including the version prefix, e.g.
 *   `http://10.0.2.2:5000/v1` for a Flask dev server on the emulator's host.
 * @param idTokenProvider returns the Google ID token sent as
 *   `Authorization: Bearer ...`, or null while sign-in isn't implemented.
 */
class HttpDetectionApi(
    baseUrl: String,
    private val idTokenProvider: suspend () -> String? = { null },
    private val connectTimeoutMillis: Int = 5_000,
    // The server waits up to ~5 s for the VLM, so leave headroom on top of that.
    private val readTimeoutMillis: Int = 10_000,
    private val ioContext: CoroutineContext = Dispatchers.IO,
) : DetectionApi {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun reportDetection(meta: DetectionMeta, jpeg: ByteArray): DetectionResult {
        val boundary = "RoadBuddy-${UUID.randomUUID()}"
        val body = multipartBody(boundary, DetectionJson.encodeMeta(meta), jpeg)
        val response = send(
            method = "POST",
            path = "/detections",
            contentType = "multipart/form-data; boundary=$boundary",
            body = body,
        )
        return parse(response) { DetectionJson.decodeResult(it) }
    }

    override suspend fun undoDetection(detectionId: String): Boolean {
        val encodedId = URLEncoder.encode(detectionId, Charsets.UTF_8.name()).replace("+", "%20")
        val response = send(method = "DELETE", path = "/detections/$encodedId")
        return parse(response) { DetectionJson.decodeUndo(it) }
    }

    private class Response(val status: Int, val body: String)

    private suspend fun send(
        method: String,
        path: String,
        contentType: String? = null,
        body: ByteArray? = null,
    ): Response {
        val token = idTokenProvider()
        return withContext(ioContext) {
            val connection = URI(baseUrl + path).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.setRequestProperty("Accept", "application/json")
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    connection.doOutput = true
                    contentType?.let { connection.setRequestProperty("Content-Type", it) }
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                Response(status, text)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun <T> parse(response: Response, decode: (String) -> T): T {
        if (response.status !in 200..299) {
            val (code, message) = DetectionJson.decodeError(response.body)
                ?: ("http_${response.status}" to response.body.take(200))
            throw ApiException(response.status, code, message)
        }
        return try {
            decode(response.body)
        } catch (e: IllegalArgumentException) {
            throw IOException("Malformed response: ${e.message}", e)
        }
    }

    internal companion object {
        fun multipartBody(boundary: String, metaJson: String, jpeg: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            fun line(text: String = "") = out.write("$text\r\n".toByteArray(Charsets.UTF_8))

            line("--$boundary")
            line("Content-Disposition: form-data; name=\"meta\"")
            line("Content-Type: application/json; charset=utf-8")
            line()
            line(metaJson)

            line("--$boundary")
            line("Content-Disposition: form-data; name=\"image\"; filename=\"frame.jpg\"")
            line("Content-Type: image/jpeg")
            line()
            out.write(jpeg)
            line()

            line("--$boundary--")
            return out.toByteArray()
        }
    }
}
