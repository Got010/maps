package app.southbound.core.search

import app.southbound.core.search.OpeningHours.Status
import app.southbound.core.search.OpeningHours.Summary
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningHoursTest {
    // 2026-10-05 is a Monday; every test week is Mon 5 Oct to Sun 11 Oct.
    private fun at(day: DayOfWeek, hour: Int, minute: Int = 0, second: Int = 0, weeks: Long = 0): LocalDateTime =
        LocalDateTime.of(2026, 10, 4 + day.value, hour, minute, second).plusWeeks(weeks)

    private fun hours(text: String): OpeningHours {
        val h = OpeningHours.parse(text)
        assertNotNull("should parse: $text", h)
        return h!!
    }

    private fun open(closes: String?, soon: Boolean = false) =
        Status.Open(closes?.let(LocalTime::parse), soon)

    /** Closed, opening at [day] [hour]:[minute] of this test week, or of the next with [weeks] = 1. */
    private fun closed(day: DayOfWeek, hour: Int, minute: Int = 0, weeks: Long = 0) =
        Status.Closed(at(day, hour, minute, weeks = weeks))

    @Test
    fun alwaysOpen() {
        val h = hours("24/7")
        assertEquals(open(null), h.status(at(DayOfWeek.WEDNESDAY, 3)))
        assertEquals(open(null), h.status(at(DayOfWeek.SUNDAY, 23, 59)))
        assertEquals(Summary.Open24Hours, h.summary(at(DayOfWeek.MONDAY, 0)))
    }

    @Test
    fun everyDayAllDayCountsAs24Hours() {
        assertEquals(open(null), hours("Mo-Su 00:00-24:00").status(at(DayOfWeek.THURSDAY, 12)))
    }

    @Test
    fun weekdayOffice() {
        val h = hours("Mo-Fr 09:00-17:00")
        assertEquals(open("17:00"), h.status(at(DayOfWeek.WEDNESDAY, 10)))
        assertEquals(Summary.OpenUntil(LocalTime.of(17, 0)), h.summary(at(DayOfWeek.WEDNESDAY, 10)))
    }

    @Test
    fun closesSoonWithinThirtyMinutes() {
        val h = hours("Mo-Fr 09:00-17:00")
        assertEquals(open("17:00", soon = true), h.status(at(DayOfWeek.WEDNESDAY, 16, 45)))
        assertEquals(Summary.ClosesSoon(LocalTime.of(17, 0)), h.summary(at(DayOfWeek.WEDNESDAY, 16, 45)))
        assertEquals(open("17:00", soon = true), h.status(at(DayOfWeek.WEDNESDAY, 16, 30)))
        assertEquals(open("17:00", soon = false), h.status(at(DayOfWeek.WEDNESDAY, 16, 29)))
    }

    @Test
    fun openingMinuteIsOpenAndClosingMinuteIsClosed() {
        val h = hours("Mo-Fr 09:00-17:00")
        assertEquals(open("17:00"), h.status(at(DayOfWeek.WEDNESDAY, 9, 0)))
        assertEquals(closed(DayOfWeek.WEDNESDAY, 9), h.status(at(DayOfWeek.WEDNESDAY, 8, 59, 59)))
        assertEquals(open("17:00", soon = true), h.status(at(DayOfWeek.WEDNESDAY, 16, 59, 59)))
        assertEquals(closed(DayOfWeek.THURSDAY, 9), h.status(at(DayOfWeek.WEDNESDAY, 17, 0)))
    }

    @Test
    fun saturdayIsClosedAndOpensMonday() {
        val h = hours("Mo-Fr 09:00-17:00")
        assertEquals(closed(DayOfWeek.MONDAY, 9, weeks = 1), h.status(at(DayOfWeek.SATURDAY, 10)))
        assertEquals(
            Summary.ClosedOpensLater(DayOfWeek.MONDAY, LocalTime.of(9, 0)),
            h.summary(at(DayOfWeek.SATURDAY, 10)),
        )
    }

    @Test
    fun fridayEveningSkipsClosedSaturdayToMonday() {
        val h = hours("Mo-Fr 09:00-17:00")
        assertEquals(
            Summary.ClosedOpensLater(DayOfWeek.MONDAY, LocalTime.of(9, 0)),
            h.summary(at(DayOfWeek.FRIDAY, 17, 30)),
        )
    }

    @Test
    fun closedBeforeOpeningSaysOpensToday() {
        val h = hours("Mo-Fr 09:00-17:00")
        assertEquals(Summary.ClosedOpensToday(LocalTime.of(9, 0)), h.summary(at(DayOfWeek.TUESDAY, 7, 15)))
    }

    @Test
    fun closedAfterClosingSaysOpensTomorrow() {
        val h = hours("Mo-Fr 09:00-17:00")
        assertEquals(Summary.ClosedOpensTomorrow(LocalTime.of(9, 0)), h.summary(at(DayOfWeek.TUESDAY, 18)))
    }

    @Test
    fun sundayNightWrapsToMonday() {
        val h = hours("Mo-Fr 09:00-17:00")
        assertEquals(closed(DayOfWeek.MONDAY, 9, weeks = 1), h.status(at(DayOfWeek.SUNDAY, 23, 59)))
        assertEquals(Summary.ClosedOpensTomorrow(LocalTime.of(9, 0)), h.summary(at(DayOfWeek.SUNDAY, 23, 59)))
    }

    @Test
    fun spanPastMidnightCarriesIntoNextDay() {
        val h = hours("Sa 10:00-02:00")
        assertEquals(open("02:00"), h.status(at(DayOfWeek.SUNDAY, 1)))
        assertEquals(open("02:00"), h.status(at(DayOfWeek.SATURDAY, 23)))
        assertEquals(open("02:00", soon = true), h.status(at(DayOfWeek.SUNDAY, 1, 45)))
        assertEquals(closed(DayOfWeek.SATURDAY, 10, weeks = 1), h.status(at(DayOfWeek.SUNDAY, 2)))
        // Saturday morning before 10:00 is not open from the previous Friday.
        assertEquals(closed(DayOfWeek.SATURDAY, 10), h.status(at(DayOfWeek.SATURDAY, 9)))
        assertEquals(Summary.OpenUntil(LocalTime.of(2, 0)), h.summary(at(DayOfWeek.SUNDAY, 1)))
    }

    @Test
    fun spanPastMidnightOnSundayWrapsToMonday() {
        val h = hours("Su 22:00-02:00")
        assertEquals(open("02:00"), h.status(at(DayOfWeek.MONDAY, 1)))
        assertEquals(closed(DayOfWeek.SUNDAY, 22), h.status(at(DayOfWeek.MONDAY, 3)))
    }

    @Test
    fun hoursPastTwentyFourAreAccepted() {
        val h = hours("Fr 22:00-26:00")
        assertEquals(open("02:00"), h.status(at(DayOfWeek.SATURDAY, 0, 30)))
    }

    @Test
    fun endOfDayTwentyFour() {
        val h = hours("Mo-Su 18:00-24:00")
        assertEquals(open("00:00"), h.status(at(DayOfWeek.TUESDAY, 20)))
        assertEquals(closed(DayOfWeek.TUESDAY, 18), h.status(at(DayOfWeek.TUESDAY, 12)))
    }

    @Test
    fun laterRuleOverridesEarlierForItsDays() {
        val h = hours("Mo-Sa 08:00-20:00; Su off")
        assertEquals(open("20:00"), h.status(at(DayOfWeek.SATURDAY, 12)))
        assertEquals(closed(DayOfWeek.MONDAY, 8, weeks = 1), h.status(at(DayOfWeek.SUNDAY, 12)))
        assertEquals(Summary.ClosedOpensTomorrow(LocalTime.of(8, 0)), h.summary(at(DayOfWeek.SUNDAY, 12)))
    }

    @Test
    fun laterRuleReplacesOnlyTheDaysItNames() {
        val h = hours("Mo-Fr 08:00-18:00; We 10:00-14:00")
        assertEquals(open("18:00"), h.status(at(DayOfWeek.TUESDAY, 9)))
        assertEquals(closed(DayOfWeek.WEDNESDAY, 10), h.status(at(DayOfWeek.WEDNESDAY, 9)))
        assertEquals(open("14:00"), h.status(at(DayOfWeek.WEDNESDAY, 11)))
        assertEquals(closed(DayOfWeek.THURSDAY, 8), h.status(at(DayOfWeek.WEDNESDAY, 15)))
    }

    @Test
    fun offRuleAloneClosesEveryDay() {
        val h = hours("off")
        assertEquals(Status.Closed(null), h.status(at(DayOfWeek.MONDAY, 12)))
        assertEquals(Summary.Closed, h.summary(at(DayOfWeek.MONDAY, 12)))
    }

    @Test
    fun closedKeywordWorksLikeOff() {
        val h = hours("Mo-Fr 09:00-17:00; We closed")
        assertEquals(closed(DayOfWeek.THURSDAY, 9), h.status(at(DayOfWeek.WEDNESDAY, 12)))
    }

    @Test
    fun splitDay() {
        val h = hours("Mo-Fr 08:00-12:00,13:00-18:00")
        assertEquals(open("12:00"), h.status(at(DayOfWeek.MONDAY, 9)))
        assertEquals(closed(DayOfWeek.MONDAY, 13), h.status(at(DayOfWeek.MONDAY, 12, 30)))
        assertEquals(Summary.ClosedOpensToday(LocalTime.of(13, 0)), h.summary(at(DayOfWeek.MONDAY, 12, 30)))
        assertEquals(open("18:00"), h.status(at(DayOfWeek.MONDAY, 14)))
    }

    @Test
    fun contiguousSpansMerge() {
        val h = hours("Mo-Fr 08:00-12:00,12:00-18:00")
        assertEquals(open("18:00"), h.status(at(DayOfWeek.MONDAY, 9)))
    }

    @Test
    fun midnightSpansAcrossTwoDaysChain() {
        val h = hours("Fr 22:00-24:00,Sa 00:00-03:00")
        assertEquals(open("03:00"), h.status(at(DayOfWeek.FRIDAY, 23)))
        assertEquals(open("03:00"), h.status(at(DayOfWeek.SATURDAY, 1)))
    }

    @Test
    fun weekdayLists() {
        val h = hours("Sa,Su 10:00-16:00")
        assertEquals(open("16:00"), h.status(at(DayOfWeek.SUNDAY, 11)))
        assertEquals(closed(DayOfWeek.SATURDAY, 10), h.status(at(DayOfWeek.FRIDAY, 11)))

        val mixed = hours("Mo-Fr,Su 09:00-17:00")
        assertTrue(mixed.status(at(DayOfWeek.SUNDAY, 10)) is Status.Open)
        assertTrue(mixed.status(at(DayOfWeek.SATURDAY, 10)) is Status.Closed)
        assertTrue(mixed.status(at(DayOfWeek.FRIDAY, 10)) is Status.Open)
    }

    @Test
    fun wrappingWeekdayRange() {
        val h = hours("Fr-Mo 10:00-20:00")
        assertTrue(h.status(at(DayOfWeek.SUNDAY, 12)) is Status.Open)
        assertTrue(h.status(at(DayOfWeek.MONDAY, 12)) is Status.Open)
        assertTrue(h.status(at(DayOfWeek.TUESDAY, 12)) is Status.Closed)
        assertTrue(h.status(at(DayOfWeek.THURSDAY, 12)) is Status.Closed)
    }

    @Test
    fun additiveRuleAfterComma() {
        val h = hours("Mo-Fr 09:00-17:00, Sa 10:00-14:00")
        assertEquals(open("14:00"), h.status(at(DayOfWeek.SATURDAY, 11)))
        assertEquals(open("17:00"), h.status(at(DayOfWeek.FRIDAY, 11)))
        assertTrue(h.status(at(DayOfWeek.SUNDAY, 11)) is Status.Closed)
    }

    @Test
    fun offAfterACommaClosesItsDays() {
        val h = hours("Mo-Fr 09:00-17:00, We off")
        assertEquals(closed(DayOfWeek.THURSDAY, 9), h.status(at(DayOfWeek.WEDNESDAY, 11)))
        assertEquals(open("17:00"), h.status(at(DayOfWeek.TUESDAY, 11)))
        assertEquals(open("17:00"), h.status(at(DayOfWeek.THURSDAY, 11)))
        // Closed in a list of additive rules too, and a later one still adds.
        val list = hours("Mo-Sa 08:00-12:00, We,Sa closed, Sa 14:00-16:00")
        assertTrue(list.status(at(DayOfWeek.WEDNESDAY, 9)) is Status.Closed)
        assertTrue(list.status(at(DayOfWeek.SATURDAY, 9)) is Status.Closed)
        assertEquals(open("16:00"), list.status(at(DayOfWeek.SATURDAY, 15)))
        assertEquals(open("12:00"), list.status(at(DayOfWeek.FRIDAY, 9)))
    }

    @Test
    fun noWeekdaySelectorMeansEveryDay() {
        val h = hours("08:00-20:00")
        assertEquals(open("20:00"), h.status(at(DayOfWeek.SUNDAY, 9)))
    }

    @Test
    fun toleratesSpacingAndCase() {
        val h = hours("  mo-fr  9:00 - 17:00 ;  sa 10:00 - 14:00 ")
        assertEquals(open("17:00"), h.status(at(DayOfWeek.MONDAY, 10)))
        assertEquals(open("14:00"), h.status(at(DayOfWeek.SATURDAY, 10)))
        assertEquals(open("17:00"), hours("Mo-Fr 09:00–17:00").status(at(DayOfWeek.MONDAY, 10)))
    }

    @Test
    fun publicHolidayRulesAreIgnoredButTheRestParses() {
        // We don't know the holiday calendar, so "PH off" asserts nothing: the weekday rule stands.
        val h = hours("Mo-Fr 09:00-17:00; PH off")
        assertTrue(h.mentionsHolidays)
        assertEquals(open("17:00"), h.status(at(DayOfWeek.WEDNESDAY, 10)))
        assertEquals(Summary.OpenUntil(LocalTime.of(17, 0)), h.summary(at(DayOfWeek.WEDNESDAY, 10)))
        assertFalse(hours("Mo-Fr 09:00-17:00").mentionsHolidays)
    }

    @Test
    fun holidayInADayListIsDroppedFromTheList() {
        val h = hours("Mo-Fr,PH 09:00-17:00")
        assertEquals(open("17:00"), h.status(at(DayOfWeek.MONDAY, 10)))
        assertTrue(h.status(at(DayOfWeek.SATURDAY, 10)) is Status.Closed)
    }

    @Test
    fun schoolHolidaysAreIgnoredToo() {
        val h = hours("Mo-Fr 08:00-15:00; SH off; PH off")
        assertTrue(h.mentionsHolidays)
        assertEquals(open("15:00"), h.status(at(DayOfWeek.TUESDAY, 10)))
    }

    @Test
    fun onlyHolidayRulesAssertNothing() {
        assertNull(OpeningHours.parse("PH off"))
    }

    @Test
    fun monthsAndDatesAreUnparseable() {
        assertNull(OpeningHours.parse("Jan-Mar 10:00-16:00"))
        assertNull(OpeningHours.parse("Mo-Fr 09:00-17:00; Dec 25 off"))
        assertNull(OpeningHours.parse("Apr-Oct Mo-Fr 09:00-17:00"))
    }

    @Test
    fun weekNumbersAndNthWeekdaysAreUnparseable() {
        assertNull(OpeningHours.parse("week 1-10 Mo-Fr 09:00-17:00"))
        assertNull(OpeningHours.parse("Mo[1] 09:00-17:00"))
        assertNull(OpeningHours.parse("Mo-Fr 09:00-17:00; Sa[1,3] off"))
    }

    @Test
    fun otherUnsupportedSyntaxIsNull() {
        assertNull(OpeningHours.parse(null))
        assertNull(OpeningHours.parse(""))
        assertNull(OpeningHours.parse("   "))
        assertNull(OpeningHours.parse("by appointment"))
        assertNull(OpeningHours.parse("Mo-Fr sunrise-sunset"))
        assertNull(OpeningHours.parse("Mo-Fr 09:00+"))
        assertNull(OpeningHours.parse("Mo-Fr 09:00"))
        assertNull(OpeningHours.parse("Mo-Fr"))
        assertNull(OpeningHours.parse("Mo-Fr 09:00-17:00 || \"call us\""))
        assertNull(OpeningHours.parse("Mo-Fr 09:00-17:00 \"by phone\""))
        assertNull(OpeningHours.parse("Mo-Fr 25:00-26:00"))
        assertNull(OpeningHours.parse("Mo-Fr 09:00-09:00"))
        assertNull(OpeningHours.parse("Mo-Fr 09:99-17:00"))
        assertNull(OpeningHours.parse("Mo-Fr 09:00-17:00 open"))
        assertNull(OpeningHours.parse("Xx 09:00-17:00"))
        assertNull(OpeningHours.parse("24/7 off"))
    }

    @Test
    fun statusOfAndSummaryOfFallBackWhenUnparsed() {
        val now = at(DayOfWeek.MONDAY, 10)
        assertEquals(Status.Unknown, OpeningHours.statusOf("Jan-Mar 10:00-16:00", now))
        assertEquals(Summary.Unparsed, OpeningHours.summaryOf("Jan-Mar 10:00-16:00", now))
        assertEquals(Summary.Unparsed, OpeningHours.summaryOf(null, now))
        assertEquals(Summary.OpenUntil(LocalTime.of(17, 0)), OpeningHours.summaryOf("Mo-Fr 09:00-17:00", now))
    }

    @Test
    fun formatsTimesInThePhonesStyle() {
        assertEquals("5 PM", OpeningHours.formatTime(LocalTime.of(17, 0), false, Locale.US))
        assertEquals("4:45 PM", OpeningHours.formatTime(LocalTime.of(16, 45), false, Locale.US))
        assertEquals("12 AM", OpeningHours.formatTime(LocalTime.of(0, 0), false, Locale.US))
        assertEquals("9 AM", OpeningHours.formatTime(LocalTime.of(9, 0), false, Locale.US))
        assertEquals("17:00", OpeningHours.formatTime(LocalTime.of(17, 0), true, Locale.US))
        assertEquals("09:05", OpeningHours.formatTime(LocalTime.of(9, 5), true, Locale.US))
        assertEquals("Mon", OpeningHours.formatDay(DayOfWeek.MONDAY, Locale.US))
    }
}
