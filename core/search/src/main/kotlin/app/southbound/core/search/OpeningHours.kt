package app.southbound.core.search

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The common subset of the OSM `opening_hours` grammar, enough to say whether
 * a place is open right now. Pure Kotlin on java.time; no Android types.
 *
 * Supported:
 *  - `24/7`
 *  - weekday selectors: `Mo`, `Mo-Fr`, `Sa,Su`, `Mo-Fr,Su`, wrapping `Fr-Mo`
 *  - several spans per day: `09:00-12:00,13:00-17:00`
 *  - rules separated by `;`; a later rule replaces the earlier ones for the
 *    days it names (`Mo-Sa 08:00-20:00; Su off`)
 *  - an additive rule after a comma: `Mo-Fr 09:00-17:00, Sa 10:00-14:00`
 *  - `off` and `closed`
 *  - spans past midnight: `22:00-02:00` (or `22:00-26:00`), and `24:00` as an end
 *  - `PH` and `SH` (public and school holidays) are accepted and IGNORED: we
 *    don't know the holiday calendar, so a holiday rule asserts nothing.
 *    `Mo-Fr 09:00-17:00; PH off` parses as `Mo-Fr 09:00-17:00` and
 *    [mentionsHolidays] is true.
 *
 * Anything else makes [parse] return null so the UI shows the raw tag:
 * months and dates (`Jan-Mar`, `Dec 25`), week numbers, `Mo[1]`, `sunrise`,
 * open-ended times (`10:00+`), `||` fallbacks, comments, and so on.
 *
 * Times are the place's wall-clock times; the caller passes the phone's local
 * time, which is right for anywhere in the same time zone as the place.
 *
 * Spill-over: a span past midnight carries into the next day even if a later
 * rule makes that next day `off` (`Sa 22:00-02:00; Su off` is open until
 * 02:00 on Sunday).
 */
