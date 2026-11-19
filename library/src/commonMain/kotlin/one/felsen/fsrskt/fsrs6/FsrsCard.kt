package one.felsen.fsrskt.fsrs6

import kotlin.time.Instant

/**
 * An immutable flashcard as scheduled by [FsrsScheduler]. Same as py-fsrs `Card`, but without `card_id`: the app
 * keeps track of which card this is.
 *
 * A new card is `FsrsCard(due = now)`: in [FsrsCardState.LEARNING] at step 0, without memory state and review.
 *
 * @property state The phase of the card.
 * @property step The current learning or relearning step, as an index into [FsrsScheduler.learningSteps] or
 * [FsrsScheduler.relearningSteps]. `null` in [FsrsCardState.REVIEW]. Defaults to 0 in [FsrsCardState.LEARNING] and to
 * `null` otherwise. The step may be beyond the steps of the scheduler, if the card was scheduled with more steps before.
 * @property stability The stability of the card (in days), or `null` before the first review.
 * @property difficulty The difficulty of the card (1.0 to 10.0), or `null` before the first review.
 * @property due When the card is due for its next review.
 * @property lastReview When the card was reviewed last, or `null` before the first review.
 * @throws IllegalArgumentException If [step] is negative.
 */
data class FsrsCard(
    val state: FsrsCardState = FsrsCardState.LEARNING,
    val step: Int? = if (state == FsrsCardState.LEARNING) 0 else null,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Instant,
    val lastReview: Instant? = null,
) {

    init {
        require(step == null || step >= 0) { "step must not be negative, but was $step." }
    }

    /**
     * The memory state of the card, or `null` if [stability] or [difficulty] is unknown.
     */
    val memoryState: FsrsState?
        get() = TODO()
}
