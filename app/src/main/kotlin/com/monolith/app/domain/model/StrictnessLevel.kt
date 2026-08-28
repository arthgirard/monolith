package com.monolith.app.domain.model

/**
 * How many ways out of an active Monolith the user left themselves, chosen during setup and
 * changeable only while Monolith is off.
 *
 * Ordered loosest to strictest, and every level only ever takes a door away: nothing here grants
 * an escape a looser level lacks. [STANDARD] is what Monolith shipped with, so installs that
 * predate the setting keep exactly the behaviour they already had.
 */
enum class StrictnessLevel {
    /** The per-app code breaker and the emergency bypass, both available. */
    STANDARD,

    /** The emergency bypass alone. No per-app escape. */
    STRICT,

    /** Nothing lifts Monolith but the tag. */
    ABSOLUTE;

    /** Whether the block wall may offer its puzzle-and-waiver route into one app. */
    val allowsAppUnlock: Boolean get() = this == STANDARD

    /** Whether Home may offer the fifteen-minute bypass of everything. */
    val allowsEmergencyBypass: Boolean get() = this != ABSOLUTE

    companion object {
        val DEFAULT: StrictnessLevel = STANDARD

        /**
         * Reads a stored name back. An absent or unrecognised value resolves to [DEFAULT] rather
         * than throwing: a preference that failed to parse must not be able to lock someone out
         * of their own phone, and it must not be able to hand them an escape they turned off
         * either -- [DEFAULT] is the level they had before the setting existed.
         */
        fun fromStorage(name: String?): StrictnessLevel = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
