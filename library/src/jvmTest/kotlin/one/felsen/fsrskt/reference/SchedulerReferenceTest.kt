package one.felsen.fsrskt.reference

import one.felsen.fsrskt.fsrs6.FsrsCalculator
import one.felsen.fsrskt.fsrs6.FsrsCard
import one.felsen.fsrskt.fsrs6.FsrsCardState
import one.felsen.fsrskt.fsrs6.FsrsScheduler
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.time.Instant

/**
 * Compares [FsrsScheduler.retrievability], [FsrsScheduler.reschedule] and
 * [one.felsen.fsrskt.fsrs6.FsrsCalculator.replay] against values computed by py-fsrs.
 * Every fixture case becomes its own test.
 */
class SchedulerReferenceTest {

    @TestFactory
    fun retrievability(): List<DynamicTest> {
        val file = ReferenceFixtures.load<CardRetrievabilityFile>("scheduler/retrievability.json")

        return file.cases.map { case ->
            val name = "${case.params}, S=${case.input.stability}, lastReview=${case.input.lastReview}, " +
                "now=${case.input.now}"
            dynamicTest(name) {
                val scheduler = FsrsScheduler(
                    parameters = ReferenceFixtures.parameterSets.getValue(checkNotNull(case.params)),
                    enableFuzzing = false,
                )
                val card = FsrsCard(
                    state = FsrsCardState.REVIEW,
                    stability = case.input.stability,
                    difficulty = 5.0,
                    due = Instant.parse("2024-01-01T00:00:00Z"),
                    lastReview = case.input.lastReview?.let(Instant::parse),
                )
                val actual = scheduler.retrievability(card, Instant.parse(case.input.now))
                assertMatchesReference(case.expected, actual, { "${file.kotlin}($name)" })
            }
        }
    }

    @TestFactory
    fun reschedule(): List<DynamicTest> = rescheduleFile.cases.map { case ->
        dynamicTest("${case.id}, ${case.logs.size} logs") {
            val scheduler = rescheduleFile.configs.getValue(case.config).scheduler()
            val actual = scheduler.reschedule(
                FsrsCard(due = Instant.parse(case.due)),
                case.logs.map(LogJson::toLog),
            )
            assertMatchesReference(case.expected, actual) { "FsrsScheduler.reschedule(${case.id})" }
        }
    }

    /**
     * The memory state does not depend on the learning steps, so the calculator replays to the same state.
     */
    @TestFactory
    fun replay(): List<DynamicTest> = rescheduleFile.cases.map { case ->
        dynamicTest("${case.id}, ${case.logs.size} logs") {
            val config = rescheduleFile.configs.getValue(case.config)
            val actual = FsrsCalculator(ReferenceFixtures.fsrs(config.params)).replay(case.logs.map(LogJson::toLog))
            val message = { "FsrsCalculator.replay(${case.id})" }
            assertMatchesNullableReference(case.expected.stability, actual?.stability, { "${message()}, stability" })
            assertMatchesNullableReference(case.expected.difficulty, actual?.difficulty, { "${message()}, difficulty" })
        }
    }

    private val rescheduleFile by lazy { ReferenceFixtures.load<RescheduleFile>("scheduler/reschedule.json") }
}
