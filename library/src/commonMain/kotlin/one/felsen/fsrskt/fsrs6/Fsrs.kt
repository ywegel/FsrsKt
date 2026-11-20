package one.felsen.fsrskt.fsrs6

import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

/**
 * FSRS6 implementation based on https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm
 *
 * @param parameters The parameters for the FSRS algorithm.
 */
class Fsrs(parameters: List<Double> = FsrsParameters.defaultParameters) {

    /**
     * The parameters for the FSRS algorithm.
     */
    val w: List<Double> = parameters.toList()

    init {
        /**
         * Ensures that the parameters list contains exactly 21 values. Required by the FSRS6 algorithm for proper calculations.
         * See: https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm#default-parameters
         */
        require(w.size == 21) { "Parameters must contain exactly 21 values." }

        /**
         * Ensures that every parameter lies within its bounds and reports all parameters that do not.
         */
        val errorMessages = w.indices.mapNotNull { index ->
            val parameter = w[index]
            val lowerBound = FsrsParameters.lowerBoundsParameters[index]
            val upperBound = FsrsParameters.upperBoundsParameters[index]

            if (lowerBound <= parameter && parameter <= upperBound) {
                null
            } else {
                "parameters[$index] = $parameter is out of bounds: ($lowerBound, $upperBound)"
            }
        }
        require(errorMessages.isEmpty()) {
            "One or more parameters are out of bounds:\n" + errorMessages.joinToString("\n")
        }
    }

    /**
     * The decay of the forgetting curve, `-w[20]`. Always negative, between -0.8 and -0.1.
     *
     * The sign follows py-fsrs (`Scheduler._DECAY`): the retrievability is `(1 + factor * t / S)^decay`.
     */
    val decay: Double = -w[20]

    /**
     * The factor of the forgetting curve, `0.9^(1 / decay) - 1`. Chosen so that the retrievability is exactly 0.9
     * when the elapsed days equal the stability. Always positive.
     *
     * Same as py-fsrs `Scheduler._FACTOR`.
     */
    val factor: Double = 0.9.pow(-1.0 / w[20]) - 1.0

    private fun clampStability(stability: Double): Double = maxOf(stability, FsrsParameters.STABILITY_MIN)

    /**
     * Calculates the initial stability for a given rating.
     *
     * @param rating The rating for which to calculate the initial stability.
     * @return The initial stability value, at least [FsrsParameters.STABILITY_MIN].
     */
    fun initialStability(rating: FsrsRating): Double = clampStability(w[rating.value - 1])

    /**
     * Calculates the initial difficulty for a given rating.
     *
     * @param rating The rating for which to calculate the initial difficulty.
     * @param clamp Whether to clamp the value between 1.0 and 10.0.
     * @return The initial difficulty value.
     */
    fun initialDifficulty(rating: FsrsRating, clamp: Boolean = true): Double {
        val initialDifficulty = w[4] - exp(w[5] * (rating.value - 1)) + 1.0
        return if (clamp) initialDifficulty.coerceIn(1.0, 10.0) else initialDifficulty
    }


    /**
     * Calculates the retrievability based on elapsed time and stability.
     *
     * @param t The elapsed time since the last review (in days), or null if the time of the last review is unknown.
     * Negative values are treated as 0.
     * @param s The current stability of the item. (in days)
     * @return The retrievability value, which indicates how likely the item is to be recalled. 0 if [t] is unknown.
     */
    fun retrievability(t: Double?, s: Double): Double {
        if (t == null) return 0.0

        val r = (1.0 + factor * (maxOf(0.0, t) / s)).pow(-w[20])
        return r
    }

    /**
     * Calculates the interval after which the retrievability drops to [desiredRetention], without rounding or bounds.
     *
     * `interval = stability / factor * (desiredRetention^(1 / decay) - 1)`. With a [desiredRetention] of 0.9 the
     * interval equals the stability. Use it for own scheduling rules; [nextInterval] is what py-fsrs schedules.
     *
     * @param stability The stability of the item. (in days)
     * @param desiredRetention The retrievability at the end of the interval. (exclusive 0.0 to exclusive 1.0)
     * @return The interval. (in fractional days)
     * @throws IllegalArgumentException If [desiredRetention] is not between 0 and 1, both exclusive. py-fsrs does not
     * check this.
     */
    fun interval(stability: Double, desiredRetention: Double): Double {
        requireDesiredRetention(desiredRetention)
        // Same operation order as py-fsrs, so that the result is bit-identical.
        return (stability / factor) * (desiredRetention.pow(1.0 / decay) - 1.0)
    }

