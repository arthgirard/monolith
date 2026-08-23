package com.monolith.app.domain.model

/**
 * The per-app exception earned by solving a code breaker. Distinct from the global emergency
 * bypass in [BlockState]: this exempts one package and leaves every other blocked app blocked.
 */
object AppUnlock {

    /**
     * How long one unlock lasts. Domain policy rather than a detail of the screen that grants it:
     * the enforcement notification counts the same window down, and two definitions of it would
     * let the wall and the shade disagree about when an app goes back behind the wall.
     */
    const val DURATION_MILLIS: Long = 5 * 60 * 1000L
}
