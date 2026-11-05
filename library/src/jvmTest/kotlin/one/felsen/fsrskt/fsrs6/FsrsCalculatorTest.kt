package one.felsen.fsrskt.fsrs6

import kotlinx.datetime.TimeZone
import one.felsen.fsrskt.helper.DateTimeHelper.elapsedCalendarDays
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Tests the formula selection of [FsrsCalculator.review].
 */
class FsrsCalculatorTest {

    private val fsrs = Fsrs()
    private val calculator = FsrsCalculator(fsrs)
    private val state = FsrsState(difficulty = 5.0, stability = 10.0)

    @Test
    fun newCardIgnoresElapsedDaysAndSameDay() {
        val actual = calculator.review(null, FsrsRating.GOOD, elapsedDays = 5.0, sameDay = true)

        assertEquals(fsrs.initialDifficulty(FsrsRating.GOOD), actual.difficulty)
        assertEquals(fsrs.initialStability(FsrsRating.GOOD), actual.stability)
    }

    @Test
    fun sameDayUsesShortTermStabilityRegardlessOfElapsedDays() {
        val actual = calculator.review(state, FsrsRating.GOOD, elapsedDays = 3.0, sameDay = true)

        assertEquals(fsrs.nextStabilityShortTerm(state.stability, FsrsRating.GOOD), actual.stability)
    }

    @Test
    fun otherDayUsesStabilityOnRecallWithFractionalElapsedDays() {
        val actual = calculator.review(state, FsrsRating.GOOD, elapsedDays = 0.5, sameDay = false)

        val retrievability = fsrs.retrievability(0.5, state.stability)
        assertEquals(
            fsrs.stabilityOnRecall(state.difficulty, state.stability, retrievability, FsrsRating.GOOD),
            actual.stability,
        )
    }

    /**
     * Follows the review process of the README. The expected values were computed once with
     * py-fsrs 6.3.2 and `Scheduler(enable_fuzzing=False, learning_steps=(), relearning_steps=())`.
     */
    @Test
    fun readmeReviewProcessMatchesPyFsrs() {
        val firstReview = Instant.parse("2024-06-01T12:00:00Z")
        val secondReview = firstReview + 3.days

        val first = reviewAsInReadme(null, null, FsrsRating.GOOD, firstReview)
        val second = reviewAsInReadme(first, firstReview, FsrsRating.GOOD, secondReview)

        assertEquals(2.111214235785395, second.difficulty, absoluteTolerance = 1e-9)
        assertEquals(13.826903694354568, second.stability, absoluteTolerance = 1e-9)
    }

    private fun reviewAsInReadme(state: FsrsState?, lastReview: Instant?, rating: FsrsRating, now: Instant): FsrsState {
        val elapsedDays = lastReview?.elapsedCalendarDays(now, timeZone = TimeZone.UTC) ?: 0.0
        return calculator.review(state, rating, elapsedDays, sameDay = elapsedDays < 1)
    }
}
