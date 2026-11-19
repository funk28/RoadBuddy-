package com.roadbuddy.core.data.navigation

import com.roadbuddy.core.model.LatLng

/**
 * Hardcoded routes for the prototype (issue #38). No Google Routes API call yet.
 *
 * Coordinates are approximate and only meant to look like a short drive near
 * campus; the contract's example hazard location (40.4237, -86.9212) lies on
 * the first leg.
 */
object MockRoutes {
    val CAMPUS_LOOP: Route = routeWithTurns(
        name = "Mock campus route",
        legs = listOf(
            Leg(LatLng(40.4237, -86.9260), "Head east"),
            Leg(LatLng(40.4237, -86.9212), null),
            Leg(LatLng(40.4237, -86.9180), "Turn left"),
            Leg(LatLng(40.4258, -86.9180), null),
            Leg(LatLng(40.4280, -86.9180), "Turn right"),
            Leg(LatLng(40.4280, -86.9110), "Arrive at destination"),
        ),
    )

    val DEFAULT: Route get() = CAMPUS_LOOP

    private data class Leg(val point: LatLng, val instructionHere: String?)

    /** Each non-null instruction is shown as the step that starts at that point. */
    private fun routeWithTurns(name: String, legs: List<Leg>): Route {
        val points = legs.map { it.point }
        var along = 0.0
        val steps = legs.mapIndexedNotNull { index, leg ->
            if (index > 0) along += Geo.distanceM(points[index - 1], points[index])
            leg.instructionHere?.let { RouteStep(it, along) }
        }
        return Route(name, points, steps)
    }
}
