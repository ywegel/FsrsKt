package one.felsen.fsrskt.fsrs6

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FsrsDayCounterTest {

    private val start = Instant.parse("2024-01-01T23:00:00Z")

    @Test
    fun wholeDaysCountsWhole24HourPeriods() {
        val expected = mapOf(
            Duration.ZERO to 0L,
            6.hours to 0L,
            (1.days - 1.minutes) to 0L,
            1.days to 1L,
            (2.days + 23.hours) to 2L,
            365.days to 365L,
        )
        expected.forEach { (gap, days) ->
            assertEquals(days, FsrsDayCounter.WholeDays.elapsedDays(start, start + gap), "$gap")
        }
    }

    @Test
    fun wholeDaysRoundsNegativeDurationsDownLikePython() {
        assertEquals(-1L, FsrsDayCounter.WholeDays.elapsedDays(start, start - 1.hours))
        assertEquals(-1L, FsrsDayCounter.WholeDays.elapsedDays(start, start - 1.days))
        assertEquals(-2L, FsrsDayCounter.WholeDays.elapsedDays(start, start - 25.hours))
    }

    @Test
    fun calendarDaysCountsDayBoundariesAtTheCutoffHour() {
        val counter = FsrsDayCounter.calendarDays(dayCutoffHour = 4, timeZone = TimeZone.UTC)

        assertEquals(1L, counter.elapsedDays(start, Instant.parse("2024-01-02T05:00:00Z")))
        assertEquals(0L, counter.elapsedDays(start, Instant.parse("2024-01-02T03:59:00Z")))
        assertEquals(1L, counter.elapsedDays(Instant.parse("2024-01-02T03:00:00Z"), Instant.parse("2024-01-02T04:00:00Z")))
    }

    @Test
    fun calendarDaysValidatesTheCutoffHour() {
        assertFailsWith<IllegalArgumentException> { FsrsDayCounter.calendarDays(dayCutoffHour = 24, timeZone = TimeZone.UTC) }
        assertFailsWith<IllegalArgumentException> { FsrsDayCounter.calendarDays(dayCutoffHour = -1, timeZone = TimeZone.UTC) }
    }
}
