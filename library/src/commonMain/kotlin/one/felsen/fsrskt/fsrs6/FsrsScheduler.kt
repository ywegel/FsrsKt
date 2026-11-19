package one.felsen.fsrskt.fsrs6

import kotlin.math.max
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Schedules [FsrsCard]s with learning steps, intervals from a desired retention and fuzz. A port of py-fsrs
 * `Scheduler`, without the optimizer, serialization and card ids.
 *
 * The scheduler never reads the clock: the time of a review is always passed in. It is immutable and can be shared,
 * as long as [random] can be shared.
 *
 * Deviations from py-fsrs:
 * - The configuration is validated (see the exceptions below).
 * - [dayCounter] makes the counting of elapsed days configurable. The default counts like py-fsrs.
 * - Reviewing a card without memory state outside [FsrsCardState.LEARNING], or in
 *   [FsrsCardState.RELEARNING] without step, throws an [IllegalArgumentException] instead of failing an `assert`.
 *   A card in [FsrsCardState.LEARNING] without step is treated as step 0, as the py-fsrs `Card` constructor does.
 *
 * @property parameters The 21 FSRS-6 parameters, validated by [Fsrs].
 * @property desiredRetention The retrievability at which a card in review is due. (exclusive 0.0 to exclusive 1.0)
 * @property learningSteps The waits between the reviews of a new card, before it moves to [FsrsCardState.REVIEW].
 * Empty to move it there after the first review. Each at least [Duration.ZERO] and finite.
 * @property relearningSteps The waits between the reviews of a forgotten card, before it moves back to
 * [FsrsCardState.REVIEW]. Empty to keep a forgotten card in review with an interval in days. Each at least
 * [Duration.ZERO] and finite.
 * @property maximumInterval The longest interval of a card in review. (in days, at least 1)
 * @property enableFuzzing Whether to fuzz the intervals of cards in review with [FsrsFuzz].
 * @property dayCounter How to count the days between two reviews. Defaults to [FsrsDayCounter.WholeDays], like
 * py-fsrs.
 * @property random The source of the fuzz. Only used if [enableFuzzing] is true.
 * @throws IllegalArgumentException If any of the properties is out of its range.
 */
