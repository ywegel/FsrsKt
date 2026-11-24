package one.felsen.fsrskt.helper

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Helper class for date and time related operations, particularly for determining if two Instants fall on the same day
 * based on a specified day cutoff hour.
 */
object DateTimeHelper {

    /**
     * Converts an Instant to a day number, considering a specified day cutoff hour.
     *
     * @param dayCutoffHour The hour at which the day is considered to start (0 to 23, default is 4 AM).
     * @param timeZone The time zone to use for the conversion (default is the system's current time zone).
     * @return The day number corresponding to the Instant.
     */
    fun Instant.toDayNumber(dayCutoffHour: Int = 4, timeZone: TimeZone = TimeZone.currentSystemDefault()): Long {
        require(dayCutoffHour in 0..23) { "dayCutoffHour must be between 0 and 23, but was $dayCutoffHour." }

        val localDateTime = this.toLocalDateTime(timeZone)
        val dayNumber = localDateTime.date.toEpochDays()

        return if (localDateTime.hour < dayCutoffHour) dayNumber - 1 else dayNumber
    }

    /**
     * Calculates the elapsed days between two Instants.
     *
     * @param other The other Instant to compare with. Defaults to the current system time if not provided.
     * @return The number of whole days elapsed between the two Instants as a Double.
     */
    fun Instant.elapsedDays(other: Instant = Clock.System.now()): Double {
        return (other - this).inWholeDays.toDouble()
    }

    /**
     * Calculates the elapsed calendar days between two Instants, considering a specified day cutoff hour.
     *
     * @param other The other Instant to compare with. Defaults to the current system time if not provided.
     * @param dayCutoffHour The hour at which the day is considered to start (default is 4 AM).
     * @param timeZone The time zone to use for the conversion (default is the system's current time zone).
     * @return The number of day boundaries crossed between the two Instants as a Double.
     */
    fun Instant.elapsedCalendarDays(
        other: Instant = Clock.System.now(),
        dayCutoffHour: Int = 4,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
    ): Double {
        return (other.toDayNumber(dayCutoffHour, timeZone) - this.toDayNumber(dayCutoffHour, timeZone)).toDouble()
    }

    /**
     * Calculates the start of the day after the day of this Instant, considering a specified day cutoff hour. An Instant
     * before the cutoff hour still belongs to the previous calendar day, so with a 4 AM cutoff both 23:00 and 03:00 of
     * the following night lead to 04:00 of that following morning.
     *
     * @param dayCutoffHour The hour at which the day is considered to start (0 to 23, default is 4 AM).
     * @param timeZone The time zone to use for the conversion (default is the system's current time zone).
     * @return The Instant at which the next day starts.
     */
    fun Instant.startOfNextDay(dayCutoffHour: Int = 4, timeZone: TimeZone = TimeZone.currentSystemDefault()): Instant {
        val nextDay = LocalDate.fromEpochDays(toDayNumber(dayCutoffHour, timeZone) + 1)
        return nextDay.atTime(dayCutoffHour, 0).toInstant(timeZone)
    }

    /**
     * Determines if two Instants fall on the same day, considering a specified day cutoff hour.
     *
     * @param other The other Instant to compare with.
     * @param dayCutoffHour The hour at which the day is considered to start (default is 4 AM).
     * @param timeZone The time zone to use for the comparison (default is the system's current time zone).
     * @return True if both Instants fall on the same day; otherwise, false.
     */
    fun Instant.isSameDay(other: Instant, dayCutoffHour: Int = 4, timeZone: TimeZone = TimeZone.currentSystemDefault()): Boolean {
        return this.toDayNumber(dayCutoffHour, timeZone) == other.toDayNumber(dayCutoffHour, timeZone)
    }
}
