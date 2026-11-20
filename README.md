# FsrsKt: A Kotlin library for FSRS

This project is a Kotlin Multiplatform library that implements the FSRS-6 (Free Spaced Repetition Scheduler) algorithm.

## Introduction to FSRS

You may have heard of spaced repetition systems (SRS) like Anki, which are designed to help you remember information
more effectively by scheduling reviews at optimal intervals.

This library implements the FSRS-6 algorithm, which is a free and open-source spaced repetition scheduling algorithm. It
is designed to be simple, efficient, and effective for learning and retaining information over time.

In case you are not familiar with FSRS, you can read
the [ABC of FSRS](https://github.com/open-spaced-repetition/awesome-fsrs/wiki/ABC-of-FSRS)

This library aims to provide a Kotlin implementation of the FSRS-6 algorithm that can be used in various applications,
including Android apps, desktop applications, and server-side applications.

## Api Documentation

The API documentation for FsrsKt can be found on [fsrskt.felsen.one](https://fsrskt.felsen.one/).

## Getting Started

### Dependency

To get started with FsrsKt, you can add the library to your Kotlin Multiplatform project.

The library is published to [Maven Central](https://central.sonatype.com/artifact/one.felsen.fsrskt/fsrskt).

### With the Scheduler

`FsrsScheduler` is a port of the py-fsrs `Scheduler`: it moves cards through learning steps, schedules reviews from a
desired retention and adds fuzz. It never reads the clock, so you always pass the time of the review.

```kotlin
import one.felsen.fsrskt.fsrs6.FsrsCard
import one.felsen.fsrskt.fsrs6.FsrsRating
import one.felsen.fsrskt.fsrs6.FsrsScheduler
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

val scheduler = FsrsScheduler(
    desiredRetention = 0.9,
    learningSteps = listOf(1.minutes, 10.minutes),
    relearningSteps = listOf(10.minutes),
    maximumInterval = 36500,
)

// A new card is due right away. Store the card next to your own content and id.
var card = FsrsCard(due = Clock.System.now())

// When the user reviews it:
val now = Clock.System.now()
val result = scheduler.review(card, FsrsRating.GOOD, now)
card = result.card          // new phase, step, stability, difficulty, due and last review
val log = result.log        // store it to recompute the card later with scheduler.reschedule(card, logs)

// The probability that the user still remembers the card now.
val retrievability = scheduler.retrievability(card, Clock.System.now())
```

By default the elapsed days between two reviews are whole 24-hour periods, like in py-fsrs. To count days in the
learner's time zone, with the day starting at 4 AM, pass `dayCounter = FsrsDayCounter.calendarDays()`.
For reproducible fuzz, pass a seeded `random = Random(seed)`, or turn it off with `enableFuzzing = false`.

### Basic Implementation

The following is a basic implementation of the FSRS algorithm using only the `FsrsCalculator`, without learning steps.
This example demonstrates how to create a simple flashcard application that uses FSRS to schedule reviews.

1. Crate a data structure to hold the card information. In this case, we will create a `Flashcard` data class that
   contains the front and back of the card, as well as the FSRS state and due date. You can also add any other
   information you want to store for each card, such as the last review date or the number of times the card has been
   reviewed.

    ```kotlin
    import one.felsen.fsrskt.fsrs6.FsrsState
    import kotlin.time.Instant
    
    data class Flashcard(
        // Content of the card (may be changed to something else; doesnt matter for the FSRS algorithm)
        val front: String,
        val back: String,
        
        // The FSRS state of the card, which is used to track the scheduling information for spaced repetition.
        var fsrsState: FsrsState? = null,
        
        // The due date for the next review of the card. This is calculated based on the FSRS state and the user's performance.
        var dueDate: Instant? = null,
        
        // The day of the last review
        var lastReview: Instant? = null
    )
    ```

2. Create the flashcard and initialize the FSRS state.

    ```kotlin
    // Create a deck of flashcards
    val deck = mutableListOf<Flashcard>()
    deck.add(Flashcard("What is the capital of South-Korea?", "Seoul"))
    // ... add more cards
    ```

3. Get the next card for review.

    ```kotlin
    // Get a list of all due cards
    // A card can be considered due if it has been reviewed before (fsrsState != null) and the due date is less than or equal to now.
    val dueCard = deck
        .filter { it.fsrsState != null && it.dueDate != null && it.dueDate!! <= now }
        .minByOrNull { it.dueDate!! }

    // Return the due card if it exists, otherwise return a new card that has not been reviewed yet.
    if (dueCard != null) return dueCard

    // If there are no due cards, return a new card that has not been reviewed yet.
    return deck.firstOrNull { it.fsrsState == null }
    ```

4. Implement the review process and update the FSRS state based on the user's performance.

    ```kotlin
    import one.felsen.fsrskt.fsrs6.FsrsCalculator
    import one.felsen.fsrskt.fsrs6.FsrsRating
    import one.felsen.fsrskt.helper.DateTimeHelper.elapsedCalendarDays
   
    // Create an instance of the FsrsCalculator to handle the review process.
    val calc = FsrsCalculator()
   
    val card: Flashcard = ... // Get the card to review (see step 3)
    val rating: FsrsRating = ... // Get the user's rating for the card (e.g., EASY, GOOD)
   
    // Get the current time
    val now = Clock.System.now()
   
    // Use the calculator to calculate the new FSRS state based on the user's rating and the elapsed time since the last review.
    val newState = calc.review(
        // Provide the current FSRS state of the card
        state = card.fsrsState,
        // Provide the user's rating for the card
        rating = rating,
        // Provide the calendar days elapsed since the last review of the card, or null if it is unknown.
        // `elapsedCalendarDays` considers all time until 4 AM of the next day as the same day.
        // Less than one day uses the short-term formula, otherwise the long-term formula is used.
        // Note: py-fsrs counts whole 24-hour periods instead. For example, a review at 11 PM and the next one at
        // 5 AM count as one day here, but as zero days in py-fsrs. Use `elapsedDays` to count like py-fsrs.
        elapsedDays = card.lastReview?.elapsedCalendarDays(now)
    )

    // Update the card's FSRS state and last review date with the new values calculated by the FsrsCalculator.
    card.fsrsState = newState
    // Update the last review date to the current time.
    card.lastReview = now

    // Precompute the due date for the next review: the whole days after which the retrievability drops to 90 %.
    // The stability alone is only the right interval for a desired retention of exactly 0.9.
    card.dueDate = now.plus(calc.fsrs.nextInterval(newState.stability, desiredRetention = 0.9, maximumInterval = 36500).days)
    ```

## Thanks

This project is based on the work of Jarrett Ye and other contributors to the FSRS algorithm. You can find more
information about the FSRS algorithm on the
wiki: [The Algorithm](https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm#default-parameters).
