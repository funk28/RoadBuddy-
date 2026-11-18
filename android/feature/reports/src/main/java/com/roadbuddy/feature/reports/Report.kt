package com.roadbuddy.feature.reports

import org.json.JSONObject

enum class ReportSeverity(val apiValue: String) {
    MINOR("minor"),
    MODERATE("moderate"),
    SEVERE("severe");

    companion object {
        fun fromApiValue(value: String): ReportSeverity =
            entries.firstOrNull { it.apiValue == value }
                ?: throw IllegalArgumentException("Unknown report severity: $value")
    }
}

enum class ReportBadge(val apiValue: String) {
    SUBMITTED("submitted"),
    MATCHED("matched"),
    EXPIRED("expired"),
    PENDING_UPLOAD("pending_upload");

    companion object {
        fun fromApiValue(value: String): ReportBadge =
            entries.firstOrNull { it.apiValue == value }
                ?: throw IllegalArgumentException("Unknown report badge: $value")
    }
}

data class UserReport(
    val id: String,
    val hazardId: String?,
    val type: String,
    val severity: ReportSeverity,
    val locationName: String,
    val capturedAt: String,
    val badge: ReportBadge,
    val imageUrl: String?,
    val tripId: String?,
    val laneCoverage: String?,
    val confirmations: Int,
    val sightingCount: Int,
)

data class ReportsResponse(val reports: List<UserReport>) {
    companion object {
        fun fromJson(json: String): ReportsResponse {
            val reportItems = JSONObject(json).getJSONArray("reports")
            return ReportsResponse(
                reports = List(reportItems.length()) { index ->
                    val item = reportItems.getJSONObject(index)
                    UserReport(
                        id = item.getString("id"),
                        hazardId = item.optionalString("hazard_id"),
                        type = item.getString("type"),
                        severity = ReportSeverity.fromApiValue(item.getString("severity")),
                        locationName = item.getString("location_name"),
                        capturedAt = item.getString("captured_at"),
                        badge = ReportBadge.fromApiValue(item.getString("badge")),
                        imageUrl = item.optionalString("image_url"),
                        tripId = item.optionalString("trip_id"),
                        laneCoverage = item.optionalString("lane_coverage"),
                        confirmations = item.optInt("confirmations"),
                        sightingCount = item.optInt("sighting_count"),
                    )
                },
            )
        }

        private fun JSONObject.optionalString(key: String): String? {
            val value = opt(key)
            return if (value == null || value == JSONObject.NULL) null else value.toString()
        }
    }
}