class FsrsScheduler(
    val parameters: List<Double> = FsrsParameters.defaultParameters,
    val desiredRetention: Double = 0.9,
    learningSteps: List<Duration> = listOf(1.minutes, 10.minutes),
    relearningSteps: List<Duration> = listOf(10.minutes),
    val maximumInterval: Int = 36500,
    val enableFuzzing: Boolean = true,
    val dayCounter: FsrsDayCounter = FsrsDayCounter.WholeDays,
    val random: Random = Random.Default,
) {

    val learningSteps: List<Duration> = learningSteps.toList()

    val relearningSteps: List<Duration> = relearningSteps.toList()

    /**
     * The formulas with [parameters].
     */
    val fsrs: Fsrs = Fsrs(parameters)

    /**
     * The calculator for the memory state, using [fsrs].
     */
    val calculator: FsrsCalculator = FsrsCalculator(fsrs)

    init {
        requireDesiredRetention(desiredRetention)
        requireMaximumInterval(maximumInterval)
        requireSteps("learningSteps", this.learningSteps)
        requireSteps("relearningSteps", this.relearningSteps)
    }

    /**
     * Reviews [card] with [rating] at [reviewAt]. A 1:1 port of py-fsrs `Scheduler.review_card`.
     *
     * Updates the memory state with [FsrsCalculator.review], moves the card through its phases and steps, and
     * schedules it: after a learning or relearning step, or after [nextInterval] whole days (fuzzed, if enabled) in
     * [FsrsCardState.REVIEW].
     *
     * The interval for HARD on step 0 is 1.5 times the step if there is one step, and the mean of the first two steps
     * otherwise. py-fsrs rounds these to whole microseconds; FsrsKt rounds them to whole nanoseconds, half to even.
     * Both are the same for steps in whole seconds.
     *
     * @param card The card before the review. Not changed.
     * @param rating The rating given in the review.
     * @param reviewAt When the review happened.
     * @param reviewDuration How long the review took, only stored in the log.
     * @return The card after the review and the log of the review.
     * @throws IllegalArgumentException If the card has no memory state outside [FsrsCardState.LEARNING], or no step in
     * [FsrsCardState.RELEARNING].
     */
    fun review(
        card: FsrsCard,
        rating: FsrsRating,
        reviewAt: Instant,
        reviewDuration: Duration? = null,
    ): FsrsReviewResult {
        val elapsedDays = card.lastReview?.let { dayCounter.elapsedDays(it, reviewAt).toDouble() }

        val memoryState = when (card.state) {
            // A new card gets its initial state, also if only one of stability and difficulty is unknown.
            FsrsCardState.LEARNING -> calculator.review(card.memoryState, rating, elapsedDays)
            FsrsCardState.REVIEW, FsrsCardState.RELEARNING -> {
                val state = requireNotNull(card.memoryState) {
                    "A card in ${card.state} needs stability and difficulty, but was $card."
                }
                calculator.review(state, rating, elapsedDays)
            }
        }

        val scheduled = when (card.state) {
            FsrsCardState.LEARNING -> scheduleSteps(card.step ?: 0, learningSteps, FsrsCardState.LEARNING, rating, memoryState)
            FsrsCardState.REVIEW -> scheduleReview(card.step, rating, memoryState)
            FsrsCardState.RELEARNING -> {
                val step = requireNotNull(card.step) { "A card in RELEARNING needs a step, but was $card." }
                scheduleSteps(step, relearningSteps, FsrsCardState.RELEARNING, rating, memoryState)
            }
        }

        val interval = if (enableFuzzing && scheduled.state == FsrsCardState.REVIEW) {
            FsrsFuzz.fuzzedInterval(scheduled.interval.inWholeDays.toInt(), maximumInterval, random).days
        } else {
            scheduled.interval
        }

        val reviewed = FsrsCard(
            state = scheduled.state,
            step = scheduled.step,
            stability = memoryState.stability,
            difficulty = memoryState.difficulty,
            due = reviewAt + interval,
            lastReview = reviewAt,
        )
        return FsrsReviewResult(reviewed, FsrsReviewLog(rating, reviewAt, reviewDuration))
    }

    /**
     * The phase, step and interval after a review, before fuzz.
     */
    private class Scheduled(val state: FsrsCardState, val step: Int?, val interval: Duration)

    private fun reviewInterval(memoryState: FsrsState) = Scheduled(FsrsCardState.REVIEW, null, nextInterval(memoryState.stability).days)

    /**
     * The learning and relearning phases, which only differ in their steps.
     */
    private fun scheduleSteps(
        step: Int,
        steps: List<Duration>,
        state: FsrsCardState,
        rating: FsrsRating,
        memoryState: FsrsState,
    ): Scheduled {
        // The step can be beyond the steps if the card was scheduled before with more steps than this scheduler has.
        if (steps.isEmpty() || (step >= steps.size && rating != FsrsRating.AGAIN)) {
            return reviewInterval(memoryState)
        }

        return when (rating) {
            FsrsRating.AGAIN -> Scheduled(state, 0, steps[0])
            FsrsRating.HARD -> {
                val interval = when {
                    step == 0 && steps.size == 1 -> oneAndAHalf(steps[0])
                    step == 0 -> mean(steps[0], steps[1])
                    else -> steps[step]
                }
                Scheduled(state, step, interval)
            }
            FsrsRating.GOOD -> if (step + 1 == steps.size) {
                reviewInterval(memoryState)
            } else {
                Scheduled(state, step + 1, steps[step + 1])
            }
            FsrsRating.EASY -> reviewInterval(memoryState)
        }
    }

    /**
     * The review phase. The step is kept as it is, as py-fsrs does.
     */
    private fun scheduleReview(step: Int?, rating: FsrsRating, memoryState: FsrsState): Scheduled =
        if (rating == FsrsRating.AGAIN && relearningSteps.isNotEmpty()) {
            Scheduled(FsrsCardState.RELEARNING, 0, relearningSteps[0])
        } else {
            Scheduled(FsrsCardState.REVIEW, step, nextInterval(memoryState.stability).days)
        }

    /**
     * The interval of a card in review, without fuzz, like py-fsrs `Scheduler._next_interval`.
     *
     * @param stability The stability of the card. (in days)
     * @return The interval. (in whole days, 1 to [maximumInterval])
     * @see Fsrs.nextInterval
     */
    fun nextInterval(stability: Double): Int = fsrs.nextInterval(stability, desiredRetention, maximumInterval)

    /**
     * The probability of recalling [card] at [now], like py-fsrs `Scheduler.get_card_retrievability`.
     *
     * @param card The card.
     * @param now The time of the recall.
     * @return The retrievability (0.0 to 1.0) after the days counted by [dayCounter] since [FsrsCard.lastReview], at
     * least 0 days. 0.0 if the card has no [FsrsCard.lastReview] or [FsrsCard.stability].
     */
    fun retrievability(card: FsrsCard, now: Instant): Double {
        val lastReview = card.lastReview ?: return 0.0
        val stability = card.stability ?: return 0.0
        return fsrs.retrievability(max(0L, dayCounter.elapsedDays(lastReview, now)).toDouble(), stability)
    }

    /**
     * Recomputes [card] from its [reviews] with this scheduler, like py-fsrs `Scheduler.reschedule_card`.
     *
     * Starts from a new card due at [FsrsCard.due] of [card] and reviews it with every log, sorted by
     * [FsrsReviewLog.reviewAt] (stable, so reviews at the same instant keep their order). Use it after changing the
     * parameters, the desired retention or the steps. Fuzz is drawn again, if enabled.
     *
     * Unlike py-fsrs, the logs are not checked to belong to the card, as there are no card ids.
     *
     * @param card The card to recompute. Only [FsrsCard.due] is used.
     * @param reviews All reviews of the card, in any order.
     * @return The recomputed card. A new card due at [FsrsCard.due] of [card], if [reviews] is empty.
     */
    fun reschedule(card: FsrsCard, reviews: Iterable<FsrsReviewLog>): FsrsCard =
        reviews.sortedBy { it.reviewAt }.fold(FsrsCard(due = card.due)) { rescheduled, review ->
            review(rescheduled, review.rating, review.reviewAt).card
        }

    private companion object {

        /** Up to this, the exact nanosecond arithmetic below cannot overflow. About 97 years. */
        const val EXACT_NANOSECONDS_LIMIT = Long.MAX_VALUE / 3

        fun requireSteps(name: String, steps: List<Duration>) {
            steps.forEachIndexed { index, step ->
                require(step.isFinite() && !step.isNegative()) {
                    "$name[$index] must be finite and at least zero, but was $step."
                }
            }
        }

        /** [step] times 1.5, rounded to whole nanoseconds, half to even. py-fsrs rounds to microseconds. */
        fun oneAndAHalf(step: Duration): Duration {
            val nanoseconds = step.inWholeNanoseconds
            if (nanoseconds > EXACT_NANOSECONDS_LIMIT) return step * 1.5
            return halfToEven(3 * nanoseconds).nanoseconds
        }

        /** The mean of [a] and [b], rounded to whole nanoseconds, half to even. py-fsrs rounds to microseconds. */
        fun mean(a: Duration, b: Duration): Duration {
            val first = a.inWholeNanoseconds
            val second = b.inWholeNanoseconds
            if (first > EXACT_NANOSECONDS_LIMIT || second > EXACT_NANOSECONDS_LIMIT) return (a + b) / 2
            return halfToEven(first + second).nanoseconds
        }

        /** [value] / 2 for a non-negative [value], with halves rounded to the even number. */
        fun halfToEven(value: Long): Long {
            val half = value / 2
            return if (value % 2 == 1L && half % 2 == 1L) half + 1 else half
        }
    }
}

/**
 * The result of [FsrsScheduler.review].
 *
 * @property card The card after the review.
 * @property log The log of the review.
 */
data class FsrsReviewResult(val card: FsrsCard, val log: FsrsReviewLog) {

    /**
     * The time from the review until the card is due again.
     */
    val interval: Duration
        get() = card.due - log.reviewAt
}
