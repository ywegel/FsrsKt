package one.felsen.fsrskt.fsrs6

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests [FsrsFuzz] beyond the py-fsrs fixtures: the injected [Random] and the validation.
 */
class FsrsFuzzTest {

    @Test
    fun shortIntervalsAreNotFuzzedAndDrawNothing() {
        listOf(0, 1, 2).forEach { days ->
            assertEquals(days, FsrsFuzz.fuzzedInterval(days, 36500, NoRandom))
            assertEquals(days, FsrsFuzz.fuzzedInterval(days, 36500, u = 0.999))
        }
    }

    @Test
    fun fuzzedIntervalStaysNearTheRange() {
        val random = Random(42)
        repeat(1000) {
            val days = random.nextInt(3, 1000)
            val range = FsrsFuzz.fuzzRange(days, 36500)
            val fuzzed = FsrsFuzz.fuzzedInterval(days, 36500, random)
            // Like py-fsrs, one day above the range is possible.
            assertTrue(fuzzed in range.first..range.last + 1, "$days fuzzed to $fuzzed, range $range")
        }
    }

    @Test
    fun fuzzedIntervalIsAtMostMaximumInterval() {
        assertEquals(10, FsrsFuzz.fuzzedInterval(10, 10, u = 0.999))
    }

    @Test
    fun seededRandomIsReproducible() {
        val first = (3..200).map { FsrsFuzz.fuzzedInterval(it, 36500, Random(7)) }
        val second = (3..200).map { FsrsFuzz.fuzzedInterval(it, 36500, Random(7)) }

        assertEquals(first, second)
    }

    @Test
    fun uMustBeInZeroToOne() {
        listOf(-0.1, 1.0, 1.5, Double.NaN).forEach { u ->
            assertFailsWith<IllegalArgumentException>("u=$u") { FsrsFuzz.fuzzedInterval(10, 100, u) }
        }
    }

    @Test
    fun maximumIntervalMustBeAtLeastOne() {
        assertFailsWith<IllegalArgumentException> { FsrsFuzz.fuzzRange(10, 0) }
        assertFailsWith<IllegalArgumentException> { FsrsFuzz.fuzzedInterval(10, 0, 0.5) }
        assertFailsWith<IllegalArgumentException> { FsrsFuzz.fuzzedInterval(10, 0, Random(1)) }
    }

    private object NoRandom : Random() {
        override fun nextBits(bitCount: Int): Int = error("No random value expected")
    }
}
