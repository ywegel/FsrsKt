package one.felsen.fsrskt.reference

import one.felsen.fsrskt.fsrs6.FsrsFuzz
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals

/**
 * Compares [FsrsFuzz] against values computed by py-fsrs with a patched `random()`.
 * Every fixture case becomes its own test, named after its input.
 */
class FuzzReferenceTest {

    @TestFactory
    fun fuzzRange(): List<DynamicTest> {
        val file = ReferenceFixtures.load<FunctionFile<FuzzRangeInput, FuzzRangeExpected>>("formulas/fuzz-range.json")

        return file.cases.map { case ->
            val name = "days=${case.input.intervalDays}, max=${case.input.maximumInterval}"
            dynamicTest(name) {
                val actual = FsrsFuzz.fuzzRange(case.input.intervalDays, case.input.maximumInterval)
                assertEquals(case.expected.first..case.expected.last, actual, "${file.kotlin}($name)")
            }
        }
    }

    @TestFactory
    fun fuzzedInterval(): List<DynamicTest> = fuzzedIntervalFile.cases.map { case ->
        val name = name(case.input)
        dynamicTest(name) {
            val actual = FsrsFuzz.fuzzedInterval(case.input.intervalDays, case.input.maximumInterval, case.input.u)
            assertEquals(case.expected.intervalDays, actual, "${fuzzedIntervalFile.kotlin}($name)")
        }
    }

    /**
     * The [kotlin.random.Random] variant returns the same and draws a value exactly when py-fsrs calls `random()`.
     */
    @TestFactory
    fun fuzzedIntervalWithRandom(): List<DynamicTest> = fuzzedIntervalFile.cases.map { case ->
        val name = name(case.input)
        dynamicTest(name) {
            val random = QueueRandom().apply { enqueue(case.input.u) }
            val actual = FsrsFuzz.fuzzedInterval(case.input.intervalDays, case.input.maximumInterval, random)
            assertEquals(case.expected.intervalDays, actual, "${fuzzedIntervalFile.kotlin}($name, random)")
            assertEquals(case.expected.drawsRandom, random.isEmpty, "${fuzzedIntervalFile.kotlin}($name), draws random")
        }
    }

    private val fuzzedIntervalFile by lazy {
        ReferenceFixtures.load<FunctionFile<FuzzedIntervalInput, FuzzedIntervalExpected>>("formulas/fuzzed-interval.json")
    }

    private fun name(input: FuzzedIntervalInput) =
        "days=${input.intervalDays}, max=${input.maximumInterval}, u=${input.u}"
}
