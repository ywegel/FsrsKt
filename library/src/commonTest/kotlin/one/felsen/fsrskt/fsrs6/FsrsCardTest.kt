package one.felsen.fsrskt.fsrs6

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class FsrsCardTest {

    private val now = Instant.parse("2024-01-01T00:00:00Z")

    @Test
    fun newCardIsLearningOnStepZero() {
        val card = FsrsCard(due = now)

        assertEquals(FsrsCardState.LEARNING, card.state)
        assertEquals(0, card.step)
        assertNull(card.stability)
        assertNull(card.difficulty)
        assertNull(card.lastReview)
    }

    @Test
    fun cardOutsideLearningHasNoStepByDefault() {
        assertNull(FsrsCard(state = FsrsCardState.REVIEW, due = now).step)
        assertNull(FsrsCard(state = FsrsCardState.RELEARNING, due = now).step)
    }

    @Test
    fun negativeStepIsRejected() {
        assertFailsWith<IllegalArgumentException> { FsrsCard(step = -1, due = now) }
    }

    @Test
    fun memoryStateNeedsStabilityAndDifficulty() {
        assertNull(FsrsCard(due = now).memoryState)
        assertNull(FsrsCard(stability = 1.0, due = now).memoryState)
        assertNull(FsrsCard(difficulty = 5.0, due = now).memoryState)
        assertEquals(FsrsState(difficulty = 5.0, stability = 1.0), FsrsCard(stability = 1.0, difficulty = 5.0, due = now).memoryState)
    }
}
