package com.monolith.app.domain.model

/** A moment enforcement was paused: the emergency bypass, or a per-app unlock. Both end the streak. */
enum class PauseType { BYPASS, UNLOCK }

data class Pause(val type: PauseType, val atMillis: Long)
