package com.monolith.app.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** History and setup that survive a reinstall. Device-bound state (tag link, block state) is never in here. */
@Serializable
data class BackupSnapshot(
    val version: Int = 1,
    val createdAt: Long,
    val sessions: List<SessionEntry>,
    val blockedPackages: List<String>,
    val importantPeople: List<PersonEntry>,
    val schedules: List<ScheduleEntry>,
    val strictness: String?,
    /** Absent in backups made before the setting existed, which restore as the default (on). */
    val uninstallGuard: Boolean? = null,
) {
    @Serializable
    data class SessionEntry(val start: Long, val end: Long)

    @Serializable
    data class PersonEntry(val packageName: String, val name: String? = null, val handle: String? = null)

    @Serializable
    data class ScheduleEntry(val id: String, val enabled: Boolean, val days: List<String>, val startMinuteOfDay: Int)
}

object BackupSnapshotCodec {
    // Defaults written too, so every blob says which version it is.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(snapshot: BackupSnapshot): ByteArray =
        json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): BackupSnapshot {
        val snapshot = try {
            json.decodeFromString<BackupSnapshot>(bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupCryptoException("unreadable snapshot", e)
        }
        if (snapshot.version != 1) throw BackupCryptoException("unsupported version")
        return snapshot
    }
}
