package one.felsen.fsrskt.reference

import kotlinx.serialization.Serializable
import one.felsen.fsrskt.fsrs6.FsrsRating

/**
 * A fixture file of one function, with input type [I] and result type [O].
 */
@Serializable
data class FunctionFile<I, O>(
    val function: String,
    val description: String,
    val pyFsrs: String,
    val kotlin: String,
    val cases: List<FunctionCase<I, O>>,
)

/**
 * One case of a [FunctionFile]. [params] is the parameter set, or `null` if the function does not depend on it.
 */
@Serializable
data class FunctionCase<I, O>(val params: String? = null, val input: I, val expected: O)

/** A fixture file of an [one.felsen.fsrskt.fsrs6.Fsrs] formula that returns a Double. */
typealias FormulaFile<I> = FunctionFile<I, Double>

@Serializable
data class NextIntervalInput(val stability: Double, val desiredRetention: Double, val maximumInterval: Int)

@Serializable
data class FuzzRangeInput(val intervalDays: Int, val maximumInterval: Int)

@Serializable
data class FuzzRangeExpected(val first: Int, val last: Int)

@Serializable
data class FuzzedIntervalInput(val intervalDays: Int, val maximumInterval: Int, val u: Double)

@Serializable
data class FuzzedIntervalExpected(val intervalDays: Int, val drawsRandom: Boolean)

@Serializable
data class RatingInput(val rating: FsrsRating)

@Serializable
data class RetrievabilityInput(val elapsedDays: Int, val stability: Double)

@Serializable
data class NextDifficultyInput(val difficulty: Double, val rating: FsrsRating)

@Serializable
data class NextStabilityShortTermInput(val stability: Double, val rating: FsrsRating)

@Serializable
data class StabilityOnRecallInput(
    val difficulty: Double,
    val stability: Double,
    val retrievability: Double,
    val rating: FsrsRating,
)

@Serializable
data class StabilityOnLapseInput(val difficulty: Double, val stability: Double, val retrievability: Double)
