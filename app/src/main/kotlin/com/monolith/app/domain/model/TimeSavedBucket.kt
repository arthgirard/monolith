package com.monolith.app.domain.model

data class TimeSavedBucket(
    val bucketStartMillis: Long,
    val durationMillis: Long,
    val capacityMillis: Long,
)
