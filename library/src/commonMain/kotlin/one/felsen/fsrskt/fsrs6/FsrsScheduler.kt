package one.felsen.fsrskt.fsrs6

import kotlin.random.Random
import kotlin.time.Duration
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
        // TODO validation
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
    ): FsrsReviewResult = TODO()

    /**
     * The interval of a card in review, without fuzz, like py-fsrs `Scheduler._next_interval`.
     *
     * @param stability The stability of the card. (in days)
     * @return The interval. (in whole days, 1 to [maximumInterval])
     * @see Fsrs.nextInterval
     */
    fun nextInterval(stability: Double): Int = TODO()

    /**
     * The probability of recalling [card] at [now], like py-fsrs `Scheduler.get_card_retrievability`.
     *
     * @param card The card.
     * @param now The time of the recall.
     * @return The retrievability (0.0 to 1.0) after the days counted by [dayCounter] since [FsrsCard.lastReview], at
     * least 0 days. 0.0 if the card has no [FsrsCard.lastReview] or [FsrsCard.stability].
     */
    fun retrievability(card: FsrsCard, now: Instant): Double = TODO()

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
    fun reschedule(card: FsrsCard, reviews: Iterable<FsrsReviewLog>): FsrsCard = TODO()
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
        get() = TODO()
}
