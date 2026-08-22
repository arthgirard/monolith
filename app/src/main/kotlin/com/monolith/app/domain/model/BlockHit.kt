package com.monolith.app.domain.model

/**
 * One recorded moment of reaching for a blocked app and meeting the wall. Distinct from a
 * [BlockSession], which measures time held: a hit is a single act of reaching, and the block
 * overlay shows today's count back to the user as the thing they're actually repeating.
 */
data class BlockHit(
    val packageName: String,
    val atMillis: Long,
)
