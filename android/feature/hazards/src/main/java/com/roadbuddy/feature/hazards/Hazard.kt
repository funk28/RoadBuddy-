package com.roadbuddy.feature.hazards

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

enum class HazardSeverity(val apiValue: String, val label: String) {
    MINOR("minor", "Minor"),
    MODERATE("moderate", "Moderate"),
    SEVERE("severe", "Severe");

    companion object {
        fun fromApiValue(value: String): HazardSeverity =
            entries.firstOrNull { it.apiValue == value }
                ?: throw IllegalArgumentException("Unknown hazard severity: $value")
    }
}

data class Hazard(
    val id: String,
    val type: String,
    val severity: HazardSeverity,
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Double,
    val sightingCount: Int,
    val reportedByMe: Boolean,
    val locationName: String?,
)

data class HazardFilters(
    val mine: Boolean = false,
    val severity: HazardSeverity? = null,
)

sealed interface HazardChange {
    data class SetSeverity(val severity: HazardSeverity) : HazardChange
    data object MarkRemoved : HazardChange
}

data class HazardUpdate(
    val id: String,
    val severity: HazardSeverity,
    val isRemoved: Boolean,
)

interface HazardsRepository {
    suspend fun getHazards(filters: HazardFilters): List<Hazard>

    suspend fun updateHazard(id: String, change: HazardChange): HazardUpdate
}

fun createHazardsRepository(baseUrl: String, authToken: String = ""): HazardsRepository =
    if (baseUrl.isBlank()) StubHazardsRepository() else HttpHazardsRepository(baseUrl, authToken)

internal fun parseHazardsResponse(json: String): List<Hazard> =
    try {
        val hazards = JSONObject(json).getJSONArray("hazards")
        List(hazards.length()) { index -> hazards.getJSONObject(index).toHazard() }
    } catch (error: JSONException) {
        throw IOException("Invalid hazards response", error)
    } catch (error: IllegalArgumentException) {
        throw IOException("Invalid hazards response", error)
    }

internal fun parseHazardUpdate(json: String): HazardUpdate =
    try {
        val response = JSONObject(json)
        val status = response.getString("status")
        if (status != "live" && status != "removed" && status != "expired") {
            throw IllegalArgumentException("Unknown hazard status: $status")
        }
        HazardUpdate(
            id = response.getString("id"),
            severity = HazardSeverity.fromApiValue(response.getString("severity")),
            isRemoved = status == "removed",
        )
    } catch (error: JSONException) {
        throw IOException("Invalid hazard update response", error)
    } catch (error: IllegalArgumentException) {
        throw IOException("Invalid hazard update response", error)
    }

private fun JSONObject.toHazard(): Hazard =
    Hazard(
        id = getString("id"),
        type = getString("type"),
        severity = HazardSeverity.fromApiValue(getString("severity")),
        latitude = getDouble("lat"),
        longitude = getDouble("lng"),
        distanceMeters = getDouble("distance_m"),
        sightingCount = getInt("sighting_count"),
        reportedByMe = getBoolean("reported_by_me"),
        locationName = optString("location_name").takeIf { it.isNotBlank() && it != "null" },
    )
