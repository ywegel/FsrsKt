package one.felsen.fsrskt.fsrs6

import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests [Fsrs.interval] and [Fsrs.nextInterval], above all the half-to-even rounding of Python's `round()`.
 */
class FsrsIntervalTest {

    private val fsrs = Fsrs()

    @Test
    fun intervalEqualsStabilityAtNinetyPercent() {
        assertEquals(42.0, fsrs.interval(42.0, 0.9), absoluteTolerance = 1e-9)
    }

    @Test
    fun higherRetentionGivesShorterInterval() {
        assertTrue(fsrs.interval(10.0, 0.95) < fsrs.interval(10.0, 0.9))
        assertTrue(fsrs.interval(10.0, 0.8) > fsrs.interval(10.0, 0.9))
    }

    @Test
    fun intervalIsNotRoundedOrBounded() {
        assertEquals(0.001, fsrs.interval(0.001, 0.9), absoluteTolerance = 1e-12)
        assertEquals(100000.0, fsrs.interval(100000.0, 0.9), absoluteTolerance = 1e-6)
    }

    @Test
    fun halfDaysRoundToEven() {
        // Unrounded interval to rounded interval, as Python's round() does it.
        // 0.5 is missing, as no double stability gives exactly 0.5 with the default parameters.
        val expected = mapOf(1.5 to 2, 2.5 to 2, 3.5 to 4, 4.5 to 4, 10.5 to 10, 11.5 to 12)
        expected.forEach { (unrounded, rounded) ->
            val stability = assertNotNull(stabilityWithInterval(unrounded), "no stability with interval $unrounded")
            assertEquals(rounded, fsrs.nextInterval(stability, 0.9, 36500), "interval $unrounded")
        }
    }

    @Test
    fun nextIntervalIsAtLeastOneDay() {
        assertEquals(1, fsrs.nextInterval(0.001, 0.9, 36500))
    }

    @Test
    fun nextIntervalIsAtMostMaximumInterval() {
        assertEquals(7, fsrs.nextInterval(1000.0, 0.9, 7))
        assertEquals(1, fsrs.nextInterval(1000.0, 0.9, 1))
    }

    @Test
    fun desiredRetentionMustBeBetweenZeroAndOneExclusive() {
        listOf(0.0, 1.0, -0.5, 2.0, Double.NaN).forEach { desiredRetention ->
            assertFailsWith<IllegalArgumentException>("$desiredRetention") { fsrs.interval(1.0, desiredRetention) }
            assertFailsWith<IllegalArgumentException>("$desiredRetention") { fsrs.nextInterval(1.0, desiredRetention, 10) }
        }
    }

    @Test
    fun maximumIntervalMustBeAtLeastOne() {
        assertFailsWith<IllegalArgumentException> { fsrs.nextInterval(1.0, 0.9, 0) }
    }

    @Test
    fun decayAndFactorMatchTheForgettingCurve() {
        assertEquals(-FsrsParameters.defaultParameters[20], fsrs.decay)
        // By definition of the factor, the retrievability is 0.9 when the elapsed days equal the stability.
        assertEquals(0.9, fsrs.retrievability(7.0, 7.0), absoluteTolerance = 1e-12)
        assertEquals(0.9, kotlin.math.exp(fsrs.decay * kotlin.math.ln(1.0 + fsrs.factor)), absoluteTolerance = 1e-12)
    }

    /**
     * Searches the doubles around the exact solution for a stability whose unrounded interval is exactly [interval].
     */
    private fun stabilityWithInterval(interval: Double): Double? {
        var up = interval
        var down = interval
        repeat(64) {
            if (fsrs.interval(up, 0.9) == interval) return up
            if (fsrs.interval(down, 0.9) == interval) return down
            up = up.nextUp()
            down = down.nextDown()
        }
        return null
    }
}
