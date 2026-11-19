package one.felsen.fsrskt.fsrs6

internal fun requireDesiredRetention(desiredRetention: Double) {
    require(desiredRetention > 0.0 && desiredRetention < 1.0) {
        "desiredRetention must be between 0 and 1, both exclusive, but was $desiredRetention."
    }
}

internal fun requireMaximumInterval(maximumInterval: Int) {
    require(maximumInterval >= 1) { "maximumInterval must be at least 1, but was $maximumInterval." }
}
