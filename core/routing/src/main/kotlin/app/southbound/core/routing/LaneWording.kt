package app.southbound.core.routing

/**
 * The spoken lane advice for a maneuver, e.g. "use the left two lanes".
 * Pure and unit tested. Lanes are counted from the left; the lanes to use
 * are [Lanes.toUse] (the active ones, else the valid ones).
 *
 * - All lanes usable, none, or a single lane road: nothing.
 * - At an edge: "use the left lane" (two lanes), "use the far left lane"
 *   (three or more), "use the left two lanes", "use any of the left three
 *   lanes"; four or more usable lanes at an edge leave few to avoid, so nothing.
 * - Inside: one lane in the exact middle is "use the middle lane", else "use
 *   the second lane from the right" (counted from the nearer edge, up to the
 *   third); two or three lanes centred are "use the middle two lanes"; others nothing.
 * - Usable lanes in more than one group: the group at one edge is named
 *   (always a usable choice); groups at both edges, or at neither, say nothing.
 */
object LaneWording {

    fun phrase(lanes: List<Lane>): String? {
        val n = lanes.size
        val use = Lanes.toUse(lanes)
        if (n < 2 || use.isEmpty() || use.size >= n) return null
        val runs = runs(use)
        if (runs.size == 1) return group(runs.single(), n)
        val left = runs.first().takeIf { it.first == 0 }
        val right = runs.last().takeIf { it.last == n - 1 }
        return when {
            left != null && right == null -> atEdge(left.count(), n, LEFT)
            right != null && left == null -> atEdge(right.count(), n, RIGHT)
            else -> null
        }
    }

    private fun group(run: IntRange, n: Int): String? {
        val k = run.count()
        return when {
            run.first == 0 -> atEdge(k, n, LEFT)
            run.last == n - 1 -> atEdge(k, n, RIGHT)
            k == 1 -> single(run.first, n)
            // Centred: as many lanes left of the group as right of it.
            run.first == n - 1 - run.last -> when (k) {
                2 -> "use the middle two lanes"
                3 -> "use the middle three lanes"
                else -> null
            }
            else -> null
        }
    }

    private fun atEdge(k: Int, n: Int, side: String): String? = when (k) {
        1 -> if (n >= 3) "use the far $side lane" else "use the $side lane"
        2 -> "use the $side two lanes"
        3 -> "use any of the $side three lanes"
        else -> null
    }

    /** One lane, not at an edge. */
    private fun single(index: Int, n: Int): String? {
        if (n % 2 == 1 && index == n / 2) return "use the middle lane"
        val fromLeft = index + 1
        val fromRight = n - index
        val (place, side) = if (fromLeft < fromRight) fromLeft to LEFT else fromRight to RIGHT
        val ordinal = ORDINALS.getOrNull(place) ?: return null
        return "use the $ordinal lane from the $side"
    }

    /** Sorted indices into runs of neighbours: [0, 1, 3] -> [0..1, 3..3]. */
    private fun runs(sorted: List<Int>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = sorted.first()
        var prev = start
        for (i in sorted.drop(1)) {
            if (i != prev + 1) {
                out += start..prev
                start = i
            }
            prev = i
        }
        out += start..prev
        return out
    }

    private const val LEFT = "left"
    private const val RIGHT = "right"
    private val ORDINALS = listOf("", "first", "second", "third")
}
