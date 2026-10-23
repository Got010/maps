package app.southbound.core.road

/** The pack's grid rows (one per tile and category) turned into the cells the map draws. */
object RoadClusters {

    /** One row of the pack's `cluster` table. */
    data class Row(val zoom: Int, val x: Int, val y: Int, val category: RoadCategory, val count: Int, val lat: Double, val lon: Double)

    /**
     * The cells of [rows] for the categories in [categories]: the rows of one tile summed into one cell, at the
     * mean of their positions weighted by their counts (six decimals, as the pack's own are). Ordered by column then
     * row, so the same rows always give the same list. A row of a category not in [categories], or with no spots,
     * is ignored. A place the pack filed under two categories is counted in each (a few thousand truck stops among
     * 168,000 rows; a circle's number is a guide, not an inventory).
     */
    fun merge(rows: List<Row>, categories: Set<RoadCategory>): List<RoadCluster> {
        val cells = LinkedHashMap<Triple<Int, Int, Int>, Cell>()
        for (row in rows) {
            if (row.category !in categories || row.count <= 0) continue
            val cell = cells.getOrPut(Triple(row.zoom, row.x, row.y)) { Cell() }
            cell.count += row.count
            cell.latSum += row.lat * row.count
            cell.lonSum += row.lon * row.count
        }
        return cells.entries
            .sortedWith(compareBy({ it.key.first }, { it.key.second }, { it.key.third }))
            .map { (key, cell) ->
                RoadCluster(key.first, key.second, key.third, cell.count, round6(cell.latSum / cell.count), round6(cell.lonSum / cell.count))
            }
    }

    private fun round6(degrees: Double): Double = Math.round(degrees * 1_000_000.0) / 1_000_000.0

    private class Cell {
        var count = 0
        var latSum = 0.0
        var lonSum = 0.0
    }
}