class OpeningHours private constructor(
    /** Open minutes as inclusive ranges counted from this week's Monday 00:00; a span may run past the week's end. */
    private val week: List<LongRange>,
    /** True when the tag also had PH/SH rules, which were ignored. */
    val mentionsHolidays: Boolean,
) {
    sealed interface Status {
        /** [closesAt] is null when open round the clock; [closesSoon] when within [SOON_MINUTES]. */
        data class Open(val closesAt: LocalTime?, val closesSoon: Boolean) : Status

        /** [opensAt] is the next opening, or null when the tag never opens. */
        data class Closed(val opensAt: LocalDateTime?) : Status

        data object Unknown : Status
    }

    /** What to tell the user, with no wording: the UI maps each case to a string resource. */
    sealed interface Summary {
        data object Open24Hours : Summary
        data class OpenUntil(val closesAt: LocalTime) : Summary
        data class ClosesSoon(val closesAt: LocalTime) : Summary
        data class ClosedOpensToday(val opensAt: LocalTime) : Summary
        data class ClosedOpensTomorrow(val opensAt: LocalTime) : Summary
        data class ClosedOpensLater(val day: DayOfWeek, val opensAt: LocalTime) : Summary

        /** Parsed, but there is no opening time at all. */
        data object Closed : Summary

        /** Couldn't be parsed or nothing was asserted: show the raw tag. */
        data object Unparsed : Summary
    }

    fun status(now: LocalDateTime): Status {
        val t = (now.dayOfWeek.value - 1) * DAY + now.hour * 60L + now.minute
        val spans = merged()
        val here = spans.firstOrNull { t in it }
        if (here != null) {
            val left = here.last + 1 - t
            if (left >= WEEK) return Status.Open(closesAt = null, closesSoon = false)
            val end = here.last + 1
            return Status.Open(
                closesAt = LocalTime.of(((end % DAY) / 60).toInt(), (end % 60).toInt()),
                closesSoon = left <= SOON_MINUTES,
            )
        }
        val next = spans.firstOrNull { it.first > t } ?: return Status.Closed(null)
        val monday = now.toLocalDate().minusDays((now.dayOfWeek.value - 1).toLong()).atStartOfDay()
        return Status.Closed(monday.plusMinutes(next.first))
    }

    fun summary(now: LocalDateTime): Summary = when (val s = status(now)) {
        is Status.Open -> when {
            s.closesAt == null -> Summary.Open24Hours
            s.closesSoon -> Summary.ClosesSoon(s.closesAt)
            else -> Summary.OpenUntil(s.closesAt)
        }
        is Status.Closed -> {
            val at = s.opensAt
            if (at == null) {
                Summary.Closed
            } else {
                val days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
                when (days) {
                    0L -> Summary.ClosedOpensToday(at.toLocalTime())
                    1L -> Summary.ClosedOpensTomorrow(at.toLocalTime())
                    else -> Summary.ClosedOpensLater(at.dayOfWeek, at.toLocalTime())
                }
            }
        }
        Status.Unknown -> Summary.Unparsed
    }

    /**
     * The week's open intervals repeated for the previous, this and the next
     * week (so spans wrap across Sunday night in both directions), sorted and
     * merged. Positions are minutes from this Monday 00:00 and may be negative.
     */
    private fun merged(): List<LongRange> {
        cache?.let { return it }
        val all = ArrayList<LongRange>()
        for (k in -1..1) {
            val shift = k * WEEK
            for (r in week) all += (r.first + shift)..(r.last + shift)
        }
        all.sortBy { it.first }
        val out = ArrayList<LongRange>()
        for (r in all) {
            val last = out.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                if (r.last > last.last) out[out.size - 1] = last.first..r.last
            } else {
                out += r
            }
        }
        return out.also { cache = it }
    }

    private var cache: List<LongRange>? = null

    companion object {
        const val SOON_MINUTES = 30L

        private const val DAY = 1440L
        private const val WEEK = 7 * DAY

        /** Parses [text], or null when it's empty, outside the supported subset, or says nothing we can use. */
        fun parse(text: String?): OpeningHours? {
            if (text.isNullOrBlank()) return null
            return try {
                parseOrThrow(text)
            } catch (_: Unsupported) {
                null
            }
        }

        /** Status of a raw tag; [Status.Unknown] when it can't be parsed. */
        fun statusOf(text: String?, now: LocalDateTime): Status = parse(text)?.status(now) ?: Status.Unknown

        /** Summary of a raw tag; [Summary.Unparsed] when it can't be parsed. */
        fun summaryOf(text: String?, now: LocalDateTime): Summary = parse(text)?.summary(now) ?: Summary.Unparsed

        /** "5 PM", "5:30 PM", or "17:30" in the phone's 12/24-hour style. */
        fun formatTime(time: LocalTime, use24h: Boolean, locale: Locale = Locale.getDefault()): String {
            val pattern = when {
                use24h -> "HH:mm"
                time.minute == 0 -> "h a"
                else -> "h:mm a"
            }
            return DateTimeFormatter.ofPattern(pattern, locale).format(time)
        }

        /** "Mon" in [locale]. */
        fun formatDay(day: DayOfWeek, locale: Locale = Locale.getDefault()): String =
            day.getDisplayName(TextStyle.SHORT, locale)

        private class Unsupported : Exception()

        private fun fail(): Nothing = throw Unsupported()

        private val DAY_NAMES = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
        private val SPAN = Regex("""(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})""")

        private class Rule(val days: Set<Int>, val spans: List<IntRange>, val additive: Boolean)

        private fun parseOrThrow(raw: String): OpeningHours {
            val text = raw.trim()
                .replace('–', '-').replace('—', '-')
                .replace(Regex("""\s*-\s*"""), "-")
                .replace(Regex("""\s*,\s*"""), ",")
                .replace(Regex("""\s*;\s*"""), ";")
                .replace(Regex("""\s+"""), " ")
            if (text.contains("||") || text.contains('"')) fail()

            // Per-day spans in minutes from that day's 00:00; may run past 1440.
            val days = List(7) { ArrayList<IntRange>() }
            var holidays = false
            var any = false
            for (piece in text.split(';')) {
                if (piece.isBlank()) continue
                for (rule in parseRule(piece.trim())) {
                    if (rule == null) {
                        holidays = true
                        continue
                    }
                    any = true
                    for (d in rule.days) {
                        if (!rule.additive) days[d].clear()
                        days[d] += rule.spans
                    }
                }
            }
            if (!any) fail()

            val week = ArrayList<LongRange>()
            for (d in 0..6) {
                for (s in days[d]) {
                    week += (d * DAY + s.first)..(d * DAY + s.last)
                }
            }
            return OpeningHours(week.sortedBy { it.first }, holidays)
        }

        /**
         * One `;`-separated rule. The result has a null element for each
         * holiday-only part that is ignored (so the caller can note it).
         * A rule may be followed by additive rules after a comma.
         */
        private fun parseRule(rule: String): List<Rule?> {
            if (rule == "24/7") return listOf(Rule(ALL_DAYS, listOf(0..1440), additive = false))

            val firstSpace = rule.indexOf(' ')
            val head = if (firstSpace < 0) rule else rule.substring(0, firstSpace)
            val dayPart: String?
            val rest: String
            if (head[0].isDigit() || head == "off" || head == "closed") {
                dayPart = null
                rest = rule
            } else {
                if (firstSpace < 0) fail() // a weekday selector with no times: unknown meaning
                dayPart = head
                rest = rule.substring(firstSpace + 1)
            }

            val parsedDays: Set<Int>? = dayPart?.let(::parseDays) // null from parseDays means holiday-only
            val results = ArrayList<Rule?>()

            val pieces = rest.split(',')
            val spans = ArrayList<IntRange>()
            var off = false
            var idx = 0
            while (idx < pieces.size) {
                val p = pieces[idx]
                val m = SPAN.matchEntire(p)
                when {
                    m != null -> spans += span(m)
                    p == "off" || p == "closed" -> off = true
                    p.isNotEmpty() && p[0].isLetter() && (spans.isNotEmpty() || off) -> {
                        // "Mo-Fr 09:00-17:00,Sa 10:00-14:00": an additive rule starts here.
                        break
                    }
                    else -> fail()
                }
                idx++
            }
            if (off && spans.isNotEmpty()) fail()
            if (!off && spans.isEmpty()) fail()

            if (dayPart != null && parsedDays == null) {
                results += null
            } else {
                results += Rule(parsedDays ?: ALL_DAYS, if (off) emptyList() else spans, additive = false)
            }
            if (idx < pieces.size) {
                val tail = pieces.subList(idx, pieces.size).joinToString(",")
                for (extra in parseRule(tail)) {
                    results += extra?.let { Rule(it.days, it.spans, additive = true) }
                }
            }
            return results
        }

        private val ALL_DAYS: Set<Int> = (0..6).toSet()

        /** The days named by `Mo-Fr,Su`, or null when only PH/SH were named. Unknown tokens fail. */
        private fun parseDays(part: String): Set<Int>? {
            val out = LinkedHashSet<Int>()
            var holidayOnly = true
            for (tok in part.split(',')) {
                val t = tok.lowercase()
                if (t == "ph" || t == "sh") continue
                holidayOnly = false
                val ends = t.split('-')
                if (ends.size > 2) fail()
                val a = DAY_NAMES.indexOf(ends[0])
                val b = if (ends.size == 2) DAY_NAMES.indexOf(ends[1]) else a
                if (a < 0 || b < 0) fail()
                var d = a
                while (true) {
                    out += d
                    if (d == b) break
                    d = (d + 1) % 7
                }
            }
            return if (holidayOnly) null else out
        }

        private fun span(m: MatchResult): IntRange {
            val (h1, m1, h2, m2) = m.destructured
            val sh = h1.toInt()
            val sm = m1.toInt()
            val eh = h2.toInt()
            val em = m2.toInt()
            if (sh > 23 || sm > 59 || em > 59 || eh > 48 || (eh == 48 && em != 0)) fail()
            val start = sh * 60 + sm
            var end = eh * 60 + em
            if (end == start) fail()
            if (end < start) end += 1440
            // Intervals are half-open in the input; stored as inclusive minute ranges.
            return start..(end - 1)
        }
    }
}
