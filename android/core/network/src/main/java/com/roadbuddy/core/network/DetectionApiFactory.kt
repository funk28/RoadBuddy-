package com.roadbuddy.core.network

/**
 * Picks the detection client for a build.
 *
 * @param baseUrl server root including `/v1`, e.g. `http://127.0.0.1:5056/v1`. Blank means
 *   no server: the in-memory [StubDetectionApi] answers instead, so the app can be demoed
 *   offline.
 * @param devToken sent as `Authorization: Bearer <devToken>` until Google sign-in exists.
 *   The backend accepts `dev:<name>` only when it runs with `DEV_AUTH=true`. Blank sends no
 *   token.
 */
fun createDetectionApi(baseUrl: String, devToken: String): DetectionApi =
    if (baseUrl.isBlank()) {
        StubDetectionApi()
    } else {
        HttpDetectionApi(baseUrl, idTokenProvider = { devToken.ifBlank { null } })
    }
