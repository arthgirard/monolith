package com.monolith.app.data.backup

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monolith.app.data.datastore.applySnapshot
import com.monolith.app.data.datastore.readSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupPrefsTest {

    private val snapshot = BackupSnapshot(
        createdAt = 1_000,
        sessions = listOf(BackupSnapshot.SessionEntry(10, 20), BackupSnapshot.SessionEntry(30, 90)),
        blockedPackages = listOf("com.a", "com.b"),
        importantPeople = listOf(BackupSnapshot.PersonEntry("com.a", name = "Sam")),
        schedules = listOf(BackupSnapshot.ScheduleEntry("s1", true, listOf("MONDAY"), 540)),
        strictness = "STRICT",
    )

    @Test
    fun `apply then read round trips the included data`() {
        val prefs = mutablePreferencesOf()
        applySnapshot(prefs, snapshot)
        val read = readSnapshot(prefs, now = 2_000)
        assertEquals(snapshot.copy(createdAt = 2_000, blockedPackages = snapshot.blockedPackages.sorted()), read.copy(blockedPackages = read.blockedPackages.sorted()))
    }

    @Test
    fun `restore leaves excluded keys untouched`() {
        val tag = stringPreferencesKey("tag_uid")
        val active = booleanPreferencesKey("block_mode_active")
        val started = longPreferencesKey("session_started_at")
        val pauses = stringPreferencesKey("pause_log")
        val prefs = mutablePreferencesOf(tag to "uid", active to true, started to 5L, pauses to "[]")
        applySnapshot(prefs, snapshot)
        assertEquals("uid", prefs[tag])
        assertEquals(true, prefs[active])
        assertEquals(5L, prefs[started])
        assertEquals("[]", prefs[pauses])
    }

    @Test
    fun `restore replaces rather than merges sessions and apps`() {
        val prefs = mutablePreferencesOf()
        applySnapshot(prefs, snapshot)
        applySnapshot(prefs, snapshot.copy(sessions = emptyList(), blockedPackages = emptyList(), strictness = null))
        val read = readSnapshot(prefs, now = 0)
        assertEquals(emptyList<BackupSnapshot.SessionEntry>(), read.sessions)
        assertEquals(emptyList<String>(), read.blockedPackages)
        assertEquals(null, read.strictness)
    }

    @Test
    fun `codec round trips and rejects an unknown version`() {
        assertEquals(snapshot, BackupSnapshotCodec.decode(BackupSnapshotCodec.encode(snapshot)))
        val future = BackupSnapshotCodec.encode(snapshot.copy(version = 2))
        assertThrows(BackupCryptoException::class.java) { BackupSnapshotCodec.decode(future) }
    }
}
