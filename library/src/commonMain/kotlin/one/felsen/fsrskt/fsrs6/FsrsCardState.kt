package one.felsen.fsrskt.fsrs6

/**
 * The phase of a [FsrsCard] in the [FsrsScheduler]. Same as py-fsrs `State`.
 *
 * Not to be confused with [FsrsState], which holds the memory state (difficulty and stability) of a card.
 */
enum class FsrsCardState {

    /**
     * The card is new or goes through the learning steps. A new card starts here at step 0.
     */
    LEARNING,

    /**
     * The card has finished its (re)learning steps and is scheduled in whole days.
     */
    REVIEW,

    /**
     * The card was forgotten in the review phase and goes through the relearning steps.
     */
    RELEARNING,
}
