package app.southbound.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteAlertsTest {

    // North 1 km, then east 1 km.
    private val route = RouteLine(listOf(Hand.at(0.0, 0.0), Hand.at(0.0, 1000.0), Hand.at(1000.0, 1000.0)))
    private val all = AlertKind.entries.toSet()

    @Test
    fun lineLengthAndLocate() {
        assertEquals(2000.0, route.length, 1.0)
        val loc = route.locate(Hand.at(500.0, 1010.0))!!
        assertEquals(1500.0, loc.along, 1.0)
        assertEquals(10.0, loc.offset, 0.5)
    }

    @Test
    fun onlyAlertsCloseToTheLineCount() {
        val near = Hand.mark(Landmark.SPEED_CAMERA, 15.0, 400.0)
        val far = Hand.mark(Landmark.SPEED_CAMERA, 80.0, 600.0)
        val signal = Hand.mark(Landmark.TRAFFIC_SIGNALS, 0.0, 300.0)
        val alpr = Hand.mark(Landmark.ALPR_CAMERA, 1200.0, 1000.0) // past the end, 200 m off
        val alerts = RouteAlerts.onRoute(route, listOf(far, signal, near, alpr))
        assertEquals(listOf(near.id), alerts.map { it.id })
        assertEquals(400.0, alerts.single().alongMeters, 1.0)
        assertEquals(AlertKind.SPEED_CAMERA, alerts.single().kind)
    }

    @Test
    fun spokenOnceAboutThreeHundredMetresAhead() {
        val cam = Hand.mark(Landmark.ALPR_CAMERA, 500.0, 1005.0)
        val alerts = RouteAlerts.onRoute(route, listOf(cam))
        assertTrue(RouteAlerts.due(alerts, 1000.0, emptySet(), all).speak.isEmpty())
        val due = RouteAlerts.due(alerts, 1250.0, emptySet(), all)
        assertEquals(listOf(cam.id), due.speak.map { it.id })
        assertTrue(RouteAlerts.due(alerts, 1300.0, due.covered, all).speak.isEmpty())
        // Already behind the driver: never.
        assertTrue(RouteAlerts.due(alerts, 1600.0, emptySet(), all).speak.isEmpty())
    }

    @Test
    fun aPairOfCamerasIsOneAlert() {
        val first = Hand.mark(Landmark.SPEED_CAMERA, 0.0, 500.0)
        val second = Hand.mark(Landmark.SPEED_CAMERA, 0.0, 620.0)
        val school = Hand.mark(Landmark.SCHOOL_ZONE, 0.0, 550.0)
        val alerts = RouteAlerts.onRoute(route, listOf(second, school, first))
        val due = RouteAlerts.due(alerts, 300.0, emptySet(), all)
        assertEquals(listOf(first.id, school.id), due.speak.map { it.id })
        assertEquals(setOf(first.id, second.id, school.id), due.covered)
    }

    @Test
    fun disabledKindsStayQuiet() {
        val school = Hand.mark(Landmark.SCHOOL_ZONE, 0.0, 550.0)
        val alerts = RouteAlerts.onRoute(route, listOf(school))
        assertTrue(RouteAlerts.due(alerts, 300.0, emptySet(), setOf(AlertKind.SPEED_CAMERA)).speak.isEmpty())
    }

    @Test
    fun countsPlateReadersOnTheRouteOnly() {
        val onRoad = Hand.mark(Landmark.ALPR_CAMERA, 10.0, 300.0)
        val otherRoad = Hand.mark(Landmark.ALPR_CAMERA, 60.0, 500.0)
        val speedCam = Hand.mark(Landmark.SPEED_CAMERA, 0.0, 700.0)
        val secondLeg = Hand.mark(Landmark.ALPR_CAMERA, 400.0, 1030.0)
        val marks = listOf(onRoad, otherRoad, speedCam, secondLeg)
        assertEquals(2, RouteAlerts.count(route, marks, AlertKind.ALPR_CAMERA))
        // A wider corridor picks up the one on the parallel road.
        assertEquals(3, RouteAlerts.count(route, marks, AlertKind.ALPR_CAMERA, withinMeters = 70.0))
        assertEquals(1, RouteAlerts.count(route, marks, AlertKind.SPEED_CAMERA))
        assertEquals(0, RouteAlerts.count(route, emptyList(), AlertKind.ALPR_CAMERA))
    }

    @Test
    fun aPoleFacingBothWaysIsOnePlateReader() {
        val north = Hand.mark(Landmark.ALPR_CAMERA, 5.0, 400.0)
        val south = Hand.mark(Landmark.ALPR_CAMERA, -5.0, 410.0)
        val further = Hand.mark(Landmark.ALPR_CAMERA, 0.0, 480.0)
        // The same row twice, as overlapping route boxes return it.
        val marks = listOf(north, south, north, further)
        assertEquals(2, RouteAlerts.count(route, marks, AlertKind.ALPR_CAMERA))
        assertEquals(3, RouteAlerts.alongRoute(route, marks, AlertKind.ALPR_CAMERA).size)
        assertEquals(3, RouteAlerts.count(route, marks, AlertKind.ALPR_CAMERA, sameSpotMeters = 0.0))
    }

    @Test
    fun boxesCoverTheRouteInPieces() {
        // Pieces end at the line's own points, so this one makes one box per leg.
        val boxes = route.boxes(chunkMeters = 500.0, padMeters = 50.0)
        assertEquals(2, boxes.size)
        for (d in listOf(0.0, 250.0, 999.0)) assertTrue(boxes.any { b -> Hand.at(0.0, d).let { b.contains(it.lat, it.lon) } })
        assertTrue(boxes.any { b -> Hand.at(1000.0, 1040.0).let { b.contains(it.lat, it.lon) } })
    }

    @Test
    fun progressFollowsALoopWithoutJumping() {
        // Out 500 m east and back on the same road (a U-turn).
        val loop = RouteLine(listOf(Hand.at(0.0, 0.0), Hand.at(500.0, 0.0), Hand.at(0.0, 20.0)))
        val progress = RouteProgress(loop)
        assertEquals(100.0, progress.update(Hand.at(100.0, 0.0))!!, 2.0)
        assertEquals(450.0, progress.update(Hand.at(450.0, 0.5))!!, 2.0)
        // Coming back: the window keeps the later leg.
        assertEquals(700.0, progress.update(Hand.at(300.0, 8.0))!!, 5.0)
        assertNull(progress.update(Hand.at(300.0, 200.0)))
    }
}
