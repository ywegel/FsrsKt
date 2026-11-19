package one.felsen.fsrskt.fsrs6

import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Tests what the py-fsrs fixtures do not cover: validation, immutability, the injected [Random], the day counting and
 * the rounding of the HARD step.
 */
class FsrsSchedulerTest {

    private val now = Instant.parse("2024-01-01T12:00:00Z")

    @Test
    fun desiredRetentionMustBeBetweenZeroAndOneExclusive() {
        listOf(0.0, 1.0, -0.1, 1.5, Double.NaN).forEach { desiredRetention ->
            assertFailsWith<IllegalArgumentException>("desiredRetention=$desiredRetention") {
                FsrsScheduler(desiredRetention = desiredRetention)
            }
        }
        FsrsScheduler(desiredRetention = 0.5)
    }

    @Test
    fun maximumIntervalMustBeAtLeastOne() {
        assertFailsWith<IllegalArgumentException> { FsrsScheduler(maximumInterval = 0) }
        assertFailsWith<IllegalArgumentException> { FsrsScheduler(maximumInterval = -1) }
        FsrsScheduler(maximumInterval = 1)
    }

    @Test
    fun stepsMustBeFiniteAndNotNegative() {
        listOf(-1.seconds, Duration.INFINITE, -Duration.INFINITE).forEach { step ->
            assertFailsWith<IllegalArgumentException>("learning step $step") {
                FsrsScheduler(learningSteps = listOf(1.minutes, step))
            }
            assertFailsWith<IllegalArgumentException>("relearning step $step") {
                FsrsScheduler(relearningSteps = listOf(step))
            }
        }
        FsrsScheduler(learningSteps = listOf(Duration.ZERO), relearningSteps = emptyList())
    }

    @Test
    fun invalidParametersAreRejected() {
        assertFailsWith<IllegalArgumentException> { FsrsScheduler(parameters = listOf(1.0)) }
    }

    @Test
    fun stepListsAreCopied() {
        val steps = mutableListOf(1.minutes)
        val scheduler = FsrsScheduler(learningSteps = steps, relearningSteps = steps)
        steps.add(10.minutes)

        assertEquals(listOf(1.minutes), scheduler.learningSteps)
        assertEquals(listOf(1.minutes), scheduler.relearningSteps)
    }

    @Test
    fun reviewDoesNotChangeTheCard() {
        val card = FsrsCard(due = now)
        val copy = card.copy()

        val result = FsrsScheduler(enableFuzzing = false).review(card, FsrsRating.GOOD, now)

        assertEquals(copy, card)
        assertNotEquals(card, result.card)
    }

    @Test
    fun logHoldsRatingTimeAndDuration() {
        val result = FsrsScheduler(enableFuzzing = false).review(FsrsCard(due = now), FsrsRating.HARD, now, 3.seconds)

        assertEquals(FsrsReviewLog(FsrsRating.HARD, now, 3.seconds), result.log)
        assertEquals(result.card.due - now, result.interval)
    }

    @Test
    fun injectedRandomMakesTheFuzzReproducible() {
        fun dueDates(seed: Int): List<Instant> {
            val scheduler = FsrsScheduler(learningSteps = emptyList(), random = Random(seed))
            var card = FsrsCard(due = now)
            return (1..8).map {
                card = scheduler.review(card, FsrsRating.EASY, card.due).card
                card.due
            }
        }

        assertEquals(dueDates(seed = 1), dueDates(seed = 1))
    }

    @Test
    fun fuzzUsesTheInjectedRandom() {
        // A long interval, so that the smallest and the largest u lead to different days.
        val card = FsrsCard(state = FsrsCardState.REVIEW, stability = 100.0, difficulty = 5.0, due = now, lastReview = now - 100.days)

        val low = FsrsScheduler(random = FixedRandom(0.0)).review(card, FsrsRating.GOOD, now)
        val high = FsrsScheduler(random = FixedRandom(0.999)).review(card, FsrsRating.GOOD, now)
        val range = FsrsFuzz.fuzzRange(
            FsrsScheduler(enableFuzzing = false).review(card, FsrsRating.GOOD, now).interval.inWholeDays.toInt(),
            36500,
        )

        assertEquals(range.first.toLong(), low.interval.inWholeDays)
        assertEquals(range.last + 1L, high.interval.inWholeDays)
    }

