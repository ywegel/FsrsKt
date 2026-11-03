package one.felsen.fsrskt.reference

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Compares each formula of [one.felsen.fsrskt.fsrs6.Fsrs] against values computed by py-fsrs.
 * Every fixture case becomes its own test, named after its parameter set and input.
 */
class FormulaReferenceTest {

    @TestFactory
    fun initialStability(): List<DynamicTest> {
        val file = ReferenceFixtures.load<FormulaFile<RatingInput>>("formulas/initial-stability.json")

        return file.cases.map { case ->
            val name = "${case.params}, ${case.input.rating}"
            dynamicTest(name) {
                val actual = ReferenceFixtures.fsrs(case.params).initialStability(case.input.rating)
                assertMatchesReference(case.expected, actual, { "${file.kotlin}($name)" })
            }
        }
    }

    @TestFactory
    fun initialDifficulty(): List<DynamicTest> {
        val file = ReferenceFixtures.load<FormulaFile<RatingInput>>("formulas/initial-difficulty.json")

        return file.cases.map { case ->
            val name = "${case.params}, ${case.input.rating}"
            dynamicTest(name) {
                val actual = ReferenceFixtures.fsrs(case.params).initialDifficulty(case.input.rating)
                assertMatchesReference(case.expected, actual, { "${file.kotlin}($name)" })
            }
        }
    }
}
