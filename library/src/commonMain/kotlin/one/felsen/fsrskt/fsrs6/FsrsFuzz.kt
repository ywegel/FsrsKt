package one.felsen.fsrskt.fsrs6

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.random.Random

/**
 * Adds a small amount of random "fuzz" to review intervals, so that cards learned together do not stay due together.
 * Same as py-fsrs `Scheduler._get_fuzzed_interval` and `FUZZ_RANGES`.
 */
object FsrsFuzz {

    /**
     * Intervals below this are not fuzzed. (in days)
     */
    const val MIN_FUZZED_INTERVAL = 2.5

    /**
     * py-fsrs `FUZZ_RANGES`: from [start] to [end] days, the range grows by [factor] per day.
     */
    private class FuzzRange(val start: Double, val end: Double, val factor: Double)

    private val fuzzRanges = listOf(
        FuzzRange(start = 2.5, end = 7.0, factor = 0.15),
        FuzzRange(start = 7.0, end = 20.0, factor = 0.1),
        FuzzRange(start = 20.0, end = Double.POSITIVE_INFINITY, factor = 0.05),
    )

    /**
     * Calculates the range of the fuzzed interval, like the inner function `_get_fuzz_range` of py-fsrs.
     *
     * The range grows with the interval: ±15 % of the days from 2.5 to 7, ±10 % from 7 to 20, ±5 % above 20, plus 1
     * day. Both ends are rounded with halves to the even day. The range is then bounded to at least 2 and at most
     * [maximumInterval].
     *
     * Note that [fuzzedInterval] can return one day more than [IntRange.last], as py-fsrs does.
     *
     * @param intervalDays The interval before fuzzing. (in whole days)
     * @param maximumInterval The longest allowed interval. (in days, at least 1)
     * @return The range of fuzzed intervals in whole days. Computed for any [intervalDays], but only used for
     * intervals of at least [MIN_FUZZED_INTERVAL], as shorter ones are not fuzzed. Empty never: if [maximumInterval]
     * is below 2, both ends are [maximumInterval].
     * @throws IllegalArgumentException If [maximumInterval] is less than 1.
     */
    fun fuzzRange(intervalDays: Int, maximumInterval: Int): IntRange {
        requireMaximumInterval(maximumInterval)

        var delta = 1.0
        for (range in fuzzRanges) {
            delta += range.factor * max(min(intervalDays.toDouble(), range.end) - range.start, 0.0)
        }

        // kotlin.math.round rounds halves to even, like Python's round().
        var first = round(intervalDays - delta).toInt()
        var last = round(intervalDays + delta).toInt()

        first = max(2, first)
        last = min(last, maximumInterval)
        first = min(first, last)

        return first..last
    }

    /**
     * Fuzzes [intervalDays] with the given random value [u], deterministically.
     *
     * `round(u * (last - first + 1) + first)` over [fuzzRange], bounded to at most [maximumInterval]. As in py-fsrs,
     * the result can be one day above the range, if [u] is close to 1 and [maximumInterval] is large enough.
     *
     * @param intervalDays The interval before fuzzing. (in whole days)
     * @param maximumInterval The longest allowed interval. (in days, at least 1)
     * @param u A uniformly distributed random value. (0.0 inclusive to 1.0 exclusive)
     * @return The fuzzed interval in whole days. [intervalDays] itself, if it is below [MIN_FUZZED_INTERVAL].
     * @throws IllegalArgumentException If [maximumInterval] is less than 1, or [u] is not in [0, 1).
     */
    fun fuzzedInterval(intervalDays: Int, maximumInterval: Int, u: Double): Int {
        requireMaximumInterval(maximumInterval)
        require(u >= 0.0 && u < 1.0) { "u must be in [0, 1), but was $u." }

        if (intervalDays < MIN_FUZZED_INTERVAL) return intervalDays

        val range = fuzzRange(intervalDays, maximumInterval)
        val fuzzed = u * (range.last - range.first + 1) + range.first
        return min(round(fuzzed).toInt(), maximumInterval)
    }

    /**
     * Fuzzes [intervalDays] with a value drawn from [random].
     *
     * Draws exactly one [Random.nextDouble] if [intervalDays] is at least [MIN_FUZZED_INTERVAL], and none otherwise,
     * as py-fsrs does with `random.random()`.
     *
     * @param intervalDays The interval before fuzzing. (in whole days)
     * @param maximumInterval The longest allowed interval. (in days, at least 1)
     * @param random The source of randomness. Pass a seeded [Random] for reproducible results.
     * @return The fuzzed interval in whole days, see [fuzzedInterval].
     * @throws IllegalArgumentException If [maximumInterval] is less than 1.
     */
    fun fuzzedInterval(intervalDays: Int, maximumInterval: Int, random: Random): Int {
        requireMaximumInterval(maximumInterval)
        if (intervalDays < MIN_FUZZED_INTERVAL) return intervalDays
        return fuzzedInterval(intervalDays, maximumInterval, random.nextDouble())
    }
}
