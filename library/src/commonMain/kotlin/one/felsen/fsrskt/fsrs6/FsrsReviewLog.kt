package one.felsen.fsrskt.fsrs6

import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One review of a card. Same as py-fsrs `ReviewLog`, but without `card_id`: the app keeps track of which card was
 * reviewed.
 *
 * @property rating The rating given in the review.
 * @property reviewAt When the review happened.
 * @property reviewDuration How long the review took, or `null` if unknown. py-fsrs stores whole milliseconds. Not
 * used for scheduling.
 */
data class FsrsReviewLog(
    val rating: FsrsRating,
    val reviewAt: Instant,
    val reviewDuration: Duration? = null,
)