    /**
     * Calculates the scheduled interval in whole days, like py-fsrs `Scheduler._next_interval`.
     *
     * Rounds [interval] to the nearest whole day, with halves rounded to the even day like Python's `round`
     * (2.5 becomes 2, 3.5 becomes 4). Then bounds it to at least 1 and at most [maximumInterval].
     *
     * @param stability The stability of the item. (in days)
     * @param desiredRetention The retrievability at the end of the interval. (exclusive 0.0 to exclusive 1.0)
     * @param maximumInterval The longest allowed interval. (in days, at least 1)
     * @return The interval. (in whole days, 1 to [maximumInterval])
     * @throws IllegalArgumentException If [desiredRetention] is not between 0 and 1, both exclusive, or
     * [maximumInterval] is less than 1. py-fsrs does not check either.
     */
    fun nextInterval(stability: Double, desiredRetention: Double, maximumInterval: Int): Int {
        requireMaximumInterval(maximumInterval)
        // kotlin.math.round rounds halves to even, like Python's round(). roundToInt() would round them up.
        val rounded = round(interval(stability, desiredRetention))
        return rounded.coerceIn(1.0, maximumInterval.toDouble()).toInt()
    }

    /**
     * Calculates the next difficulty based on the current difficulty and rating.
     *
     * @param difficulty The current difficulty of the item. (1.0 to 10.0)
     * @param rating The rating given by the user after reviewing the item.
     * @return The next difficulty value, clamped between 1.0 and 10.0.
     */
    fun nextDifficulty(difficulty: Double, rating: FsrsRating): Double {
        val deltaD = -w[6] * (rating.value - 3.0)
        val dPrime = difficulty + deltaD * ((10.0 - difficulty) / 9.0)
        val dDoublePrime = w[7] * initialDifficulty(FsrsRating.EASY, clamp = false) + (1.0 - w[7]) * dPrime
        return dDoublePrime.coerceIn(1.0, 10.0)
    }

    /**
     * Calculates the next stability for short-term reviews based on the current stability and rating.
     *
     * @param stability The current stability of the item. (in days)
     * @param rating The rating given by the user after reviewing the item.
     * @return The next stability value, at least [FsrsParameters.STABILITY_MIN], which indicates how long until the item should be reviewed again.
     */
    fun nextStabilityShortTerm(stability: Double, rating: FsrsRating): Double {
        val shortTermStabilityIncrease = exp(w[17] * (rating.value - 3.0 + w[18])) * stability.pow(-w[19])

        val increase = when (rating) {
            FsrsRating.HARD, FsrsRating.GOOD, FsrsRating.EASY -> maxOf(shortTermStabilityIncrease, 1.0)
            FsrsRating.AGAIN -> shortTermStabilityIncrease
        }

        return clampStability(stability * increase)
    }

    /**
     * Calculates the next stability for long-term reviews, using [stabilityOnLapse] for AGAIN and [stabilityOnRecall] for all other ratings.
     *
     * @param difficulty The current difficulty of the item. (1.0 to 10.0)
     * @param stability The current stability of the item. (in days)
     * @param retrievability The current retrievability of the item. (0.0 to 1.0)
     * @param rating The rating given by the user after reviewing the item.
     * @return The next stability value, at least [FsrsParameters.STABILITY_MIN], which indicates how long until the item should be reviewed again.
     */
    fun nextStability(difficulty: Double, stability: Double, retrievability: Double, rating: FsrsRating): Double {
        val nextStability = when (rating) {
            FsrsRating.AGAIN -> stabilityOnLapse(difficulty, stability, retrievability)
            FsrsRating.HARD, FsrsRating.GOOD, FsrsRating.EASY -> stabilityOnRecall(difficulty, stability, retrievability, rating)
        }

        return clampStability(nextStability)
    }

    /**
     * Calculates the next stability for long-term reviews based on the current difficulty, stability, retrievability, and rating.
     * In case the rating is AGAIN, the stabilityOnLapse function should be used instead.
     *
     * @param difficulty The current difficulty of the item. (1.0 to 10.0)
     * @param stability The current stability of the item. (in days)
     * @param retrievability The current retrievability of the item. (0.0 to 1.0)
     * @param rating The rating given by the user after reviewing the item.
     * @return The next stability value, which indicates how long until the item should be reviewed again.
     */
    fun stabilityOnRecall(difficulty: Double, stability: Double, retrievability: Double, rating: FsrsRating): Double {
        val w15 = if (rating == FsrsRating.HARD) w[15] else 1.0
        val w16 = if (rating == FsrsRating.EASY) w[16] else 1.0

        val sPrime =
            stability * (exp(w[8]) * (11.0 - difficulty) * stability.pow(-w[9]) * (exp(w[10] * (1.0 - retrievability)) - 1.0) * w15 * w16 + 1.0)
        return sPrime
    }

    /**
     * Calculates the next stability for items that have lapsed (i.e., rated as AGAIN) based on the current difficulty, stability, and retrievability.
     *
     * @param difficulty The current difficulty of the item. (1.0 to 10.0)
     * @param stability The current stability of the item. (in days)
     * @param retrievability The current retrievability of the item. (0.0 to 1.0)
     * @return The next stability value, which indicates how long until the item should be reviewed again.
     */
    fun stabilityOnLapse(difficulty: Double, stability: Double, retrievability: Double): Double {
        val nextForgetStabilityLongTermParams =
            (w[11] * difficulty.pow(-w[12]) * ((stability + 1.0).pow(w[13]) - 1.0) * exp(w[14] * (1.0 - retrievability)))

        val nextForgetStabilityShortTermParams = stability / exp(w[17] * w[18])

        return min(nextForgetStabilityLongTermParams, nextForgetStabilityShortTermParams)
    }
}
