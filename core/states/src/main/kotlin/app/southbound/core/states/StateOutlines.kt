package app.southbound.core.states

import app.southbound.core.network.MiniJson
import kotlin.math.cos

/**
 * The 50 states and DC as outlines (about 2 km accurate, a margin out to sea included), and which one a point is
 * in. Static data: nothing here remembers a question, so nothing about where anyone went is kept.
 *
 * Parse once and keep ([StateData.outlines]); [parse] takes the GeoJSON text (a FeatureCollection whose features
 * carry a `postal` property and a Polygon or MultiPolygon geometry).
 */
class StateOutlines private constructor(shapes: List<Shape>) {

    private val shapes: List<Shape> = shapes
    private val byCode: Map<String, Shape> = shapes.associateBy { it.code }

    /** The postal codes, in the file's order. */
    val codes: List<String> get() = shapes.map { it.code }

    /** The state [lat], [lon] is in, or null when it is in none of the outlines (more than about 2 km out to sea). */
    fun stateAt(lat: Double, lon: Double): String? = stateAt(lat, lon, null)

    /**
     * [stateAt], trying [hint] first: a point inside the hinted state is in it, which is the usual case along a
     * drive and costs one outline instead of the whole list.
     */
    fun stateAt(lat: Double, lon: Double, hint: String?): String? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (hint != null) {
            val hinted = byCode[hint]
            if (hinted != null && hinted.contains(lat, lon)) return hint
        }
        for (shape in shapes) if (shape.contains(lat, lon)) return shape.code
        return null
    }

    /**
     * True when the point is outside state [code] and at least [meters] from its border, i.e. that deep into
     * whatever lies beyond it. An unknown code has no border: true.
     */
    fun isClearOf(code: String, lat: Double, lon: Double, meters: Double): Boolean {
        val shape = byCode[code] ?: return true
        return !shape.contains(lat, lon) && !shape.within(lat, lon, meters)
    }

    private class Ring(val lons: DoubleArray, val lats: DoubleArray) {
        val minLon = lons.min()
        val maxLon = lons.max()
        val minLat = lats.min()
        val maxLat = lats.max()

        fun inBox(lat: Double, lon: Double, padLat: Double = 0.0, padLon: Double = 0.0): Boolean =
            lat >= minLat - padLat && lat <= maxLat + padLat && lon >= minLon - padLon && lon <= maxLon + padLon

        /** Ray casting, as `Region.contains` in core/packs does: count the edges a ray running west crosses. */
        fun contains(lat: Double, lon: Double): Boolean {
            if (!inBox(lat, lon)) return false
            var inside = false
            var j = lons.size - 1
            for (i in lons.indices) {
                if ((lats[i] > lat) != (lats[j] > lat) &&
                    lon < (lons[j] - lons[i]) * (lat - lats[i]) / (lats[j] - lats[i]) + lons[i]
                ) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }

        /** True when some edge of the ring passes within [meters] of the point. */
        fun edgeWithin(lat: Double, lon: Double, meters: Double): Boolean {
            var j = lons.size - 1
            for (i in lons.indices) {
                if (Geo.segmentDistanceMeters(lat, lon, lats[i], lons[i], lats[j], lons[j]) <= meters) return true
                j = i
            }
            return false
        }
    }

    private class Polygon(val outer: Ring, val holes: List<Ring>) {
        fun contains(lat: Double, lon: Double): Boolean = outer.contains(lat, lon) && holes.none { it.contains(lat, lon) }

        fun within(lat: Double, lon: Double, meters: Double): Boolean {
            val padLat = meters / Geo.METERS_PER_DEGREE
            val padLon = padLat / maxOf(cos(Math.toRadians(lat)), 0.01)
            if (!outer.inBox(lat, lon, padLat, padLon)) return false
            return outer.edgeWithin(lat, lon, meters) || holes.any { it.edgeWithin(lat, lon, meters) }
        }
    }

    private class Shape(val code: String, val polygons: List<Polygon>) {
        fun contains(lat: Double, lon: Double): Boolean = polygons.any { it.contains(lat, lon) }
        fun within(lat: Double, lon: Double, meters: Double): Boolean = polygons.any { it.within(lat, lon, meters) }
    }

    companion object {
        /** Reads the GeoJSON text. Throws [IllegalArgumentException] for anything that is not the expected shape. */
        fun parse(json: String): StateOutlines {
            val root = try {
                MiniJson.parse(json)
            } catch (e: MiniJson.JsonException) {
                throw IllegalArgumentException("state outlines are not JSON: ${e.message}")
            }
            val features = (root as? Map<*, *>)?.get("features") as? List<*> ?: bad("no features")
            val shapes = features.map { feature ->
                val f = feature as? Map<*, *> ?: bad("a feature is not an object")
                val code = ((f["properties"] as? Map<*, *>)?.get("postal") as? String)?.takeIf { it.isNotEmpty() } ?: bad("a feature has no postal code")
                val geometry = f["geometry"] as? Map<*, *> ?: bad("$code has no geometry")
                val coordinates = geometry["coordinates"] as? List<*> ?: bad("$code has no coordinates")
                val polygons = when (geometry["type"]) {
                    "Polygon" -> listOf(polygon(code, coordinates))
                    "MultiPolygon" -> coordinates.map { polygon(code, it as? List<*> ?: bad("$code has a bad polygon")) }
                    else -> bad("$code is neither a Polygon nor a MultiPolygon")
                }
                Shape(code, polygons)
            }
            if (shapes.isEmpty()) bad("no states")
            if (shapes.map { it.code }.toSet().size != shapes.size) bad("a state appears twice")
            return StateOutlines(shapes)
        }

        private fun polygon(code: String, rings: List<*>): Polygon {
            if (rings.isEmpty()) bad("$code has an empty polygon")
            val parsed = rings.map { ring(code, it as? List<*> ?: bad("$code has a bad ring")) }
            return Polygon(parsed.first(), parsed.drop(1))
        }

        private fun ring(code: String, points: List<*>): Ring {
            if (points.size < 4) bad("$code has a ring of fewer than 4 points")
            val lons = DoubleArray(points.size)
            val lats = DoubleArray(points.size)
            points.forEachIndexed { i, p ->
                val pair = p as? List<*> ?: bad("$code has a bad point")
                val lon = (pair.getOrNull(0) as? Number)?.toDouble() ?: bad("$code has a bad longitude")
                val lat = (pair.getOrNull(1) as? Number)?.toDouble() ?: bad("$code has a bad latitude")
                if (!lon.isFinite() || !lat.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) bad("$code has a point off the globe")
                lons[i] = lon
                lats[i] = lat
            }
            return Ring(lons, lats)
        }

        private fun bad(why: String): Nothing = throw IllegalArgumentException("state outlines: $why")
    }
}
