package one.felsen.fsrskt.reference

import one.felsen.fsrskt.fsrs6.FsrsCard
import one.felsen.fsrskt.fsrs6.FsrsScheduler
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Compares [FsrsScheduler.review] against review sequences computed by py-fsrs, with learning steps and fuzz.
 * Every review sequence becomes its own test in both factories. The fuzz gets the same random values as py-fsrs.
 */
class SchedulerReviewSequenceReferenceTest {

    private val file by lazy { ReferenceFixtures.load<SchedulerSequenceFile>("scheduler/review-sequences.json") }

    /**
     * Starts every step from the py-fsrs card, so that a failure points to the step that causes a deviation.
     */
    @TestFactory
    fun everyStepFromReferenceState(): List<DynamicTest> = file.sequences.map { sequence ->
        dynamicTest("${sequence.id}, ${sequence.config}") {
            val random = QueueRandom()
            val scheduler = file.configs.getValue(sequence.config).scheduler(random)
            sequence.steps.forEachIndexed { index, step ->
                step.fuzzU?.let(random::enqueue)
                val result = scheduler.review(step.before.toCard(), step.rating, Instant.parse(step.reviewAt))
                val message = { describe(sequence, index, step) }

                assertMatchesReference(step.after, result.card, message)
                assertEquals(step.intervalDays.toLong(), result.interval.inWholeDays, "${message()}, interval days")
                assertTrue(random.isEmpty, "${message()}: py-fsrs drew ${step.fuzzU} for the fuzz, FsrsKt did not")
            }
        }
    }

    /**
     * Continues with the FsrsKt card after every step, so that deviations add up as they do in real use.
     */
    @TestFactory
    fun wholeSequenceWithOwnState(): List<DynamicTest> = file.sequences.map { sequence ->
        dynamicTest("${sequence.id}, ${sequence.config}") {
            val random = QueueRandom()
            sequence.steps.forEach { step -> step.fuzzU?.let(random::enqueue) }
            val scheduler = file.configs.getValue(sequence.config).scheduler(random)

            val actual = sequence.steps.fold(sequence.steps.first().before.toCard()) { card: FsrsCard, step ->
                scheduler.review(card, step.rating, Instant.parse(step.reviewAt)).card
            }

            val message = { "${file.kotlin}(${sequence.id}, ${sequence.config}, end of ${sequence.steps.size} steps)" }
            assertMatchesReference(sequence.steps.last().after, actual, message)
            assertTrue(random.isEmpty, "${message()}: FsrsKt drew fewer random values than py-fsrs")
        }
    }

    private fun describe(sequence: SchedulerSequence, index: Int, step: SchedulerStep): String =
        "${file.kotlin}(${sequence.id}, ${sequence.config}, step ${index + 1}/${sequence.steps.size}, " +
            "reviewAt=${step.reviewAt}, before=${step.before}, ${step.rating}, fuzzU=${step.fuzzU})"
}