    @Test
    fun fuzzIsNotAppliedToLearningSteps() {
        val scheduler = FsrsScheduler(random = FailingRandom)

        val result = scheduler.review(FsrsCard(due = now), FsrsRating.GOOD, now)

        assertEquals(10.minutes, result.interval)
    }

    @Test
    fun wholeDaysCounterTreatsLateEveningAndEarlyMorningAsSameDay() {
        val card = reviewedAt(Instant.parse("2024-01-01T23:00:00Z"))
        val nextMorning = Instant.parse("2024-01-02T05:00:00Z")

        val wholeDays = FsrsScheduler(enableFuzzing = false).review(card, FsrsRating.GOOD, nextMorning).card
        val calendarDays = FsrsScheduler(
            enableFuzzing = false,
            dayCounter = FsrsDayCounter.calendarDays(dayCutoffHour = 4, timeZone = TimeZone.UTC),
        ).review(card, FsrsRating.GOOD, nextMorning).card

        val fsrs = Fsrs()
        assertEquals(fsrs.nextStabilityShortTerm(10.0, FsrsRating.GOOD), wholeDays.stability)
        assertEquals(
            fsrs.nextStability(5.0, 10.0, fsrs.retrievability(1.0, 10.0), FsrsRating.GOOD),
            calendarDays.stability,
        )
    }

    @Test
    fun retrievabilityUsesTheDayCounter() {
        val card = reviewedAt(Instant.parse("2024-01-01T23:00:00Z"))
        val nextMorning = Instant.parse("2024-01-02T05:00:00Z")
        val calendar = FsrsScheduler(dayCounter = FsrsDayCounter.calendarDays(timeZone = TimeZone.UTC))

        assertEquals(1.0, FsrsScheduler().retrievability(card, nextMorning))
        assertEquals(Fsrs().retrievability(1.0, 10.0), calendar.retrievability(card, nextMorning))
    }

    @Test
    fun retrievabilityIsZeroWithoutReviewOrStability() {
        val scheduler = FsrsScheduler()

        assertEquals(0.0, scheduler.retrievability(FsrsCard(due = now), now))
        assertEquals(0.0, scheduler.retrievability(FsrsCard(stability = 1.0, difficulty = 5.0, due = now), now))
        assertEquals(0.0, scheduler.retrievability(FsrsCard(due = now, lastReview = now), now))
    }

    @Test
    fun hardOnFirstOfOneStepIsOneAndAHalfSteps() {
        val result = learningCardRatedHard(listOf(10.minutes))

        assertEquals(15.minutes, result.interval)
    }

    @Test
    fun hardOnFirstOfTwoStepsIsTheMeanOfBoth() {
        val result = learningCardRatedHard(listOf(1.minutes, 10.minutes))

        assertEquals(5.5.minutes, result.interval)
    }

    @Test
    fun hardStepRoundsHalfNanosecondsToEven() {
        assertEquals(4.nanoseconds, learningCardRatedHard(listOf(3.nanoseconds)).interval) // 4.5
        assertEquals(8.nanoseconds, learningCardRatedHard(listOf(5.nanoseconds)).interval) // 7.5
        assertEquals(2.nanoseconds, learningCardRatedHard(listOf(1.nanoseconds, 2.nanoseconds)).interval) // 1.5
        assertEquals(2.nanoseconds, learningCardRatedHard(listOf(2.nanoseconds, 3.nanoseconds)).interval) // 2.5
    }

