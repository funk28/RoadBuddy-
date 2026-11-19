package com.roadbuddy.feature.hazards

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpHazardsRepository(
    baseUrl: String,
    private val authToken: String = "",
) : HazardsRepository {
    private val apiBaseUrl = baseUrl.trimEnd('/')

    override suspend fun getHazards(filters: HazardFilters): List<Hazard> =
        withContext(Dispatchers.IO) {
            val query = mutableListOf(
                "lat=40.4237",
                "lng=-86.9212",
                "radius_m=2000",
            )
            filters.severity?.let { query += "severity=${encode(it.apiValue)}" }
            if (filters.mine) query += "mine=true"

            parseHazardsResponse(
                request(
                    path = "/hazards?${query.joinToString("&")}",
                    method = "GET",
                ),
            )
        }

    override suspend fun updateHazard(id: String, change: HazardChange): HazardUpdate =
        withContext(Dispatchers.IO) {
            val payload = when (change) {
                is HazardChange.SetSeverity -> JSONObject().put("severity", change.severity.apiValue)
                HazardChange.MarkRemoved -> JSONObject().put("status", "removed")
            }
            parseHazardUpdate(
                request(
                    path = "/hazards/${encode(id)}",
                    method = "PATCH",
                    body = payload.toString(),
                ),
            )
        }

    private fun request(path: String, method: String, body: String? = null): String {
        val connection = URL("$apiBaseUrl$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            if (authToken.isNotBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $authToken")
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }

            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: throw IOException("Hazards API returned HTTP $statusCode")
            val response = responseStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (statusCode !in 200..299) {
                throw IOException("Hazards API returned HTTP $statusCode: $response")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
