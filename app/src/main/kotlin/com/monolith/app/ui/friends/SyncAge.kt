package com.monolith.app.ui.friends

private const val STALE_AFTER_MILLIS = 60 * 60 * 1000L

/** A board row only mentions its last sync when it is missing or stale; fresh is the norm. */
fun showSyncAge(lastSyncAt: Long?, nowMillis: Long): Boolean =
    lastSyncAt == null || nowMillis - lastSyncAt > STALE_AFTER_MILLIS
