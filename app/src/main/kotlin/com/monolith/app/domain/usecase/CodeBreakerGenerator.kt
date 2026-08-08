package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.CodeBreaker
import kotlin.random.Random

/** Builds the per-unlock code-breaking puzzle: a random secret, repeats allowed. */
object CodeBreakerGenerator {

    const val SECRET_LENGTH = 6
    const val SYMBOL_COUNT = 8

    fun generate(random: Random = Random.Default): CodeBreaker =
        CodeBreaker(secret = List(SECRET_LENGTH) { random.nextInt(SYMBOL_COUNT) })
}
