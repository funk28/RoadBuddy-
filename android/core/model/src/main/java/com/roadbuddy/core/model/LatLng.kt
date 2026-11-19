package com.roadbuddy.core.model

/** A WGS-84 coordinate in decimal degrees. */
data class LatLng(
    val lat: Double,
    val lng: Double,
) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lng in -180.0..180.0) { "lng out of range: $lng" }
    }
}
