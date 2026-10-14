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
        assertEquals(setOf(first.id, second.id, school.id), due.covered.map { it.id }.toSet())
    }

    // North 1 km on x = 0, across 10 m, and back south on x = 10: one road driven out and back.
    private val outAndBack = RouteLine(listOf(Hand.at(0.0, 0.0), Hand.at(0.0, 1000.0), Hand.at(10.0, 1000.0), Hand.at(10.0, 0.0)))

    @Test
    fun aCameraPassedTwiceIsAnAlertEachWay() {
        val cam = Hand.mark(Landmark.SPEED_CAMERA, 5.0, 500.0)
        val alerts = RouteAlerts.onRoute(outAndBack, listOf(cam))
        assertEquals(listOf(cam.id, cam.id), alerts.map { it.id })
        assertEquals(500.0, alerts[0].alongMeters, 1.0)
        assertEquals(1510.0, alerts[1].alongMeters, 1.0)
        assertEquals(listOf(1, 0), alerts.map { it.laterPasses })

        // Spoken on the way out, then again on the way back.
        val out = RouteAlerts.due(alerts, 250.0, emptySet(), all)
        assertEquals(listOf(500.0), out.speak.map { Math.round(it.alongMeters).toDouble() })
        assertEquals(setOf(alerts[0].key), out.covered)
        assertTrue(RouteAlerts.due(alerts, 600.0, out.covered, all).speak.isEmpty())
        val back = RouteAlerts.due(alerts, 1300.0, out.covered, all)
        assertEquals(listOf(alerts[1]), back.speak)
        assertTrue(RouteAlerts.due(alerts, 1400.0, out.covered + back.covered, all).speak.isEmpty())
    }

    @Test
    fun aRerouteKeepsEachPassItsKey() {
        val cam = Hand.mark(Landmark.ALPR_CAMERA, 5.0, 500.0)
        val whole = RouteAlerts.onRoute(outAndBack, listOf(cam))
        // A new route from the top of the road back down: only the way back is left, and it is the same pass.
        val rest = RouteLine(listOf(Hand.at(0.0, 1000.0), Hand.at(10.0, 1000.0), Hand.at(10.0, 0.0)))
        val left = RouteAlerts.onRoute(rest, listOf(cam)).single()
        assertEquals(whole.last().key, left.key)
        // So the way out, already announced, doesn't silence it.
        assertEquals(listOf(left), RouteAlerts.due(listOf(left), 300.0, setOf(whole.first().key), all).speak)
    }

    @Test
    fun aCameraAtTheTurnIsOneAlert() {
        // Within 40 m of the road all the way round the turn, so one pass.
        val cam = Hand.mark(Landmark.SPEED_CAMERA, 5.0, 990.0)
        val alerts = RouteAlerts.onRoute(outAndBack, listOf(cam))
        assertEquals(1, alerts.size)
        assertEquals(0, alerts.single().laterPasses)
    }

    @Test
    fun passesFindsEachTimeTheLineComesClose() {
        val beside = Hand.at(5.0, 500.0)
        assertEquals(listOf(500.0, 1510.0), outAndBack.passes(beside, 40.0, 200.0).map { Math.round(it.along).toDouble() })
        // Joined when the gap between the two stretches is shorter than the join distance.
        assertEquals(1, outAndBack.passes(beside, 40.0, 2_000.0).size)
        // Once, at the nearest point, for a line passing by once.
        val once = route.passes(Hand.at(500.0, 1010.0), 40.0, 200.0).single()
        assertEquals(route.locate(Hand.at(500.0, 1010.0))!!.along, once.along, 0.01)
        assertEquals(10.0, once.offset, 0.5)
        assertTrue(route.passes(Hand.at(300.0, 300.0), 40.0, 200.0).isEmpty())
        assertTrue(RouteLine(listOf(Hand.at(0.0, 0.0))).passes(Hand.at(0.0, 0.0), 40.0, 200.0).isEmpty())
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
    fun pointAtWalksAlongTheLine() {
        val mid = route.pointAt(1500.0)!!
        assertEquals(0.0, RouteLine.distanceMeters(mid, Hand.at(500.0, 1000.0)), 1.0)
        assertEquals(route.points.first(), route.pointAt(-5.0))
        assertEquals(route.points.last(), route.pointAt(9999.0))
        assertNull(RouteLine(emptyList()).pointAt(10.0))
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

    // Direction-aware counting. Driving north along x = 0 (the route's first leg is bearing 0).
    private val northbound = RouteLine(listOf(Hand.at(0.0, 0.0), Hand.at(0.0, 1000.0)))

    private fun alprCount(direction: Int?, line: RouteLine = northbound, east: Double = 5.0, north: Double = 500.0) =
        RouteAlerts.count(line, listOf(Hand.mark(Landmark.ALPR_CAMERA, east, north, direction = direction)), AlertKind.ALPR_CAMERA)

    @Test
    fun cameraFacingAlongOrAgainstTheRoadCounts() {
        assertEquals(1, alprCount(0)) // the way we drive
        assertEquals(1, alprCount(180)) // watching the other way (the rear plates)
        assertEquals(1, alprCount(10))
        assertEquals(1, alprCount(350))
    }

    @Test
    fun cameraFacingAcrossTheRoadIsNotCounted() {
        assertEquals(0, alprCount(90))
        assertEquals(0, alprCount(270))
        assertEquals(0, alprCount(60))
        assertEquals(0, alprCount(120))
    }

    @Test
    fun aCameraWithNoDirectionAlwaysCounts() {
        assertEquals(1, alprCount(null))
        // Same for a speed camera, and for a road with no length to point along.
        val speed = Hand.mark(Landmark.SPEED_CAMERA, 5.0, 500.0)
        assertEquals(1, RouteAlerts.count(northbound, listOf(speed), AlertKind.SPEED_CAMERA))
        assertTrue(RouteAlerts.faces(90, null))
    }

    @Test
    fun toleranceIsFortyFiveDegreesEitherSideOfTheRoadAxisAndTheBoundaryCounts() {
        assertEquals(45, RouteAlerts.DIRECTION_TOLERANCE_DEG)
        // Difference between the camera's direction and the bearing of travel, in degrees.
        val expected = mapOf(0 to true, 45 to true, 46 to false, 90 to false, 134 to false, 135 to true, 180 to true, 225 to true, 314 to false, 315 to true)
        for ((diff, counts) in expected) {
            assertEquals("$diff degrees off the bearing", counts, RouteAlerts.faces(diff, 0.0))
            // The rule holds whatever way the road runs.
            assertEquals("$diff degrees off a bearing of 200", counts, RouteAlerts.faces((200 + diff) % 360, 200.0))
            assertEquals("$diff degrees off a bearing of 359", counts, RouteAlerts.faces((359 + diff) % 360, 359.0))
        }
    }

    @Test
    fun theRoutesBearingAtTheCameraIsUsedNotTheStartOfTheRoute() {
        // North 1 km, then east 1 km: the second leg is bearing 90.
        assertEquals(0, alprCount(0, route, east = 500.0, north = 1010.0)) // facing north, on the eastbound leg: across
        assertEquals(1, alprCount(90, route, east = 500.0, north = 1010.0))
        assertEquals(1, alprCount(270, route, east = 500.0, north = 1010.0))
        assertEquals(1, alprCount(0, route, east = 5.0, north = 500.0))
        assertEquals(0, alprCount(90, route, east = 5.0, north = 500.0))
    }

    @Test
    fun avoidanceStillSeesCamerasThatFaceAcrossTheRoad() {
        val across = Hand.mark(Landmark.ALPR_CAMERA, 5.0, 500.0, direction = 90)
        assertEquals(0, RouteAlerts.count(northbound, listOf(across), AlertKind.ALPR_CAMERA))
        assertEquals(listOf(across.id), RouteAlerts.alongRoute(northbound, listOf(across), AlertKind.ALPR_CAMERA).map { it.id })
    }

    @Test
    fun exposureCountsReadersAndSpeedCamerasSeparately() {
        val marks = listOf(
            Hand.mark(Landmark.ALPR_CAMERA, 5.0, 200.0),
            Hand.mark(Landmark.ALPR_CAMERA, 5.0, 400.0, direction = 90), // across
            Hand.mark(Landmark.SPEED_CAMERA, 5.0, 600.0, direction = 180),
            Hand.mark(Landmark.SPEED_CAMERA, -5.0, 800.0),
            Hand.mark(Landmark.SCHOOL_ZONE, 0.0, 700.0),
        )
        val e = RouteAlerts.exposure(northbound, marks)
        assertEquals(RouteExposure(plateReaders = 1, speedCameras = 2), e.copy(marks = emptyList()))
        // The cameras counted come along, so the map can pin them: not the one facing across.
        assertEquals(listOf(marks[0], marks[2], marks[3]).map { it.id }, e.marks.map { it.id })
        assertTrue(!e.isEmpty)
        assertTrue(RouteAlerts.exposure(northbound, emptyList()).isEmpty)
        assertTrue(RouteExposure(0, 0).isEmpty)
        assertTrue(!RouteExposure(0, 1).isEmpty)
    }

    @Test
    fun dotCamerasAreCountedButNeverAnAlert() {
        val marks = listOf(
            Hand.mark(Landmark.DOT_CAMERA, 5.0, 200.0),
            Hand.mark(Landmark.DOT_CAMERA, 8.0, 210.0), // the same pole, counted once
            Hand.mark(Landmark.DOT_CAMERA, 5.0, 600.0),
            Hand.mark(Landmark.DOT_CAMERA, 300.0, 600.0), // another road
            Hand.mark(Landmark.ALPR_CAMERA, 5.0, 400.0),
        )
        val e = RouteAlerts.exposure(northbound, marks)
        assertEquals(RouteExposure(plateReaders = 1, speedCameras = 0, dotCameras = 2), e.copy(marks = emptyList()))
        // One pin per spot: the second mark on the same pole isn't pinned again.
        assertEquals(listOf(marks[4], marks[0], marks[2]).map { it.id }, e.marks.map { it.id })
        assertEquals(3, RouteAlerts.alongRoute(northbound, marks, Landmark.DOT_CAMERA).size)
        assertTrue(RouteAlerts.onRoute(northbound, marks).none { it.kind != AlertKind.ALPR_CAMERA })
        assertTrue(!RouteExposure(0, 0, 1).isEmpty)
    }

    @Test
    fun anOldPackWithoutTheColumnCountsEveryCamera() {
        // A pack without the column reads every direction as NULL, so the count is the one it always was.
        assertEquals("NULL", LandmarkColumns.directionSelect(setOf("id", "category", "name", "lat", "lon")))
        assertEquals("NULL", LandmarkColumns.directionSelect(emptySet()))
        assertEquals("direction", LandmarkColumns.directionSelect(setOf("id", "category", "name", "lat", "lon", "direction")))
        val cams = listOf(
            Hand.mark(Landmark.ALPR_CAMERA, 5.0, 300.0),
            Hand.mark(Landmark.ALPR_CAMERA, 5.0, 600.0),
            Hand.mark(Landmark.ALPR_CAMERA, -5.0, 900.0),
        )
        assertEquals(cams.size, RouteAlerts.count(northbound, cams, AlertKind.ALPR_CAMERA))
        assertEquals(null, cams.first().direction)
    }

    @Test
    fun aStoredDirectionIsReducedToZeroTo359() {
        assertEquals(0, LandmarkColumns.normalDirection(0))
        assertEquals(359, LandmarkColumns.normalDirection(359))
        assertEquals(0, LandmarkColumns.normalDirection(360))
        assertEquals(10, LandmarkColumns.normalDirection(370))
        assertEquals(350, LandmarkColumns.normalDirection(-10))
    }

    @Test
    fun aCameraAtACornerCountsWhenItFacesEitherLeg() {
        // North 1 km, then east 1 km (the corner is at (0, 1000)); each spot is within 10 m of both legs.
        val spots = listOf(5.0 to 995.0, -5.0 to 1005.0, 5.0 to 1005.0)
        for ((east, north) in spots) {
            assertEquals("arriving leg, $east,$north", 1, alprCount(0, route, east, north))
            assertEquals("departing leg, $east,$north", 1, alprCount(90, route, east, north))
            assertEquals("against the departing leg, $east,$north", 1, alprCount(270, route, east, north))
        }
    }

    @Test
    fun aCameraAtACornerFacingNeitherLegIsNotCounted() {
        // North 1 km, then bearing 30 for 1 km: a direction of 105 is 75 degrees off both legs' axes.
        val bend = RouteLine(listOf(Hand.at(0.0, 0.0), Hand.at(0.0, 1000.0), Hand.at(500.0, 1866.0)))
        assertEquals(0, alprCount(105, bend, east = 5.0, north = 995.0))
        // A direction along only the departing leg (60 is 30 off bearing 30, 60 off bearing 0) still counts there...
        assertEquals(1, alprCount(60, bend, east = 5.0, north = 995.0))
        // ...but not away from the corner, where only the first leg is near.
        assertEquals(0, alprCount(60, bend, east = 5.0, north = 500.0))
    }

    @Test
    fun aMissingDirectionOrBearingAlwaysFaces() {
        assertTrue(RouteAlerts.faces(null, 123.0))
        assertTrue(RouteAlerts.faces(null, null))
        assertTrue(RouteAlerts.faces(90, null))
    }

    @Test
    fun bearingsNearListsEveryLegThatClose() {
        assertEquals(listOf(0.0), route.bearingsNear(Hand.at(5.0, 500.0), 40.0).map { Math.round(it).toDouble() })
        assertEquals(listOf(90.0), route.bearingsNear(Hand.at(500.0, 1010.0), 40.0).map { Math.round(it).toDouble() })
        // At the corner both legs, in route order.
        assertEquals(listOf(0.0, 90.0), route.bearingsNear(Hand.at(5.0, 995.0), 40.0).map { Math.round(it).toDouble() })
        assertTrue(route.bearingsNear(Hand.at(300.0, 300.0), 40.0).isEmpty())
        val southwest = RouteLine(listOf(Hand.at(0.0, 0.0), Hand.at(-100.0, -100.0)))
        assertEquals(225.0, southwest.bearingsNear(Hand.at(-50.0, -50.0), 10.0).single(), 0.5)
        // A repeated point has no direction of its own.
        val west = RouteLine(listOf(Hand.at(0.0, 0.0), Hand.at(0.0, 0.0), Hand.at(-100.0, 0.0)))
        assertEquals(270.0, west.bearingsNear(Hand.at(-50.0, 3.0), 10.0).single(), 0.5)
        assertTrue(RouteLine(listOf(Hand.at(0.0, 0.0))).bearingsNear(Hand.at(0.0, 0.0), 10.0).isEmpty())
        assertTrue(RouteLine(emptyList()).bearingsNear(Hand.at(0.0, 0.0), 10.0).isEmpty())
    }
}
