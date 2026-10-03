package app.southbound.core.routing

import app.southbound.core.network.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lanes as Valhalla 3.6's OSRM serializer writes them (tyr
 * route_serializer_osrm.cc): a banner's `sub` holds one "lane" component per
 * lane, left to right, with `active`, `directions` and, on valid lanes,
 * `active_direction`; the step's maneuver intersection repeats them as
 * `lanes[]` with `indications`, `valid`, `active`, `valid_indication`.
 */
class LanesTest {

    /** What Ferrostar 0.57 makes of a step's banners (from_osrm_and_geom), then [Lanes.fromBanner] as [StepLanes] does. */
    @Suppress("UNCHECKED_CAST")
    private fun bannerLanes(step: Map<String, Any?>): List<Lane> {
        val banners = step["bannerInstructions"] as? List<Map<String, Any?>> ?: return emptyList()
        for (banner in banners) {
            val sub = banner["sub"] as? Map<String, Any?> ?: continue
            val components = (sub["components"] as List<Map<String, Any?>>).filter { it["type"] == "lane" }
            if (components.isEmpty()) continue
            return components.map {
                Lanes.fromBanner(
                    active = it["active"] as? Boolean ?: false,
                    directions = it["directions"] as? List<String> ?: emptyList(),
                    activeDirection = it["active_direction"] as String?,
                )
            }
        }
        return emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun intersectionLanes(step: Map<String, Any?>): List<Lane> {
        val first = (step["intersections"] as List<Map<String, Any?>>).first()
        val lanes = first["lanes"] as? List<Map<String, Any?>> ?: return emptyList()
        return lanes.map {
            Lanes.fromIntersection(
                indications = it["indications"] as List<String>,
                valid = it["valid"] as Boolean,
                active = it["active"] as Boolean?,
                validIndication = it["valid_indication"] as String?,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun step(json: String): Map<String, Any?> = MiniJson.parse(json) as Map<String, Any?>

    private fun lane(component: String) = """{"type":"lane","text":"",$component}"""

    private fun banner(vararg lanes: String, distance: Int = 400) = """
        {"distanceAlongGeometry":$distance,
         "primary":{"type":"turn","modifier":"left","text":"Elm Street","components":[{"type":"text","text":"Elm Street"}]},
         "sub":{"text":"","components":[${lanes.joinToString(",") { lane(it) }}]}}
    """

    @Test
    fun `four lanes, the left two for a left turn`() {
        val json = """
            {"distance":812.4,"name":"Main Street",
             "bannerInstructions":[
               {"distanceAlongGeometry":812.4,"primary":{"type":"turn","modifier":"left","text":"Elm Street","components":[]}},
               ${banner(
                   """"active":true,"active_direction":"left","directions":["left"]""",
                   """"active":true,"active_direction":"left","directions":["left","straight"]""",
                   """"active":false,"directions":["straight"]""",
                   """"active":false,"directions":["straight","right"]""",
               )}
             ],
             "intersections":[{"location":[-73.99,40.73],"bearings":[0,180],"entry":[true,false],"in":1,"out":0}]}
        """
        val lanes = bannerLanes(step(json))
        assertEquals(4, lanes.size)
        assertEquals(listOf(LaneDirection.LEFT, LaneDirection.STRAIGHT), lanes[1].directions)
        assertEquals(LaneDirection.LEFT, lanes[1].use)
        assertFalse(lanes[2].valid)
        assertEquals(listOf(0, 1), Lanes.toUse(lanes))
        assertEquals("use the left two lanes", LaneWording.phrase(lanes))
    }

    @Test
    fun `a far right exit`() {
        val json = """
            {"distance":2400.0,
             "bannerInstructions":[${banner(
                 """"active":false,"directions":["straight"]""",
                 """"active":false,"directions":["straight"]""",
                 """"active":false,"directions":["straight"]""",
                 """"active":true,"active_direction":"slight right","directions":["slight right"]""",
             )}],
             "intersections":[{"location":[-73.9,40.8],"bearings":[0],"entry":[true],"out":0}]}
        """
        val lanes = bannerLanes(step(json))
        assertEquals(LaneDirection.SLIGHT_RIGHT, lanes[3].use)
        assertEquals(listOf(3), Lanes.toUse(lanes))
        assertEquals("use the far right lane", LaneWording.phrase(lanes))
    }

    @Test
    fun `the middle lane goes straight`() {
        val json = """
            {"distance":300.0,
             "bannerInstructions":[${banner(
                 """"active":false,"directions":["left"]""",
                 """"active":true,"active_direction":"straight","directions":["straight"]""",
                 """"active":false,"directions":["right"]""",
             )}]}
        """
        val lanes = bannerLanes(step(json))
        assertEquals("use the middle lane", LaneWording.phrase(lanes))
    }

    @Test
    fun `no lane data`() {
        val json = """
            {"distance":120.0,
             "bannerInstructions":[{"distanceAlongGeometry":120.0,"primary":{"type":"turn","modifier":"right","text":"Oak Street","components":[]}}],
             "intersections":[{"location":[-73.9,40.8],"bearings":[0,90],"entry":[true,true],"in":0,"out":1}]}
        """
        assertTrue(bannerLanes(step(json)).isEmpty())
        assertTrue(intersectionLanes(step(json)).isEmpty())
        assertNull(LaneWording.phrase(emptyList()))
        assertFalse(Lanes.worthShowing(emptyList()))
    }

    @Test
    fun `a turn soon after keeps only the nearest lane active`() {
        // Valhalla activates one lane when the next maneuver is close; the
        // other left lane is valid (active_direction set) but not active.
        val json = """
            {"distance":150.0,
             "bannerInstructions":[${banner(
                 """"active":true,"active_direction":"left","directions":["left"]""",
                 """"active":false,"active_direction":"left","directions":["left"]""",
                 """"active":false,"directions":["straight"]""",
             )}]}
        """
        val lanes = bannerLanes(step(json))
        assertTrue(lanes[1].valid)
        assertFalse(lanes[1].active)
        assertEquals(listOf(0), Lanes.toUse(lanes))
        assertEquals("use the far left lane", LaneWording.phrase(lanes))
    }

    @Test
    fun `intersection lanes read the same`() {
        val json = """
            {"distance":500.0,
             "intersections":[{"location":[-73.9,40.8],"bearings":[0,90,180],"entry":[true,true,false],"in":2,"out":1,
               "lanes":[
                 {"active":false,"valid":false,"indications":["straight"]},
                 {"active":true,"valid":true,"valid_indication":"right","indications":["straight","right"]},
                 {"active":true,"valid":true,"valid_indication":"right","indications":["right"]}
               ]}]}
        """
        val lanes = intersectionLanes(step(json))
        assertEquals(listOf(1, 2), Lanes.toUse(lanes))
        assertEquals(LaneDirection.RIGHT, lanes[1].use)
        assertEquals("use the right two lanes", LaneWording.phrase(lanes))
    }

    @Test
    fun `plain OSRM lanes without active use the valid ones`() {
        val lanes = listOf(
            Lanes.fromIntersection(listOf("left"), valid = true, active = null, validIndication = null),
            Lanes.fromIntersection(listOf("straight"), valid = false, active = null, validIndication = null),
            Lanes.fromIntersection(listOf("straight"), valid = false, active = null, validIndication = null),
        )
        assertEquals(listOf(0), Lanes.toUse(lanes))
        assertEquals("use the far left lane", LaneWording.phrase(lanes))
    }

    @Test
    fun `directions parse from Valhalla and OSM spellings`() {
        assertEquals(LaneDirection.UTURN, LaneDirection.parse("uturn"))
        assertEquals(LaneDirection.SHARP_LEFT, LaneDirection.parse("sharp left"))
        assertEquals(LaneDirection.SLIGHT_RIGHT, LaneDirection.parse("slight_right"))
        assertEquals(LaneDirection.STRAIGHT, LaneDirection.parse("through"))
        assertEquals(LaneDirection.RIGHT, LaneDirection.parse(" Right "))
        assertNull(LaneDirection.parse("none"))
        assertNull(LaneDirection.parse(""))
        assertNull(LaneDirection.parse(null))
        // Unknown arrows are dropped, the lane kept.
        val lane = Lanes.fromBanner(active = false, directions = listOf("merge_to_left", "straight"), activeDirection = null)
        assertEquals(listOf(LaneDirection.STRAIGHT), lane.directions)
        assertFalse(lane.valid)
        assertNull(lane.use)
    }
}
