package app.southbound.core.userdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LegCsvTest {

    private fun leg(id: Long, on: String, meters: Long, from: String = "Motel A", to: String = "Site B", trip: String = "Fall trip", edited: Boolean = false, mode: LegMode = LegMode.DRIVE) =
        Leg(id, trip, on, meters, mode, from, to, edited)

    @Test
    fun `header, rows by day then id, CRLF, no total row`() {
        val csv = LegCsv.csv(
            listOf(
                leg(5, "2026-10-25", 341_825, edited = true),
                leg(4, "2026-10-24", 16_093, from = "Site B", to = "Motel A", mode = LegMode.BIKE),
                leg(3, "2026-10-25", 1_609, mode = LegMode.WALK),
            ),
        )
        assertEquals(
            "trip,date,from,to,mode,miles,km,source\r\n" +
                "Fall trip,2026-10-24,Site B,Motel A,bike,10.0,16.1,planned\r\n" +
                "Fall trip,2026-10-25,Motel A,Site B,walk,1.0,1.6,planned\r\n" +
                "Fall trip,2026-10-25,Motel A,Site B,drive,212.4,341.8,edited\r\n",
            csv,
        )
        // Every line ends with CRLF and nothing else breaks a line.
        assertFalse(csv.replace("\r\n", "").contains('\n'))
        assertFalse(csv.replace("\r\n", "").contains('\r'))
        assertFalse(csv.contains("total", ignoreCase = true))
        assertEquals("trip,date,from,to,mode,miles,km,source\r\n", LegCsv.csv(emptyList()))
    }

    @Test
    fun `quoting follows RFC 4180`() {
        assertEquals("plain", LegCsv.field("plain"))
        assertEquals("\"Motel A, Room 2\"", LegCsv.field("Motel A, Room 2"))
        assertEquals("\"The \"\"Big\"\" Site\"", LegCsv.field("The \"Big\" Site"))
        assertEquals("\"a\nb\"", LegCsv.field("a\nb"))
        val csv = LegCsv.csv(listOf(leg(3, "2026-10-24", 1_000, from = "Lot 4, west", to = "\"Q\" Site")))
        assertTrue(csv.contains(",\"Lot 4, west\",\"\"\"Q\"\" Site\",drive,"))
    }

    @Test
    fun `a field that starts like a formula gets a leading quote`() {
        for (start in listOf("=", "+", "-", "@", "\t")) {
            assertEquals("'${start}1+1", LegCsv.field("${start}1+1"))
        }
        assertEquals("\"'=HYPERLINK(\"\"x\"\",\"\"y\"\")\"", LegCsv.field("=HYPERLINK(\"x\",\"y\")"))
        // Only at the start.
        assertEquals("Site-B", LegCsv.field("Site-B"))
        val csv = LegCsv.csv(listOf(leg(3, "2026-10-24", 1_000, from = "=cmd", to = "@Site", trip = "-Fall")))
        assertTrue(csv, csv.contains("'-Fall,2026-10-24,'=cmd,'@Site,drive,"))
    }

    @Test
    fun `the mileage line is the rows' sum and at most 60 characters`() {
        val legs = listOf(
            leg(3, "2026-10-24", 100_000), leg(4, "2026-10-26", 200_000), leg(5, "2026-10-25", 363_525),
            leg(6, "2026-10-28", 1), leg(7, "2026-10-30", 80),
        )
        val trip = LegLog.totals("Fall trip", legs)
        val line = LegCsv.mileageLine(trip)
        assertEquals("412.3 mi (663.6 km), 5 legs, 2026-10-24 to 2026-10-30", line)
        // Its miles are the rows' miles as the CSV prints them, added up.
        val rowMiles = LegCsv.csv(legs).split("\r\n").drop(1).filter { it.isNotEmpty() }.map { it.split(",")[5] }
        assertEquals("412.3", rowMiles.map { java.math.BigDecimal(it) }.reduce(java.math.BigDecimal::add).toPlainString())
        val rowKm = LegCsv.csv(legs).split("\r\n").drop(1).filter { it.isNotEmpty() }.map { java.math.BigDecimal(it.split(",")[6]) }
        assertEquals("663.6", rowKm.reduce(java.math.BigDecimal::add).toPlainString())
        // One leg, one day.
        assertEquals("62.1 mi (100.0 km), 1 leg, 2026-10-24", LegCsv.mileageLine(LegLog.totals("t", listOf(leg(3, "2026-10-24", 100_000)))))
    }

    @Test
    fun `at the caps the kilometres go first, then the range`() {
        // 5,000 legs of 10,000 km: the longest numbers the log can hold.
        val most = (1..LegLog.MAX_LEGS).map { leg(it.toLong() + 2, if (it == 1) "2026-01-01" else "2026-12-31", Leg.MAX_METERS) }
        val line = LegCsv.mileageLine(LegLog.totals("t", most))
        assertEquals("31068500.0 mi, 5000 legs, 2026-01-01 to 2026-12-31", line)
        assertTrue(line.length <= LegCsv.MAX_LINE)
        // Wherever it is cut, the line never passes 60 characters, for every count up to the cap.
        for (n in listOf(1, 9, 10, 99, 100, 999, 1_000, LegLog.MAX_LEGS)) {
            val t = LegLog.totals("t", most.take(n))
            assertTrue("$n legs", LegCsv.mileageLine(t).length <= LegCsv.MAX_LINE)
            assertTrue(LegCsv.mileageLine(t).startsWith(Distance.format(t.tenthsMiles) + " mi"))
        }
        // A size where only the kilometres have to go.
        val mid = LegLog.totals("t", most.take(1_000))
        assertEquals("6213700.0 mi, 1000 legs, 2026-01-01 to 2026-12-31", LegCsv.mileageLine(mid))
        // Ten capped legs: the longest line that keeps the kilometres (59 characters).
        val ten = LegCsv.mileageLine(LegLog.totals("t", most.take(10)))
        assertEquals("62137.0 mi (100000.0 km), 10 legs, 2026-01-01 to 2026-12-31", ten)
        assertEquals(59, ten.length)
    }

    @Test
    fun `a CSV is offered as text only up to 64 KiB`() {
        assertEquals(64 * 1024, LegCsv.MAX_SHARE_BYTES)
        assertTrue(LegCsv.fitsTextShare(LegCsv.csv(listOf(leg(3, "2026-10-24", 1_000)))))
        assertTrue(LegCsv.fitsTextShare("x".repeat(LegCsv.MAX_SHARE_BYTES)))
        assertFalse(LegCsv.fitsTextShare("x".repeat(LegCsv.MAX_SHARE_BYTES + 1)))
        // Counted in UTF-8 bytes, not characters.
        assertFalse(LegCsv.fitsTextShare("\u00e9".repeat(LegCsv.MAX_SHARE_BYTES / 2 + 1)))
        // A full log of long labels is far past it; a few hundred plain legs are not.
        val long = "L".repeat(Leg.MAX_LABEL)
        assertFalse(LegCsv.fitsTextShare(LegCsv.csv((1..LegLog.MAX_LEGS).map { leg(it.toLong() + 2, "2026-10-24", 1_000, from = long, to = long) })))
        assertTrue(LegCsv.fitsTextShare(LegCsv.csv((1..300).map { leg(it.toLong() + 2, "2026-10-24", 341_825) })))
    }

    @Test
    fun `the file name is the day it is saved`() {
        assertEquals("southbound-legs-20261024.csv", LegCsv.fileName(LocalDate.of(2026, 10, 24)))
    }
}
