package app.southbound.core.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteOptionsTest {

    @Test
    fun `exclude polygons go into the request body as lon-lat rings`() {
        val body = """{"format":"osrm","costing":"auto","locations":[]}"""
        val ring = listOf(-74.0 to 40.7, -73.99 to 40.7, -73.99 to 40.71, -74.0 to 40.71, -74.0 to 40.7)
        assertEquals(
            """{"exclude_polygons":[[[-74.000000,40.700000],[-73.990000,40.700000],[-73.990000,40.710000],[-74.000000,40.710000],[-74.000000,40.700000]]],""" +
                """"format":"osrm","costing":"auto","locations":[]}""",
            RouteOptions.withExcludePolygons(body, listOf(ring)),
        )
    }

    @Test
    fun `nothing to avoid leaves the body alone`() {
        val body = """{"costing":"auto"}"""
        assertEquals(body, RouteOptions.withExcludePolygons(body, emptyList()))
        assertEquals(body, RouteOptions.withExcludePolygons(body, listOf(listOf(-74.0 to 40.7, -73.9 to 40.7))))
    }

    @Test
    fun `options ask for alternatives in miles`() {
        assertEquals("""{"alternates":2,"units":"miles","language":"en-US"}""", RouteOptions.json(TravelMode.DRIVE))
    }
}
