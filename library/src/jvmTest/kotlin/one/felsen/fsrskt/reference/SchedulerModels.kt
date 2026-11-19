package one.felsen.fsrskt.reference

import kotlinx.serialization.Serializable
import one.felsen.fsrskt.fsrs6.FsrsCard
import one.felsen.fsrskt.fsrs6.FsrsCardState
import one.felsen.fsrskt.fsrs6.FsrsRating
import one.felsen.fsrskt.fsrs6.FsrsReviewLog
import one.felsen.fsrskt.fsrs6.FsrsScheduler
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A py-fsrs `Scheduler` configuration as pinned in the fixture headers. Steps are in seconds.
 */
@Serializable
data class SchedulerConfig(
    val params: String,
    val desiredRetention: Double,
    val learningStepsSeconds: List<Double>,
    val relearningStepsSeconds: List<Double>,
    val maximumInterval: Int,
    val enableFuzzing: Boolean,
) {

    fun scheduler(random: Random = Random.Default): FsrsScheduler = FsrsScheduler(
        parameters = ReferenceFixtures.parameterSets[params] ?: error("Unknown parameter set '$params'"),
        desiredRetention = desiredRetention,
        learningSteps = learningStepsSeconds.map { it.seconds },
        relearningSteps = relearningStepsSeconds.map { it.seconds },
        maximumInterval = maximumInterval,
        enableFuzzing = enableFuzzing,
        random = random,
    )
}

/**
 * A py-fsrs `Card` without `card_id`. Instants are ISO-8601 strings.
 */
@Serializable
data class CardJson(
    val state: FsrsCardState,
    val step: Int?,
    val stability: Double?,
    val difficulty: Double?,
    val due: String,
    val lastReview: String?,
) {

    fun toCard(): FsrsCard = FsrsCard(
        state = state,
        step = step,
        stability = stability,
        difficulty = difficulty,
        due = Instant.parse(due),
        lastReview = lastReview?.let(Instant::parse),
    )
}

@Serializable
data class SchedulerSequenceFile(
    val function: String,
    val description: String,
    val pyFsrs: String,
    val kotlin: String,
    val dayCounting: String,
    val configs: Map<String, SchedulerConfig>,
    val sequences: List<SchedulerSequence>,
)

@Serializable
data class SchedulerSequence(val id: String, val config: String, val steps: List<SchedulerStep>)

@Serializable
data class SchedulerStep(
    val reviewAt: String,
    val rating: FsrsRating,
    val fuzzU: Double?,
    val intervalDays: Int,
    val before: CardJson,
    val after: CardJson,
)

@Serializable
data class CardRetrievabilityFile(
    val function: String,
    val description: String,
    val pyFsrs: String,
    val kotlin: String,
    val dayCounting: String,
    val cases: List<FunctionCase<CardRetrievabilityInput, Double>>,
)

@Serializable
data class CardRetrievabilityInput(val stability: Double?, val lastReview: String?, val now: String)

@Serializable
data class RescheduleFile(
    val function: String,
    val description: String,
    val pyFsrs: String,
    val kotlin: String,
    val dayCounting: String,
    val configs: Map<String, SchedulerConfig>,
    val cases: List<RescheduleCase>,
)

@Serializable
data class RescheduleCase(
    val id: String,
    val config: String,
    val due: String,
    val logs: List<LogJson>,
    val expected: CardJson,
)

@Serializable
data class LogJson(val rating: FsrsRating, val reviewAt: String) {

    fun toLog(): FsrsReviewLog = FsrsReviewLog(rating, Instant.parse(reviewAt))
}

/**
 * Returns the queued values from [nextDouble] in order, like the patched `random()` of the fixtures.
 * Fails on any other draw and on a draw from an empty queue.
 */
class QueueRandom : Random() {

    private val values = ArrayDeque<Double>()

    val isEmpty: Boolean get() = values.isEmpty()

    fun enqueue(value: Double) {
        values.addLast(value)
    }

    override fun nextDouble(): Double =
        values.removeFirstOrNull() ?: throw AssertionError("FsrsKt drew a random value, but py-fsrs did not")

    override fun nextBits(bitCount: Int): Int = throw AssertionError("Only nextDouble() is expected")
}

/**
 * Asserts that [actual] matches the py-fsrs card [expected]: phase, step and instants exactly, memory state up to
 * the tolerance of [assertMatchesReference].
 */
fun assertMatchesReference(expected: CardJson, actual: FsrsCard, message: () -> String) {
    val expectedCard = expected.toCard()
    fun fail(field: String, expectedValue: Any?, actualValue: Any?): Nothing =
        kotlin.test.fail("${message()}, $field: expected <$expectedValue> (py-fsrs) but was <$actualValue> (FsrsKt)")

    if (expectedCard.state != actual.state) fail("state", expectedCard.state, actual.state)
    if (expectedCard.step != actual.step) fail("step", expectedCard.step, actual.step)
    if (expectedCard.due != actual.due) fail("due", expectedCard.due, actual.due)
    if (expectedCard.lastReview != actual.lastReview) fail("lastReview", expectedCard.lastReview, actual.lastReview)
    assertMatchesNullableReference(expected.stability, actual.stability, { "${message()}, stability" })
    assertMatchesNullableReference(expected.difficulty, actual.difficulty, { "${message()}, difficulty" })
}

/**
 * Like [assertMatchesReference], but both values may be `null`, which must then match exactly.
 */
fun assertMatchesNullableReference(expected: Double?, actual: Double?, message: () -> String) {
    if (expected == null || actual == null) {
        if (expected != actual) kotlin.test.fail("${message()}: expected <$expected> (py-fsrs) but was <$actual> (FsrsKt)")
        return
    }
    assertMatchesReference(expected, actual, message)
}
