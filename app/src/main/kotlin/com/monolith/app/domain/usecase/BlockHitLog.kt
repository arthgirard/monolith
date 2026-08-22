package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.BlockHit
import java.time.Instant
import java.time.ZoneId

/**
 * The rules for the block-hit log, kept pure so they can be tested without DataStore. The write
 * itself has to stay inside MonolithPreferences' atomic `edit` block -- two blocked apps in quick
 * succession would otherwise read-modify-write over each other -- so only the decision lives here.
 */
object BlockHitLog {

    /** Hits older than this are dropped on write. */
    const val RETENTION_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * Two hits on the same package inside this window count once. The accessibility service fires
     * on every TYPE_WINDOW_STATE_CHANGED, so one reach for a blocked app can re-trigger several
     * times -- the overlay taking focus, a transient system window, the app redrawing behind it --
     * and an undeduped count would climb into meaninglessness within a single reach.
     */
    const val DEDUPE_MILLIS: Long = 60L * 1000

    /**
     * Returns the log with this hit appended and expired hits pruned, or null if [packageName]
     * already has a hit inside the dedupe window and nothing should be written.
     */
    fun record(existing: List<BlockHit>, packageName: String, now: Long): List<BlockHit>? {
        val lastForPackage = existing
            .filter { it.packageName == packageName }
            .maxOfOrNull { it.atMillis }
        if (lastForPackage != null && now - lastForPackage < DEDUPE_MILLIS) return null
        val cutoff = now - RETENTION_MILLIS
        return existing.filter { it.atMillis >= cutoff } + BlockHit(packageName, now)
    }

    /**
     * How many times [packageName] was blocked today, in [zone]'s calendar day. Counting by local
     * midnight rather than a rolling 24 hours is what makes the overlay's "opened today" agree
     * with the user's own sense of the day.
     */
    fun countToday(
        hits: List<BlockHit>,
        packageName: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val startOfDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
        return hits.count { it.packageName == packageName && it.atMillis >= startOfDay }
    }
}
