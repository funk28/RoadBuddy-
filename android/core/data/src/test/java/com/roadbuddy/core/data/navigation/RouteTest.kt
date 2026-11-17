package com.roadbuddy.core.data.navigation

import com.roadbuddy.core.model.LatLng
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    private val route = MockRoutes.CAMPUS_LOOP

    @Test
    fun geoDistanceMatchesKnownScale() {
        // One thousandth of a degree of latitude is about 111 m everywhere.
        val d = Geo.distanceM(LatLng(40.0, -86.0), LatLng(40.001, -86.0))
        assertEquals(111.2, d, 0.5)
        assertEquals(90.0, Geo.bearingDeg(LatLng(40.0, -86.0), LatLng(40.0, -85.99)), 0.1)
        assertEquals(0.0, Geo.bearingDeg(LatLng(40.0, -86.0), LatLng(40.01, -86.0)), 0.1)
    }

    @Test
    fun mockRouteHasReasonableLengthAndEnds() {
        assertTrue("length ${route.lengthM}", route.lengthM in 1_500.0..2_000.0)
        assertEquals(route.points.first(), route.positionAt(0.0).location)
        assertEquals(route.points.last(), route.positionAt(route.lengthM).location)
        // Clamped past either end.
        assertEquals(route.points.first(), route.positionAt(-50.0).location)
        assertEquals(route.points.last(), route.positionAt(route.lengthM + 50).location)
    }

    @Test
    fun positionInterpolatesWithHeading() {
        val start = route.positionAt(10.0)
        assertEquals(90.0, start.headingDeg, 1.0) // heading east
        assertEquals(40.4237, start.location.lat, 1e-6)

        // Just past the "Turn left" corner the car is heading north.
        val leftTurnAt = route.steps.first { it.instruction == "Turn left" }.atDistanceM
        assertEquals(0.0, route.positionAt(leftTurnAt + 20).headingDeg, 1.0)
    }

    @Test
    fun contractExampleLocationIsOnTheRoute() {
        val example = LatLng(40.4237, -86.9212)
        assertTrue(route.points.contains(example))
    }

    @Test
    fun nextStepIsTheUpcomingInstruction() {
        assertEquals("Turn left", route.nextStepAfter(0.0)!!.instruction)
        val leftTurnAt = route.steps.first { it.instruction == "Turn left" }.atDistanceM
        assertEquals("Turn right", route.nextStepAfter(leftTurnAt)!!.instruction)
        assertEquals("Arrive at destination", route.nextStepAfter(route.lengthM - 1)!!.instruction)
        assertNull(route.nextStepAfter(route.lengthM))
    }

    @Test
    fun simulatedGpsDrivesTheWholeRouteInOrder() = runBlocking {
        val gps = SimulatedGps(route, speedMps = 20_000.0, tickMillis = 1)

        val fixes = gps.positions().toList()

        val distances = fixes.map { it.distanceAlongM }.toDoubleArray()
        assertArrayEquals(distances.sortedArray(), distances, 0.0)
        assertEquals(0.0, distances.first(), 0.0)
        assertEquals(route.lengthM, distances.last(), 1e-9)
        assertEquals(route.points.last(), fixes.last().location)
    }

    @Test
    fun simulatedGpsCanResumeMidRoute() = runBlocking {
        val gps = SimulatedGps(route, speedMps = 20_000.0, tickMillis = 1)
        assertEquals(1_000.0, gps.positions(startAtM = 1_000.0).toList().first().distanceAlongM, 0.0)
    }
}