    @Test
    fun hardStepDoesNotOverflowForLongSteps() {
        assertEquals(54750.days, learningCardRatedHard(listOf(36500.days)).interval)
        assertEquals(54750.days, learningCardRatedHard(listOf(36500.days, 73000.days)).interval)
    }

    @Test
    fun learningCardWithoutStepIsOnStepZero() {
        val scheduler = FsrsScheduler(enableFuzzing = false)
        val card = FsrsCard(state = FsrsCardState.LEARNING, step = null, due = now)

        assertEquals(1, scheduler.review(card, FsrsRating.GOOD, now).card.step)
    }

    @Test
    fun reviewCardWithoutMemoryStateIsRejected() {
        val scheduler = FsrsScheduler()
        val card = FsrsCard(state = FsrsCardState.REVIEW, due = now)

        assertFailsWith<IllegalArgumentException> { scheduler.review(card, FsrsRating.GOOD, now) }
    }

    @Test
    fun relearningCardWithoutStepIsRejected() {
        val scheduler = FsrsScheduler()
        val card = FsrsCard(state = FsrsCardState.RELEARNING, step = null, stability = 1.0, difficulty = 5.0, due = now)

        assertFailsWith<IllegalArgumentException> { scheduler.review(card, FsrsRating.GOOD, now) }
    }

    @Test
    fun rescheduleWithoutReviewsIsANewCard() {
        val card = reviewedAt(now)

        assertEquals(FsrsCard(due = card.due), FsrsScheduler().reschedule(card, emptyList()))
    }

    @Test
    fun rescheduleSortsTheReviews() {
        val scheduler = FsrsScheduler(enableFuzzing = false)
        val reviews = listOf(
            FsrsReviewLog(FsrsRating.GOOD, now),
            FsrsReviewLog(FsrsRating.AGAIN, now + 2.days),
            FsrsReviewLog(FsrsRating.GOOD, now + 10.minutes),
            FsrsReviewLog(FsrsRating.EASY, now + 1.hours),
        )

        assertEquals(
            scheduler.reschedule(FsrsCard(due = now), reviews.sortedBy { it.reviewAt }),
            scheduler.reschedule(FsrsCard(due = now), reviews.shuffled(Random(3))),
        )
    }

    @Test
    fun nextIntervalUsesTheConfiguration() {
        val scheduler = FsrsScheduler(desiredRetention = 0.8, maximumInterval = 50)

        assertEquals(Fsrs().nextInterval(30.0, 0.8, 50), scheduler.nextInterval(30.0))
        assertEquals(50, scheduler.nextInterval(1000.0))
    }

    @Test
    fun replayOfNoReviewsIsNull() {
        assertNull(FsrsCalculator().replay(emptyList()))
    }

    @Test
    fun replayEqualsReschedule() {
        val reviews = listOf(
            FsrsReviewLog(FsrsRating.GOOD, now + 3.days),
            FsrsReviewLog(FsrsRating.AGAIN, now),
            FsrsReviewLog(FsrsRating.HARD, now + 5.minutes),
        )

        val card = FsrsScheduler(enableFuzzing = false).reschedule(FsrsCard(due = now), reviews)

        assertEquals(card.memoryState, FsrsCalculator().replay(reviews))
    }

    private fun reviewedAt(lastReview: Instant) = FsrsCard(
        state = FsrsCardState.REVIEW,
        stability = 10.0,
        difficulty = 5.0,
        due = lastReview + 10.days,
        lastReview = lastReview,
    )

    private fun learningCardRatedHard(steps: List<Duration>): FsrsReviewResult =
        FsrsScheduler(learningSteps = steps, enableFuzzing = false).review(FsrsCard(due = now), FsrsRating.HARD, now)

    private class FixedRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = error("Only nextDouble() is expected")
        override fun nextDouble(): Double = value
    }

    private object FailingRandom : Random() {
        override fun nextBits(bitCount: Int): Int = error("No random value expected")
    }
}
