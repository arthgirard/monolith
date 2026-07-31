package com.monolith.app.domain.model

data class BlockSession(
    val startMillis: Long,
    val endMillis: Long,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)
}
