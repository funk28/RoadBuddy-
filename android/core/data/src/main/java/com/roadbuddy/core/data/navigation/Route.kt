package com.roadbuddy.core.data.navigation

import com.roadbuddy.core.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A turn-by-turn instruction that applies from [atDistanceM] along the route. */
data class RouteStep(
    val instruction: String,
    val atDistanceM: Double,
)

/** Where the vehicle is on a [Route]. */
data class RoutePosition(
    val location: LatLng,
    val headingDeg: Double,
    val distanceAlongM: Double,
)

/**
 * A driving route: a polyline plus its turn instructions.
 *
 * For the prototype this comes from [MockRoutes]; later it will be built from a
 * Google Routes API response.
 */
class Route(
    val name: String,
    val points: List<LatLng>,
    steps: List<RouteStep>,
) {
    init {
        require(points.size >= 2) { "A route needs at least two points" }
    }

    /** Distance from the start to each point, in meters. */
    private val cumulativeM: DoubleArray = DoubleArray(points.size).also { cum ->
        for (i in 1 until points.size) cum[i] = cum[i - 1] + Geo.distanceM(points[i - 1], points[i])
    }

    val lengthM: Double get() = cumulativeM.last()

    val steps: List<RouteStep> = steps.sortedBy { it.atDistanceM }

    /** The point [distanceM] meters along the route, clamped to its ends. */
    fun positionAt(distanceM: Double): RoutePosition {
        val d = distanceM.coerceIn(0.0, lengthM)
        var i = cumulativeM.indexOfLast { it <= d }.coerceAtMost(points.size - 2)
        // Skip zero-length segments so the heading stays meaningful.
        while (i < points.size - 2 && cumulativeM[i + 1] - cumulativeM[i] == 0.0) i++
        val start = points[i]
        val end = points[i + 1]
        val segmentM = cumulativeM[i + 1] - cumulativeM[i]
        val t = if (segmentM == 0.0) 0.0 else (d - cumulativeM[i]) / segmentM
        return RoutePosition(
            location = Geo.interpolate(start, end, t),
            headingDeg = Geo.bearingDeg(start, end),
            distanceAlongM = d,
        )
    }

    /** The next instruction after [distanceM], or null once only arrival is left. */
    fun nextStepAfter(distanceM: Double): RouteStep? = steps.firstOrNull { it.atDistanceM > distanceM }

    /** Fraction of the route (0..1) that [distanceM] represents. */
    fun progressAt(distanceM: Double): Float = (distanceM / lengthM).coerceIn(0.0, 1.0).toFloat()
}

/** Small spherical-earth helpers; accurate to well under 1% at city scale. */
internal object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial compass bearing from [a] to [b]: 0 = north, 90 = east. */
    fun bearingDeg(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Linear interpolation; fine for the short segments of a city route. */
    fun interpolate(a: LatLng, b: LatLng, t: Double): LatLng =
        LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
}
