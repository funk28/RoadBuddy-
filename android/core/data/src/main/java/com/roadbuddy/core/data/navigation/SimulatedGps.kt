package com.roadbuddy.core.data.navigation

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Stands in for the phone's GPS: emits positions moving along [route] at a
 * constant [speedMps], one fix every [tickMillis], and stops at the end.
 *
 * Replace with a FusedLocationProvider-backed source once real navigation
 * lands; [NavigationSession] only needs a `Flow<RoutePosition>`.
 */
class SimulatedGps(
    private val route: Route,
    private val speedMps: Double = DEFAULT_SPEED_MPS,
    private val tickMillis: Long = 1_000,
) {
    init {
        require(speedMps > 0) { "speedMps must be positive" }
        require(tickMillis > 0) { "tickMillis must be positive" }
    }

    fun positions(startAtM: Double = 0.0): Flow<RoutePosition> = flow {
        var distance = startAtM.coerceIn(0.0, route.lengthM)
        emit(route.positionAt(distance))
        while (distance < route.lengthM) {
            delay(tickMillis)
            distance = (distance + speedMps * tickMillis / 1_000.0).coerceAtMost(route.lengthM)
            emit(route.positionAt(distance))
        }
    }

    companion object {
        /** About 30 mph. */
        const val DEFAULT_SPEED_MPS = 13.4
    }
}
