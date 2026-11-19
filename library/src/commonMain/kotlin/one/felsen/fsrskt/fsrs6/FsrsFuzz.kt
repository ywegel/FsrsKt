package one.felsen.fsrskt.fsrs6

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
     * @return The range of fuzzed intervals in whole days, or just [intervalDays] if it is below
     * [MIN_FUZZED_INTERVAL], as it is not fuzzed then.
     * @throws IllegalArgumentException If [maximumInterval] is less than 1.
     */
    fun fuzzRange(intervalDays: Int, maximumInterval: Int): IntRange = TODO()

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
    fun fuzzedInterval(intervalDays: Int, maximumInterval: Int, u: Double): Int = TODO()

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
    fun fuzzedInterval(intervalDays: Int, maximumInterval: Int, random: Random): Int = TODO()
}
