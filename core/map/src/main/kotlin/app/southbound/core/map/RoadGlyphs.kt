package app.southbound.core.map

/**
 * The glyph of each Road category on a 24 by 24 grid, as plain shapes: white on the badge's colour, with "holes"
 * painted in the badge's colour. No drawable resources or asset files; [RoadIcons] draws them with Canvas, and the
 * shapes can be checked on the JVM (every key has one, all inside the grid).
 */
internal object RoadGlyphs {

    const val GRID = 24f

    sealed interface Shape {
        /** True when the shape is painted in the badge's colour (it cuts out of the white shapes under it). */
        val hole: Boolean
    }

    /** A closed polygon; [xy] is x0, y0, x1, y1, ... */
    data class Polygon(val xy: List<Float>, override val hole: Boolean = false) : Shape

    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float = 0f, override val hole: Boolean = false) : Shape

    data class Circle(val cx: Float, val cy: Float, val r: Float, override val hole: Boolean = false) : Shape

    /** An open line through the points [xy], [width] thick, with round ends. */
    data class Line(val xy: List<Float>, val width: Float, override val hole: Boolean = false) : Shape

    /** The glyph for a pack category code, or a plain dot for a code this app doesn't know. */
    fun of(category: String): List<Shape> = GLYPHS[category] ?: listOf(Circle(12f, 12f, 3f))

    val keys: Set<String> get() = GLYPHS.keys

    private val GLYPHS: Map<String, List<Shape>> = mapOf(
        // A tent with its door.
        "camp" to listOf(
            Polygon(listOf(12f, 5.5f, 20f, 18.5f, 4f, 18.5f)),
            Polygon(listOf(12f, 11.5f, 14.6f, 18.5f, 9.4f, 18.5f), hole = true),
        ),
        // A camper: body, a window, two wheels.
        "rv" to listOf(
            Rect(3.5f, 6.5f, 20.5f, 16.5f, 2.5f),
            Rect(14f, 8.5f, 18.5f, 12.5f, 1f, hole = true),
            Rect(5.5f, 8.5f, 11f, 12.5f, 1f, hole = true),
            Circle(8f, 17.5f, 2.6f, hole = true),
            Circle(16f, 17.5f, 2.6f, hole = true),
            Circle(8f, 17.5f, 1.7f),
            Circle(16f, 17.5f, 1.7f),
        ),
        // A bench: back, seat, legs.
        "rest" to listOf(
            Rect(5f, 7f, 19f, 9.5f, 0.8f),
            Rect(4f, 11f, 20f, 13.5f, 0.8f),
            Rect(6f, 13.5f, 8.2f, 18f),
            Rect(15.8f, 13.5f, 18f, 18f),
        ),
        // An arrow down into a pit.
        "dump" to listOf(
            Polygon(listOf(10f, 4.5f, 14f, 4.5f, 14f, 11f, 17.5f, 11f, 12f, 17f, 6.5f, 11f, 10f, 11f)),
            Rect(5f, 19f, 19f, 21f, 0.8f),
        ),
        // A drop.
        "water" to listOf(
            Polygon(listOf(12f, 3.5f, 17.2f, 12f, 6.8f, 12f)),
            Circle(12f, 14.6f, 5.4f),
        ),
        // Two figures.
        "toilet" to listOf(
            Circle(7.5f, 6f, 1.9f),
            Circle(16.5f, 6f, 1.9f),
            Rect(5.5f, 9f, 9.5f, 17f, 1.2f),
            Rect(14.5f, 9f, 18.5f, 17f, 1.2f),
            Rect(5.8f, 17f, 7.2f, 21f),
            Rect(7.8f, 17f, 9.2f, 21f),
            Polygon(listOf(16.5f, 17f, 14.3f, 21f, 18.7f, 21f)),
            Line(listOf(12f, 5f, 12f, 21f), 0.9f),
        ),
        // A shower head on a pipe, with drops.
        "shower" to listOf(
            Line(listOf(5.5f, 20f, 5.5f, 6.5f, 13f, 6.5f), 2f),
            Polygon(listOf(11f, 8.5f, 20f, 8.5f, 18.5f, 11.5f, 12.5f, 11.5f)),
            Circle(12.5f, 15f, 1.1f),
            Circle(16f, 14.5f, 1.1f),
            Circle(19f, 15f, 1.1f),
            Circle(14.2f, 18.8f, 1.1f),
            Circle(17.6f, 18.8f, 1.1f),
        ),
        // A fuel pump: body, window, hose.
        "fuel" to listOf(
            Rect(5.5f, 4.5f, 14.5f, 19f, 1.2f),
            Rect(7.5f, 6.5f, 12.5f, 10.5f, 0.6f, hole = true),
            Rect(4.5f, 19f, 15.5f, 21f, 0.6f),
            Line(listOf(14.5f, 9f, 17.5f, 9f, 18.5f, 11f, 18.5f, 16f), 1.7f),
        ),
        // A lorry: box, cab, wheels.
        "truck" to listOf(
            Rect(2.5f, 6.5f, 13.5f, 16.5f, 1f),
            Polygon(listOf(14.5f, 9.5f, 18f, 9.5f, 21.5f, 13f, 21.5f, 16.5f, 14.5f, 16.5f)),
            Circle(7f, 17.5f, 2.7f, hole = true),
            Circle(17f, 17.5f, 2.7f, hole = true),
            Circle(7f, 17.5f, 1.8f),
            Circle(17f, 17.5f, 1.8f),
        ),
        // A washing machine: door and knobs.
        "laundry" to listOf(
            Rect(5.5f, 3.5f, 18.5f, 20.5f, 2f),
            Circle(12f, 13.5f, 4.6f, hole = true),
            Circle(12f, 13.5f, 2.6f),
            Circle(8.4f, 6.9f, 0.9f, hole = true),
            Circle(11.2f, 6.9f, 0.9f, hole = true),
        ),
        // An open book.
        "library" to listOf(
            Polygon(listOf(3.5f, 6.5f, 11.2f, 8.5f, 11.2f, 19.5f, 3.5f, 17.5f)),
            Polygon(listOf(20.5f, 6.5f, 12.8f, 8.5f, 12.8f, 19.5f, 20.5f, 17.5f)),
        ),
        // A capsule.
        "pharmacy" to listOf(
            Line(listOf(8f, 16f, 16f, 8f), 6f),
            Line(listOf(10.6f, 10.6f, 13.4f, 13.4f), 1.1f, hole = true),
        ),
        // An H.
        "hospital" to listOf(
            Rect(6f, 4.5f, 9.6f, 19.5f, 0.6f),
            Rect(14.4f, 4.5f, 18f, 19.5f, 0.6f),
            Rect(8f, 10.2f, 16f, 13.8f),
        ),
        // A cross.
        "clinic" to listOf(
            Rect(9.8f, 4.5f, 14.2f, 19.5f, 0.8f),
            Rect(4.5f, 9.8f, 19.5f, 14.2f, 0.8f),
        ),
        // A basket on wheels.
        "grocery" to listOf(
            Line(listOf(3.5f, 5.5f, 6.5f, 5.5f, 8.5f, 14.5f, 18f, 14.5f), 1.7f),
            Polygon(listOf(7.2f, 8f, 20.5f, 8f, 18.3f, 13.5f, 8.7f, 13.5f)),
            Circle(10f, 18f, 1.7f),
            Circle(17f, 18f, 1.7f),
        ),
        // A tree: two layers of branches and a trunk.
        "park" to listOf(
            Polygon(listOf(12f, 3.5f, 17.5f, 11f, 6.5f, 11f)),
            Polygon(listOf(12f, 7.5f, 19f, 16f, 5f, 16f)),
            Rect(10.8f, 15.5f, 13.2f, 21f),
        ),
    )
}
