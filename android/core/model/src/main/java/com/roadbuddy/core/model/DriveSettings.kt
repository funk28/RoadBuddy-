package com.roadbuddy.core.model

data class DriveSettings(
    val audibleAlerts: Boolean = true,
    val detectionEnabled: Boolean = true,
    val suggestReroutes: Boolean = true,
) {
    companion object {
        val DEFAULT = DriveSettings()
    }
}
