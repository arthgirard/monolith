package com.monolith.app.domain.model

enum class SlotResult { EXACT, PARTIAL, MISS }

/**
 * A Mastermind-style code-breaking puzzle: [secret] is a fixed-length sequence of symbol indices,
 * hidden from the solver. Each [Guess] records, per slot, whether that guessed symbol was exactly
 * right, present elsewhere in the secret, or not present at all -- solving is direct deduction
 * from real per-slot information, not a blind aggregate count.
 */
data class CodeBreaker(
    val secret: List<Int>,
    val guesses: List<Guess> = emptyList(),
) {
    data class Guess(val values: List<Int>, val slotResults: List<SlotResult>) {
        val exactMatches: Int get() = slotResults.count { it == SlotResult.EXACT }
        val partialMatches: Int get() = slotResults.count { it == SlotResult.PARTIAL }
    }

    val isSolved: Boolean
        get() = guesses.lastOrNull()?.slotResults?.all { it == SlotResult.EXACT } == true

    /** Guesses still allowed against this secret before it's burned and replaced. */
    val triesLeft: Int get() = (MAX_GUESSES - guesses.size).coerceAtLeast(0)

    /** Every try spent without cracking it: this secret is dead, a fresh one takes its place. */
    val isOutOfTries: Boolean get() = !isSolved && guesses.size >= MAX_GUESSES

    fun withGuess(values: List<Int>): CodeBreaker {
        val results = MutableList(values.size) { SlotResult.MISS }
        val leftoverSecretIndices = mutableListOf<Int>()
        values.indices.forEach { i ->
            if (values[i] == secret[i]) {
                results[i] = SlotResult.EXACT
            } else {
                leftoverSecretIndices += i
            }
        }
        // Remaining (non-exact) secret symbols form a shared pool: each non-exact guess slot, in
        // order, claims one matching symbol from it if available, so a repeated symbol can't earn
        // more partial credit than the secret actually has copies of.
        val leftoverSecretValues = leftoverSecretIndices.map { secret[it] }.toMutableList()
        values.indices.forEach { i ->
            if (results[i] == SlotResult.MISS && leftoverSecretValues.remove(values[i])) {
                results[i] = SlotResult.PARTIAL
            }
        }
        return copy(guesses = guesses + Guess(values, results))
    }

    companion object {
        /**
         * Tries against one secret. Deliberately tight: with six slots and eight symbols, five
         * guesses is enough to deduce the code from the per-slot feedback but not enough to grind
         * it out by brute force, and running out costs the whole board rather than one guess.
         */
        const val MAX_GUESSES = 5
    }
}
