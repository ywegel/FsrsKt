package one.felsen.fsrskt.reference

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import one.felsen.fsrskt.fsrs6.Fsrs
import one.felsen.fsrskt.fsrs6.FsrsRating
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.fail

/**
 * Reference fixtures generated from py-fsrs by `tools/fixtures/generate_fixtures.py`.
 */
object ReferenceFixtures {

    private const val RESOURCE_DIR = "/py-fsrs-6.3.2"

    /** Parameter sets by name, e.g. "default" or "random-03". */
    val parameterSets: Map<String, List<Double>> by lazy {
        load<ParameterSetsFile>("parameter-sets.json").parameterSets.associate { it.name to it.w }
    }

    fun fsrs(params: String): Fsrs =
        Fsrs(parameterSets[params] ?: error("Unknown parameter set '$params'"))

    inline fun <reified T> load(path: String): T = Json.decodeFromString<T>(readResource(path))

    fun readResource(path: String): String =
        ReferenceFixtures::class.java.getResource("$RESOURCE_DIR/$path")?.readText()
            ?: error("Fixture '$RESOURCE_DIR/$path' not found. Run tools/fixtures/generate_fixtures.py")
}

@Serializable
data class ParameterSetsFile(val pyFsrsVersion: String, val seed: Long, val parameterSets: List<ParameterSet>)

@Serializable
data class ParameterSet(val name: String, val w: List<Double>)

@Serializable
data class FormulaFile<I>(
    val function: String,
    val description: String,
    val pyFsrs: String,
    val kotlin: String,
    val cases: List<FormulaCase<I>>,
)

@Serializable
data class FormulaCase<I>(val params: String, val input: I, val expected: Double)

@Serializable
data class RatingInput(val rating: FsrsRating)

@Serializable
data class RetrievabilityInput(val elapsedDays: Int, val stability: Double)

@Serializable
data class NextDifficultyInput(val difficulty: Double, val rating: FsrsRating)

/**
 * Asserts that [actual] matches the py-fsrs value [expected] up to a relative tolerance,
 * with an absolute floor so that values near zero are compared sensibly.
 */
fun assertMatchesReference(expected: Double, actual: Double, message: () -> String, tolerance: Double = 1e-9) {
    if (abs(expected - actual) > tolerance * max(1.0, abs(expected))) {
        fail("${message()}: expected <$expected> (py-fsrs) but was <$actual> (FsrsKt)")
    }
}
