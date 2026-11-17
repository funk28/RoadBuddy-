package com.roadbuddy.core.network

import com.roadbuddy.core.model.DetectionMeta
import com.roadbuddy.core.model.DetectionResult
import java.io.IOException

/**
 * Android side of API contract calls 1 and 2 (shared/openapi.yaml, tag `Detections`).
 *
 * Implementations throw [IOException] when the server can't be reached and
 * [ApiException] when it answers with an error body.
 */
interface DetectionApi {
    /** `POST /v1/detections` (multipart: `image` JPEG + `meta` JSON). */
    suspend fun reportDetection(meta: DetectionMeta, jpeg: ByteArray): DetectionResult

    /** `DELETE /v1/detections/{id}`. Returns the server's `undone` flag. */
    suspend fun undoDetection(detectionId: String): Boolean
}

/** The server answered with `{"error": {"code": ..., "message": ...}}`. */
class ApiException(
    val httpStatus: Int,
    val code: String,
    message: String,
) : IOException("HTTP $httpStatus $code: $message") {
    /**
     * Whether sending the same request again later could succeed. Server-side
     * failures (5xx, including `503 vlm_unavailable`) and rate limits are
     * retryable; validation and auth errors are not.
     */
    val isRetryable: Boolean
        get() = httpStatus >= 500 || httpStatus == 408 || httpStatus == 429
}
