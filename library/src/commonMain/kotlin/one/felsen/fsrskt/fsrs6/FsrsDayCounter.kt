package one.felsen.fsrskt.fsrs6

import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * Counts the days between two reviews. The [FsrsScheduler] uses the count to choose between the short-term
 * (less than 1 day) and the long-term formulas, and as elapsed time for the retrievability.
 */
fun interface FsrsDayCounter {

    /**
     * Counts the days from [from] to [to].
     *
     * @param from The earlier instant, usually the last review.
     * @param to The later instant, usually the current review.
     * @return The elapsed days. (in whole days, negative if [to] is before [from])
     */
    fun elapsedDays(from: Instant, to: Instant): Long

    companion object {

        /**
         * Whole 24-hour periods, rounded down: like py-fsrs `(now - last_review).days`.
         *
         * A review at 11 PM and the next at 5 AM count as 0 days, even though they are on different dates.
         * Unlike [one.felsen.fsrskt.helper.DateTimeHelper.elapsedDays], negative durations are also rounded down, as in
         * Python (-1 hour counts as -1 day).
         */
        val WholeDays: FsrsDayCounter = FsrsDayCounter { _, _ -> TODO() }

        /**
         * Day boundaries crossed, with the day starting at [dayCutoffHour] in [timeZone], as in
         * [one.felsen.fsrskt.helper.DateTimeHelper.elapsedCalendarDays]. Deviates from py-fsrs.
         *
         * A review at 11 PM and the next at 5 AM count as 1 day, two reviews before and after 4 AM as well.
         *
         * @param dayCutoffHour The hour at which a new day starts. (0 to 23)
         * @param timeZone The time zone of the learner.
         * @throws IllegalArgumentException If [dayCutoffHour] is not between 0 and 23.
         */
        fun calendarDays(
            dayCutoffHour: Int = 4,
            timeZone: TimeZone = TimeZone.currentSystemDefault(),
        ): FsrsDayCounter = TODO()
    }
}
