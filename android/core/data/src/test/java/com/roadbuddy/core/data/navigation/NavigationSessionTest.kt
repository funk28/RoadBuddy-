package com.roadbuddy.core.data.navigation

import com.roadbuddy.core.model.DetectionMeta
import com.roadbuddy.core.model.DetectionOutcome
import com.roadbuddy.core.model.DetectionResult
import com.roadbuddy.core.model.ReportedHazard
import com.roadbuddy.core.model.Severity
import com.roadbuddy.core.network.ApiException
import com.roadbuddy.core.network.DetectionApi
import com.roadbuddy.core.network.StubDetectionApi
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSessionTest {
    /** Records calls; each test decides how the "server" answers. */
    private class FakeApi : DetectionApi {
        val reports = mutableListOf<DetectionMeta>()
        val images = mutableListOf<ByteArray>()
        val undos = mutableListOf<String>()
        var onReport: suspend (DetectionMeta) -> DetectionResult = { created(it) }
        var onUndo: suspend (String) -> Boolean = { true }

        override suspend fun reportDetection(meta: DetectionMeta, jpeg: ByteArray): DetectionResult {
            reports += meta
            images += jpeg
            return onReport(meta)
        }

        override suspend fun undoDetection(detectionId: String): Boolean {
            undos += detectionId
            return onUndo(detectionId)
        }

        companion object {
            fun created(meta: DetectionMeta, id: String = "d_${meta.clientDetectionId}") = DetectionResult(
                detectionId = id,
                outcome = DetectionOutcome.CREATED,
                hazard = ReportedHazard("h_1", "pothole", Severity.SEVERE, meta.location, 1),
                undoExpiresAt = null,
            )
        }
    }

    private var now = Instant.parse("2026-11-17T22:00:00Z")
    private val api = FakeApi()
    private val gpsFixes = MutableSharedFlow<RoutePosition>()
    private var nextId = 0

    private fun CoroutineScope.newSession(
        detectionApi: DetectionApi = api,
        positionSource: ((Double) -> kotlinx.coroutines.flow.Flow<RoutePosition>)? = null,
    ): Pair<NavigationSession, CoroutineScope> {
        val scope = CoroutineScope(coroutineContext + Job())
        val session = NavigationSession(
            scope = scope,
            api = detectionApi,
            route = MockRoutes.CAMPUS_LOOP,
            positionSource = positionSource ?: { gpsFixes },
            clock = { now },
            newClientDetectionId = { "c${++nextId}" },
            undoWindow = Duration.ofSeconds(5),
            countdownTickMillis = 5,
            retryDelayMillis = 20,
        )
        return session to scope
    }

    private suspend fun NavigationSession.awaitState(
        predicate: (NavigationUiState) -> Boolean,
    ): NavigationUiState = withTimeout(2_000) { state.first(predicate) }

    /** Starts a drive and moves the car to [distanceM] along the route. */
    private suspend fun NavigationSession.driveTo(distanceM: Double) {
        startDrive()
        awaitState { it.driving }
        // Wait until the drive coroutine is collecting before emitting.
        withTimeout(2_000) { gpsFixes.subscriptionCount.first { it > 0 } }
        gpsFixes.emit(route.positionAt(distanceM))
        awaitState { it.position.distanceAlongM == distanceM }
    }

    @Test
    fun initialStateShowsRouteReady() = runBlocking {
        val (session, scope) = newSession()
        val state = session.state.value

        assertFalse(state.driving)
        assertEquals(MockRoutes.CAMPUS_LOOP.points.first(), state.position.location)
        assertEquals("Turn left", state.turn!!.instruction)
        assertEquals(MockRoutes.CAMPUS_LOOP.lengthM, state.trip.remainingDistanceM, 1e-6)
        assertNull(state.alert)
        scope.cancel()
    }

    @Test
    fun drivingTheSimulatedRouteUpdatesTurnCardTripBarAndArrives() = runBlocking {
        val (session, scope) = newSession(
            positionSource = SimulatedGps(MockRoutes.CAMPUS_LOOP, speedMps = 40_000.0, tickMillis = 1)::positions,
        )

        session.startDrive()
        val midway = session.awaitState { it.progress in 0.4f..0.9f }
        assertTrue(midway.driving)
        assertTrue(midway.trip.remainingDistanceM < MockRoutes.CAMPUS_LOOP.lengthM)
        assertNotNull(midway.turn)

        val done = session.awaitState { it.arrived }
        assertFalse(done.driving)
        assertEquals(1f, done.progress)
        assertNull(done.turn)
        assertEquals(0.0, done.trip.remainingDistanceM, 1e-6)
        scope.cancel()
    }

    @Test
    fun endDriveStopsAndStartResumesWhereItStopped() = runBlocking {
        val startedFrom = mutableListOf<Double>()
        val (session, scope) = newSession(positionSource = { start -> startedFrom += start; gpsFixes })
        session.driveTo(450.0)

        session.endDrive()
        assertFalse(session.state.value.driving)
        withTimeout(2_000) { gpsFixes.subscriptionCount.first { it == 0 } }
        assertEquals(450.0, session.state.value.position.distanceAlongM, 0.0)

        session.startDrive()
        withTimeout(2_000) { gpsFixes.subscriptionCount.first { it > 0 } }
        assertEquals(listOf(0.0, 450.0), startedFrom)
        scope.cancel()
    }

    @Test
    fun detectionIsIgnoredWhenNotDriving() = runBlocking {
        val (session, scope) = newSession()

        assertNull(session.reportPothole())
        assertNull(session.state.value.alert)
        assertTrue(api.reports.isEmpty())
        scope.cancel()
    }

    @Test
    fun detectionPostsCurrentPositionAndShowsAlert() = runBlocking {
        val (session, scope) = newSession()
        session.driveTo(500.0)

        val id = session.reportPothole(localConfidence = 0.9)

        val reported = session.awaitState { it.alert?.phase == AlertPhase.REPORTED }
        assertEquals(id, reported.alert!!.clientDetectionId)
        assertEquals(Severity.SEVERE, reported.alert!!.severity)
        assertEquals(5, reported.alert!!.undoSecondsLeft)

        val meta = api.reports.single()
        assertEquals("c1", meta.clientDetectionId)
        assertEquals(MockRoutes.CAMPUS_LOOP.positionAt(500.0).location, meta.location)
        assertEquals(90.0, meta.headingDeg!!, 1.0)
        assertEquals(0.9, meta.localConfidence, 0.0)
        assertEquals(now, meta.capturedAt)
        scope.cancel()
    }

    @Test
    fun uploadedFrameIsAPotholeImageTaggedWithTheSameGpsAsMeta() = runBlocking {
        val (session, scope) = newSession()
        session.driveTo(700.0)

        session.reportPothole()
        session.awaitState { it.alert?.phase == AlertPhase.REPORTED }

        val meta = api.reports.single()
        val exif = GpsExif.read(api.images.single())!!
        assertEquals(meta.location.lat, exif.location.lat, 1e-7)
        assertEquals(meta.location.lng, exif.location.lng, 1e-7)
        assertEquals(meta.capturedAt, exif.capturedAt)
        assertEquals(meta.headingDeg!!, exif.headingDeg!!, 0.01)
        assertTrue(api.images.single().size > 20_000)
        scope.cancel()
    }

    @Test
    fun undoInsideWindowDeletesOnServer() = runBlocking {
        val (session, scope) = newSession()
        session.driveTo(500.0)
        session.reportPothole()
        session.awaitState { it.alert?.phase == AlertPhase.REPORTED }

        now = now.plusSeconds(3)
        session.undo()

        val after = session.awaitState { it.message != null }
        assertNull(after.alert)
        assertEquals(NavigationMessage.HAZARD_REMOVED, after.message)
        assertEquals(listOf("d_c1"), api.undos)

        session.messageShown()
        assertNull(session.state.value.message)
        scope.cancel()
    }

    @Test
    fun undoWhileReportIsInFlightDeletesOnceServerAnswers() = runBlocking {
        val serverAnswer = CompletableDeferred<Unit>()
        api.onReport = { meta -> serverAnswer.await(); FakeApi.created(meta) }
        val (session, scope) = newSession()
        session.driveTo(500.0)
        session.reportPothole()
        session.awaitState { it.alert?.phase == AlertPhase.SENDING }

        session.undo()
        assertEquals(AlertPhase.UNDOING, session.state.value.alert!!.phase)
        assertTrue(api.undos.isEmpty())

        serverAnswer.complete(Unit)
        val after = session.awaitState { it.message == NavigationMessage.HAZARD_REMOVED }
        assertNull(after.alert)
        assertEquals(listOf("d_c1"), api.undos)
        scope.cancel()
    }

    @Test
    fun alertCountsDownAndDisappearsWhenWindowCloses() = runBlocking {
        val (session, scope) = newSession()
        session.driveTo(500.0)
        session.reportPothole()
        session.awaitState { it.alert?.phase == AlertPhase.REPORTED }

        now = now.plusMillis(2_500)
        assertEquals(3, session.awaitState { it.alert?.undoSecondsLeft == 3 }.alert!!.undoSecondsLeft)

        now = now.plusMillis(2_600)
        session.awaitState { it.alert == null }

        // Too late now: Undo does nothing and the report stays.
        session.undo()
        assertTrue(api.undos.isEmpty())
        assertNull(session.state.value.message)
        scope.cancel()
    }

    @Test
    fun failedUndoTellsDriverToUseMyReports() = runBlocking {
        api.onUndo = { throw IOException("no signal") }
        val (session, scope) = newSession()
        session.driveTo(500.0)
        session.reportPothole()
        session.awaitState { it.alert?.phase == AlertPhase.REPORTED }

        session.undo()

        val after = session.awaitState { it.message != null }
        assertEquals(NavigationMessage.UNDO_FAILED, after.message)
        assertNull(after.alert)
        scope.cancel()
    }

    @Test
    fun offlineReportIsQueuedAndRetriedWithSameClientId() = runBlocking {
        var online = false
        api.onReport = { meta -> if (online) FakeApi.created(meta) else throw IOException("offline") }
        val (session, scope) = newSession()
        session.driveTo(500.0)

        session.reportPothole()
        val queued = session.awaitState { it.pendingUploads == 1 }
        assertEquals(AlertPhase.QUEUED, queued.alert!!.phase)

        online = true
        val sent = session.awaitState { it.pendingUploads == 0 && it.alert?.phase == AlertPhase.REPORTED }
        assertNotNull(sent.alert)
        assertTrue(api.reports.size >= 2)
        assertEquals(setOf("c1"), api.reports.map { it.clientDetectionId }.toSet())
        scope.cancel()
    }

    @Test
    fun vlmTimeoutIsRetriedLikeBeingOffline() = runBlocking {
        var failures = 2
        api.onReport = { meta ->
            if (failures-- > 0) throw ApiException(503, "vlm_unavailable", "slow") else FakeApi.created(meta)
        }
        val (session, scope) = newSession()
        session.driveTo(500.0)

        session.reportPothole()

        session.awaitState { it.pendingUploads == 0 && it.alert?.phase == AlertPhase.REPORTED }
        assertEquals(3, api.reports.size)
        scope.cancel()
    }

    @Test
    fun undoWhileQueuedDropsItWithoutCallingServer() = runBlocking {
        api.onReport = { throw IOException("offline") }
        val (session, scope) = newSession()
        session.driveTo(500.0)
        session.reportPothole()
        session.awaitState { it.pendingUploads == 1 }
        val callsBeforeUndo = api.reports.size

        session.undo()

        val after = session.awaitState { it.message != null }
        assertEquals(NavigationMessage.HAZARD_REMOVED, after.message)
        assertEquals(0, after.pendingUploads)
        assertTrue(api.undos.isEmpty())
        // Nothing left to retry.
        kotlinx.coroutines.delay(100)
        assertEquals(callsBeforeUndo, api.reports.size)
        scope.cancel()
    }

    @Test
    fun rejectedDetectionClosesAlert() = runBlocking {
        api.onReport = { DetectionResult(null, DetectionOutcome.REJECTED, null, null) }
        val (session, scope) = newSession()
        session.driveTo(500.0)

        session.reportPothole()

        val after = session.awaitState { it.message != null }
        assertEquals(NavigationMessage.NOT_A_HAZARD, after.message)
        assertNull(after.alert)
        scope.cancel()
    }

    @Test
    fun badRequestIsNotQueued() = runBlocking {
        api.onReport = { throw ApiException(400, "missing_field", "nope") }
        val (session, scope) = newSession()
        session.driveTo(500.0)

        session.reportPothole()

        val after = session.awaitState { it.message != null }
        assertEquals(NavigationMessage.REPORT_FAILED, after.message)
        assertEquals(0, after.pendingUploads)
        assertEquals(1, api.reports.size)
        scope.cancel()
    }

    @Test
    fun newDetectionReplacesTheAlert() = runBlocking {
        val (session, scope) = newSession()
        session.driveTo(500.0)
        session.reportPothole()
        session.awaitState { it.alert?.clientDetectionId == "c1" && it.alert?.phase == AlertPhase.REPORTED }

        session.reportPothole()
        session.awaitState { it.alert?.clientDetectionId == "c2" && it.alert?.phase == AlertPhase.REPORTED }

        session.undo()
        session.awaitState { it.message == NavigationMessage.HAZARD_REMOVED }
        assertEquals(listOf("d_c2"), api.undos)
        scope.cancel()
    }

    @Test
    fun endToEndAgainstStubServer() = runBlocking {
        val stub = StubDetectionApi(clock = { now }, latencyMillis = 1)
        val (session, scope) = newSession(detectionApi = stub)
        session.driveTo(500.0)

        session.reportPothole()
        session.awaitState { it.alert?.phase == AlertPhase.REPORTED }
        assertEquals(1, stub.activeDetectionCount)

        session.undo()
        session.awaitState { it.message == NavigationMessage.HAZARD_REMOVED }
        assertEquals(0, stub.activeDetectionCount)
        scope.cancel()
    }
}
