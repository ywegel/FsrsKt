# FsrsKt API overview

Compact reference of the public API, for people and coding agents. Package: `one.felsen.fsrskt.fsrs6`, unless noted.
Time is `kotlin.time.Instant` / `Duration`. Intervals, stability and elapsed time are in **days**.
Full KDoc: https://fsrskt.felsen.one/. The scheduler is a port of py-fsrs 6.3.2 (`Scheduler`), tested against it.

Keep this file in sync with `library/api/`. CI fails if the API dump changes in a PR without a change here.

## Which entry point to use

| Need | Use |
|---|---|
| Full scheduling: learning steps, due dates, fuzz | `FsrsScheduler` + `FsrsCard` |
| Only the memory state (difficulty, stability), own scheduling | `FsrsCalculator` + `FsrsState` |
| Single formulas | `Fsrs` |

## FsrsScheduler

```kotlin
class FsrsScheduler(
    parameters: List<Double> = FsrsParameters.defaultParameters, // 21 values, validated
    desiredRetention: Double = 0.9,                              // (0, 1) exclusive
    learningSteps: List<Duration> = listOf(1.minutes, 10.minutes),
    relearningSteps: List<Duration> = listOf(10.minutes),
    maximumInterval: Int = 36500,                                // days, >= 1
    enableFuzzing: Boolean = true,
    dayCounter: FsrsDayCounter = FsrsDayCounter.WholeDays,
    random: Random = Random.Default,                             // only used for fuzz
) {
    val fsrs: Fsrs
    val calculator: FsrsCalculator

    fun review(card: FsrsCard, rating: FsrsRating, reviewAt: Instant, reviewDuration: Duration? = null): FsrsReviewResult
    fun retrievability(card: FsrsCard, now: Instant): Double   // 0.0..1.0; 0.0 without lastReview or stability
    fun reschedule(card: FsrsCard, reviews: Iterable<FsrsReviewLog>): FsrsCard // only card.due is used
    fun nextInterval(stability: Double): Int                   // days, 1..maximumInterval, no fuzz
}

data class FsrsReviewResult(val card: FsrsCard, val log: FsrsReviewLog) {
    val interval: Duration // card.due - log.reviewAt
}
```

- Throws `IllegalArgumentException` for an invalid configuration, for a card in `REVIEW`/`RELEARNING` without
  stability and difficulty, and for a card in `RELEARNING` without step.
- Immutable and never reads the clock. Steps must be finite and >= 0; empty lists skip (re)learning.
- Empty `relearningSteps`: AGAIN in `REVIEW` keeps the card in `REVIEW` with an interval in days.
- HARD on step 0: 1.5 x the step with one step, otherwise the mean of the first two steps.

## FsrsCard, FsrsCardState, FsrsReviewLog

```kotlin
data class FsrsCard(
    val state: FsrsCardState = FsrsCardState.LEARNING,
    val step: Int? = if (state == FsrsCardState.LEARNING) 0 else null, // >= 0; null in REVIEW
    val stability: Double? = null,   // days; null before the first review
    val difficulty: Double? = null,  // 1.0..10.0; null before the first review
    val due: Instant,
    val lastReview: Instant? = null,
) { val memoryState: FsrsState? }    // null if stability or difficulty is null

enum class FsrsCardState { LEARNING, REVIEW, RELEARNING }

data class FsrsReviewLog(val rating: FsrsRating, val reviewAt: Instant, val reviewDuration: Duration? = null)
```

A new card is `FsrsCard(due = now)`. There is no `NEW` state and no card id: the app keeps the id.

## FsrsDayCounter

```kotlin
fun interface FsrsDayCounter {
    fun elapsedDays(from: Instant, to: Instant): Long
    companion object {
        val WholeDays: FsrsDayCounter  // whole 24-hour periods, rounded down (also negative), like py-fsrs
        fun calendarDays(dayCutoffHour: Int = 4, timeZone: TimeZone = TimeZone.currentSystemDefault()): FsrsDayCounter
    }
}
```

Less than 1 elapsed day uses the short-term formulas. With `WholeDays`, 11 PM to 5 AM is 0 days; with
`calendarDays()` it is 1 day. Learning apps usually want `calendarDays()`; py-fsrs counts like `WholeDays`.

## FsrsFuzz

