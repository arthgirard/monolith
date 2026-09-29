package com.monolith.app.domain.usecase

/** Automatic backups go up at most once per [MIN_INTERVAL_MILLIS]; the first one is always due. */
object BackupThrottle {
    const val MIN_INTERVAL_MILLIS = 3 * 60 * 60 * 1000L

    fun due(lastBackupAt: Long?, now: Long): Boolean = lastBackupAt == null || now - lastBackupAt >= MIN_INTERVAL_MILLIS
}