```kotlin
object FsrsFuzz {
    const val MIN_FUZZED_INTERVAL = 2.5
    fun fuzzRange(intervalDays: Int, maximumInterval: Int): IntRange
    fun fuzzedInterval(intervalDays: Int, maximumInterval: Int, u: Double): Int    // u in [0, 1)
    fun fuzzedInterval(intervalDays: Int, maximumInterval: Int, random: Random): Int // one nextDouble() if fuzzed
}
```

Intervals below 2.5 days are not fuzzed. Like py-fsrs, the result can be one day above `fuzzRange().last`.

## FsrsCalculator

```kotlin
class FsrsCalculator(val fsrs: Fsrs = Fsrs()) {
    fun review(state: FsrsState?, rating: FsrsRating, elapsedDays: Double?): FsrsState // null state = new card
    fun replay(reviews: Iterable<FsrsReviewLog>, dayCounter: FsrsDayCounter = FsrsDayCounter.WholeDays): FsrsState?
}
```

`elapsedDays`: `null` if the last review is unknown (retrievability 0); fractional days are used as they are.
`replay` sorts by `reviewAt` and returns `null` for no reviews; equals `memoryState` of `FsrsScheduler.reschedule`.

## Fsrs, FsrsState, FsrsRating, FsrsParameters

```kotlin
class Fsrs(parameters: List<Double> = FsrsParameters.defaultParameters) {
    val w: List<Double>
    val decay: Double   // -w[20], negative
    val factor: Double  // 0.9^(1 / decay) - 1
    fun initialStability(rating: FsrsRating): Double
    fun initialDifficulty(rating: FsrsRating, clamp: Boolean = true): Double
    fun retrievability(t: Double?, s: Double): Double                      // t, s in days; null t -> 0.0
    fun interval(stability: Double, desiredRetention: Double): Double      // unrounded days
    fun nextInterval(stability: Double, desiredRetention: Double, maximumInterval: Int): Int // rounded half to even
    fun nextDifficulty(difficulty: Double, rating: FsrsRating): Double
    fun nextStabilityShortTerm(stability: Double, rating: FsrsRating): Double
    fun nextStability(difficulty: Double, stability: Double, retrievability: Double, rating: FsrsRating): Double
    fun stabilityOnRecall(difficulty: Double, stability: Double, retrievability: Double, rating: FsrsRating): Double
    fun stabilityOnLapse(difficulty: Double, stability: Double, retrievability: Double): Double
}

data class FsrsState(val difficulty: Double, val stability: Double) // memory state, not the card phase
enum class FsrsRating(val value: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }
object FsrsParameters { const val STABILITY_MIN = 0.001; val defaultParameters; val lowerBoundsParameters; val upperBoundsParameters }
```

## DateTimeHelper (`one.felsen.fsrskt.helper`)

```kotlin
object DateTimeHelper {
    fun Instant.toDayNumber(dayCutoffHour: Int = 4, timeZone: TimeZone = TimeZone.currentSystemDefault()): Long
    fun Instant.elapsedDays(other: Instant = Clock.System.now()): Double          // whole days, truncated
    fun Instant.elapsedCalendarDays(other: Instant = Clock.System.now(), dayCutoffHour: Int = 4, timeZone: TimeZone = ...): Double
    fun Instant.isSameDay(other: Instant, dayCutoffHour: Int = 4, timeZone: TimeZone = ...): Boolean
}
```

## Typical use

```kotlin
val scheduler = FsrsScheduler(dayCounter = FsrsDayCounter.calendarDays())
val now = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()) // same precision as the database
val card = loadCard(id) ?: FsrsCard(due = now)
val result = scheduler.review(card, FsrsRating.GOOD, now)
saveInOneTransaction(id, result.card, result.log)
```

## Rules and pitfalls

- Persist the six `FsrsCard` fields per card and every `FsrsReviewLog` (append-only). Without logs, a changed
  configuration cannot be applied with `reschedule`. Don't persist retrievability, interval or elapsed days.
- Always pass the time; truncate it to the precision you store, or the stored card differs from the computed one.
- The due date is `nextInterval`, not `now + stability`: the stability equals the interval only at 90 % retention.
- Rate wrong answers `AGAIN` and right ones `GOOD`; `HARD`/`EASY` only if the user chooses them.
- `FsrsCardState` is the phase of a card, `FsrsState` its memory state. Don't confuse them.
- Inject a seeded `Random` (or `enableFuzzing = false`) in tests; fuzz is otherwise random.
