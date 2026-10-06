# Recovery Code on the Tag Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Linking a writable tag also stores the recovery code on it (hidden), and re-linking that tag during setup on a fresh install offers a one-tap restore.

**Architecture:** A pure-JVM `TagCodeCodec` seals the master under a UID-derived key into a second NDEF record. `NfcManager` writes and reads that record; `IdentityManager` can hand out a master before the server knows it, so linking works offline. A stateful `TagRestoreUseCase` wraps the existing two-step `RestoreBackupUseCase` for the onboarding tag step, which moves to the front of setup. Whether a tag's code is current is derived from `NfcTagLink.code`, never stored.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, DataStore Preferences, android.nfc, JCA AES-GCM, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-06-tag-recovery-code-design.md`

## Global Constraints

- Tag record 2: NDEF external type `monolith.app:k`, payload `0x01 ‖ 12-byte nonce ‖ AES-256-GCM(tagKey, nonce, master)`, 61 bytes.
- `tagKey = HKDF-SHA256(ikm = UID bytes, salt = "monolith", info = "tag v1", 32 bytes)`.
- Record 1 (`monolith://tag/<UID>`) stays first and unchanged.
- The code is written only while backup is on, and only to tags with room for both records.
- UID-only tags, and tags too small for the code: no message, no link-screen line, no Settings row, ever.
- Backup on by default for new installs only (set during onboarding); existing installs keep their stored value.
- Restore is never silent; Start fresh never overwrites an unchecked code without confirming.
- No server changes.
- Copy: no em-dashes, no all-caps. Every new string is added to `values` and to all six translations (`de`, `es`, `fr`, `it`, `nl`, `pt-rBR`), formal register, matching existing terms (de "Sicherung", "Wiederherstellungscode"; nl "back-up", "herstelcode"; etc.).
- Commits: no `Co-Authored-By` or other AI trailers.
- Unit tests: `./gradlew :app:testDebugUnitTest`. Device-bound work ends with `./gradlew assembleRelease`.

## Review Focus

1. **A transient read miss on a background tap** (cached NDEF message absent) must not flip a good link to "missing code": the tap only records a code it actually found. Pinned in Task 4.
2. **Restore that fails after the offer** (network drops between prepare and confirm) must link nothing and land on Try again, not half-restore. Pinned in Task 6.
3. **The recovery code while still unregistered** (offline setup): Settings must not call the tag "old" just because the identity has no master yet. Pinned in Task 4 (`tagCodeState` returns null without a code).
4. **An offline registration** must keep the same master for the next try, or the tag carries a code that restores nothing. Pinned in Task 2.
5. **Apps missing on the new phone** must vanish from blocked apps and important people while history survives. Pinned in Task 5.

---

### Task 1: TagCodeCodec

**Files:**
- Create: `app/src/main/kotlin/com/monolith/app/data/backup/TagCodeCodec.kt`
- Test: `app/src/test/kotlin/com/monolith/app/data/backup/TagCodeCodecTest.kt`

**Interfaces:**
- Consumes: `BackupCrypto.hkdf(ikm, salt, info, length)` (exists).
- Produces: `object TagCodeCodec { const val PAYLOAD_BYTES: Int; fun encode(uid: ByteArray, master: ByteArray): ByteArray; fun decode(uid: ByteArray, payload: ByteArray): ByteArray? }`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.monolith.app.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagCodeCodecTest {

    private val uid = byteArrayOf(0x04, 0x1A, 0x2B, 0x3C, 0x4D, 0x5E, 0x6F)
    private val master = ByteArray(32) { it.toByte() }

    @Test
    fun `round trip returns the master`() {
        assertArrayEquals(master, TagCodeCodec.decode(uid, TagCodeCodec.encode(uid, master)))
    }

    @Test
    fun `payload is the advertised size`() {
        assertEquals(61, TagCodeCodec.PAYLOAD_BYTES)
        assertEquals(TagCodeCodec.PAYLOAD_BYTES, TagCodeCodec.encode(uid, master).size)
    }

    @Test
    fun `another tag's uid does not decode`() {
        val other = uid.copyOf().also { it[6] = 0x70 }
        assertNull(TagCodeCodec.decode(other, TagCodeCodec.encode(uid, master)))
    }

    @Test
    fun `a tampered byte does not decode`() {
        val payload = TagCodeCodec.encode(uid, master)
        payload[20] = (payload[20].toInt() xor 1).toByte()
        assertNull(TagCodeCodec.decode(uid, payload))
    }

    @Test
    fun `an unknown version does not decode`() {
        val payload = TagCodeCodec.encode(uid, master)
        payload[0] = 2
        assertNull(TagCodeCodec.decode(uid, payload))
    }

    @Test
    fun `a short payload does not decode`() {
        assertNull(TagCodeCodec.decode(uid, ByteArray(10)))
    }

    @Test
    fun `both records fit an NTAG213`() {
        // Short-record header (3) + type "U" (1) + URI prefix byte (1) + the URI itself.
        val uriRecord = 3 + 1 + 1 + "monolith://tag/".length + uid.size * 2
        // Short-record header (3) + external type + payload.
        val codeRecord = 3 + "monolith.app:k".length + TagCodeCodec.PAYLOAD_BYTES
        // 137 is the NDEF capacity Android reports for an NTAG213 (144 bytes of user memory).
        assertTrue(uriRecord + codeRecord <= 137)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.monolith.app.data.backup.TagCodeCodecTest"`
Expected: FAIL, compilation error "Unresolved reference: TagCodeCodec".

- [ ] **Step 3: Write the implementation**

```kotlin
package com.monolith.app.data.backup

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The recovery code as it sits on a linked tag, sealed under a key derived from the tag's own UID.
 *
 * Hidden, not protected: a reader app shows opaque bytes, but the key comes from the UID and
 * constants anyone can read here, so whoever studies the app can still open it. Accepted because
 * whoever holds the tag can already turn Monolith off. Binding the key to the UID means the record
 * copied onto another tag does not decode.
 */
object TagCodeCodec {
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MASTER_BYTES = 32
    private const val KEY_BYTES = 32

    /** Version, nonce, the sealed master, and the GCM tag. */
    const val PAYLOAD_BYTES = 1 + NONCE_BYTES + MASTER_BYTES + TAG_BITS / 8

    private val random = SecureRandom()
    private val salt = "monolith".toByteArray(Charsets.UTF_8)
    private val info = "tag v1".toByteArray(Charsets.UTF_8)

    fun encode(uid: ByteArray, master: ByteArray): ByteArray {
        require(master.size == MASTER_BYTES) { "master must be $MASTER_BYTES bytes" }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        return byteArrayOf(VERSION) + nonce + cipher(Cipher.ENCRYPT_MODE, uid, nonce).doFinal(master)
    }

    /** Null for another tag's UID, a tampered payload, or a version this build does not know. */
    fun decode(uid: ByteArray, payload: ByteArray): ByteArray? {
        if (payload.size != PAYLOAD_BYTES || payload[0] != VERSION) return null
        val nonce = payload.copyOfRange(1, 1 + NONCE_BYTES)
        return try {
            cipher(Cipher.DECRYPT_MODE, uid, nonce).doFinal(payload, 1 + NONCE_BYTES, payload.size - 1 - NONCE_BYTES)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun cipher(mode: Int, uid: ByteArray, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            val key = BackupCrypto.hkdf(uid, salt, info, KEY_BYTES)
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(byteArrayOf(VERSION))
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.monolith.app.data.backup.TagCodeCodecTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/monolith/app/data/backup/TagCodeCodec.kt app/src/test/kotlin/com/monolith/app/data/backup/TagCodeCodecTest.kt
git commit -m "feat: seal the recovery code for a tag under its UID"
```

---

### Task 2: A master before registration

**Files:**
- Modify: `app/src/main/kotlin/com/monolith/app/data/leaderboard/IdentityStore.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/data/repository/IdentityManager.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/domain/repository/BackupRepository.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/data/repository/BackupRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/service/BackupScheduler.kt`
- Modify: `app/src/test/kotlin/com/monolith/app/data/repository/LeaderboardFakes.kt`
- Create: `app/src/test/kotlin/com/monolith/app/domain/usecase/BackupFakes.kt`
- Modify: `app/src/test/kotlin/com/monolith/app/domain/usecase/RestoreBackupUseCaseTest.kt`
- Test: `app/src/test/kotlin/com/monolith/app/data/repository/BackupRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `IdentityStore.unregisteredMaster: Flow<String?>`, `IdentityStore.saveUnregisteredMaster(master: String)`
  - `IdentityManager.localMaster(): String?`
  - `BackupRepository.localRecoveryCode(): String?`, `BackupRepository.enableByDefault()`
  - Test fakes (package `com.monolith.app.domain.usecase`): `FakeBackupRepository(fetchResult, enabled)` with `enabled: MutableStateFlow<Boolean>`, `localCode: String?`, `enabledByDefault: Int`, `fetchCalls`, `enabledCalls`; `RecordingWriter`; `CountingAfterRestore`.

- [ ] **Step 1: Move the backup fakes out of RestoreBackupUseCaseTest**

Create `app/src/test/kotlin/com/monolith/app/domain/usecase/BackupFakes.kt`:

```kotlin
package com.monolith.app.domain.usecase

import com.monolith.app.data.backup.BackupSnapshot
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.repository.BackupRepository
import com.monolith.app.domain.repository.FetchedBackup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeBackupRepository(
    var fetchResult: LeaderboardResult<FetchedBackup> = LeaderboardResult.Err(LeaderboardError.NO_BACKUP),
    enabled: Boolean = false,
) : BackupRepository {
    val enabled = MutableStateFlow(enabled)
    var localCode: String? = null
    var enabledByDefault = 0
        private set
    val fetchCalls = mutableListOf<String>()
    val enabledCalls = mutableListOf<Boolean>()

    override fun observeBackupEnabled(): Flow<Boolean> = enabled
    override fun observeLastBackupAt(): Flow<Long?> = MutableStateFlow(null)
    override fun observeRecoveryCode(): Flow<String?> = MutableStateFlow(null)
    override suspend fun ensureIdentity(displayName: String?) = LeaderboardResult.Ok(Unit)
    override suspend fun setBackupEnabled(enabled: Boolean): LeaderboardResult<Unit> {
        enabledCalls += enabled
        return LeaderboardResult.Ok(Unit)
    }
    override suspend fun upload(snapshot: BackupSnapshot, now: Long) = LeaderboardResult.Ok(Unit)
    override suspend fun fetch(recoveryCode: String): LeaderboardResult<FetchedBackup> {
        fetchCalls += recoveryCode
        return fetchResult
    }
    override suspend fun localRecoveryCode(): String? = localCode
    override suspend fun enableByDefault() {
        enabledByDefault++
        enabled.value = true
    }
}

class RecordingWriter : SnapshotWriter {
    val writes = mutableListOf<BackupSnapshot>()
    override suspend fun write(snapshot: BackupSnapshot) { writes += snapshot }
}

class CountingAfterRestore : AfterRestore {
    var runs = 0
    override suspend fun run() { runs++ }
}
```

In `RestoreBackupUseCaseTest.kt`, delete the three private classes `FakeBackupRepository`, `RecordingWriter` and `CountingAfterRestore`, and the now-unused imports (`BackupRepository`, `Flow`, `MutableStateFlow`). The `Harness` keeps working unchanged because the shared classes have the same names and members.

- [ ] **Step 2: Write the failing tests**

Add to `BackupRepositoryTest.kt` (inside the class; `repo(api, store)` and `masterOf` already exist there; add imports `org.junit.Assert.assertNull`, `org.junit.Assert.assertTrue`, `com.monolith.app.domain.model.LeaderboardError` if missing):

```kotlin
    @Test
    fun `a code handed out before registering is the one registered`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore()
        val repo = repo(api, store)

        val code = repo.localRecoveryCode()!!
        assertEquals("the same code until it registers", code, repo.localRecoveryCode())
        assertEquals(LeaderboardResult.Ok(Unit), repo.ensureIdentity(null))

        assertEquals(code, store.identity.value!!.master)
        assertEquals(BackupCrypto.deriveToken(BackupCrypto.decodeCode(code)!!), api.registerRequests.single().token)
        assertNull(store.unregisteredMaster.value)
    }

    @Test
    fun `an offline registration keeps the code for the next try`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore()
        val repo = repo(api, store)
        val code = repo.localRecoveryCode()!!

        api.registerResult = LeaderboardResult.Err(LeaderboardError.NETWORK)
        repo.ensureIdentity(null)
        assertNull(store.identity.value)

        api.registerResult = LeaderboardResult.Ok(Unit)
        repo.ensureIdentity(null)
        assertEquals(code, store.identity.value!!.master)
    }

    @Test
    fun `an identity's own code is the local one`() = runBlocking {
        val code = BackupCrypto.encodeCode(BackupCrypto.newMaster())
        val store = FakeIdentityStore(Identity("t", "", emptyList(), master = code))

        assertEquals(code, repo(FakeLeaderboardApi(), store).localRecoveryCode())
        assertNull(store.unregisteredMaster.value)
    }

    @Test
    fun `an identity from before backups has no local code until it migrates`() = runBlocking {
        val store = FakeIdentityStore(Identity("old", "", emptyList(), master = null))

        assertNull(repo(FakeLeaderboardApi(), store).localRecoveryCode())
    }

    @Test
    fun `enableByDefault turns backup on without asking the server`() = runBlocking {
        val api = FakeLeaderboardApi()
        val store = FakeIdentityStore()

        repo(api, store).enableByDefault()

        assertTrue(store.backupEnabled.value)
        assertEquals(0, api.calls)
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.monolith.app.data.repository.BackupRepositoryTest"`
Expected: FAIL, compilation errors "Unresolved reference: localRecoveryCode / unregisteredMaster / enableByDefault".

- [ ] **Step 4: IdentityStore**

In `IdentityStore` (interface), after `pendingMaster`:

```kotlin
    /**
     * A master made on the phone before any server knows it: written to a tag during setup, and
     * registered on the next online moment. Cleared by any [save], and by [clear].
     */
    val unregisteredMaster: Flow<String?>
```

and after `savePendingMaster`:

```kotlin
    suspend fun saveUnregisteredMaster(master: String)
```

In `DataStoreIdentityStore`:

```kotlin
    override val unregisteredMaster: Flow<String?> = context.leaderboardStore.data.map { it[UNREGISTERED_MASTER] }
```

```kotlin
    override suspend fun saveUnregisteredMaster(master: String) {
        context.leaderboardStore.edit { it[UNREGISTERED_MASTER] = master }
    }
```

In `save`, add as the last line of the edit block:

```kotlin
            prefs.remove(UNREGISTERED_MASTER)
```

In the companion object:

```kotlin
        val UNREGISTERED_MASTER = stringPreferencesKey("unregistered_master")
```

In `LeaderboardFakes.kt`, `FakeIdentityStore`:

```kotlin
    override val unregisteredMaster = MutableStateFlow<String?>(null)
```

```kotlin
    override suspend fun saveUnregisteredMaster(master: String) { unregisteredMaster.value = master }
```

and in its `save` add `unregisteredMaster.value = null`, and in its `clear` add `unregisteredMaster.value = null`.

- [ ] **Step 5: IdentityManager**

Add after `ensureIdentity`:

```kotlin
    /**
     * The recovery code a tag linked now should carry: the identity's, or one made here and kept
     * until it registers. Null for an identity from before backups, whose code exists only once it
     * migrates.
     */
    suspend fun localMaster(): String? = mutex.withLock {
        val identity = store.identity.first()
        if (identity != null) return@withLock identity.master
        store.unregisteredMaster.first()
            ?: BackupCrypto.encodeCode(BackupCrypto.newMaster()).also { store.saveUnregisteredMaster(it) }
    }
```

In `register`, replace `val master = BackupCrypto.newMaster()` with:

```kotlin
        // A master already written to a tag is the one to register, or the tag would carry a code
        // that restores nothing.
        val master = store.unregisteredMaster.first()?.let(BackupCrypto::decodeCode) ?: BackupCrypto.newMaster()
```

- [ ] **Step 6: BackupRepository**

In the interface, after `ensureIdentity`:

```kotlin
    /** The code a tag linked now should carry. Null for an identity from before backups. */
    suspend fun localRecoveryCode(): String?

    /**
     * Turns backup on without asking the server, for a new install. Registration and the first
     * upload follow on the next online moment.
     */
    suspend fun enableByDefault()
```

In `BackupRepositoryImpl`:

```kotlin
    override suspend fun localRecoveryCode(): String? = identities.localMaster()

    override suspend fun enableByDefault() = store.setBackupEnabled(true)
```

- [ ] **Step 7: BackupScheduler registers before uploading**

In `handle`, after `if (!backupRepository.observeBackupEnabled().first()) return`:

```kotlin
        // Backup turned on during setup may not have reached the server yet.
        val identity = backupRepository.ensureIdentity(null)
        if (identity is LeaderboardResult.Err) {
            Log.w(TAG, "Backup identity not registered: ${identity.error}")
            return
        }
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, including the 5 new tests and the unchanged `RestoreBackupUseCaseTest`.

- [ ] **Step 9: Commit**

```bash
git add -A app/src/main/kotlin/com/monolith/app/data app/src/main/kotlin/com/monolith/app/domain/repository/BackupRepository.kt app/src/main/kotlin/com/monolith/app/service/BackupScheduler.kt app/src/test
git commit -m "feat: hand out a recovery code before the server knows it"
```

---

### Task 3: Write the code with the tag link

**Files:**
- Modify: `app/src/main/kotlin/com/monolith/app/domain/model/NfcTagLink.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/data/datastore/MonolithPreferences.kt:145-150, 431-460`
- Modify: `app/src/main/kotlin/com/monolith/app/domain/repository/TagProvisioner.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/nfc/NfcManager.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/domain/usecase/LinkNfcTagUseCase.kt`
- Create: `app/src/test/kotlin/com/monolith/app/domain/usecase/TagFakes.kt`
- Test: `app/src/test/kotlin/com/monolith/app/domain/usecase/LinkNfcTagUseCaseTest.kt`

**Interfaces:**
- Consumes: `TagCodeCodec` (Task 1), `BackupRepository.localRecoveryCode()` and `FakeBackupRepository` (Task 2).
- Produces:
  - `NfcTagLink.code: String? = null`, `NfcTagLink.codeFits: Boolean = true`
  - `TagProvisioner.provisionTag(tag: Tag, code: String?): NfcLinkResult`, `TagProvisioner.readCode(tag: Tag): String?`, `TagProvisioner.existingLink(tag: Tag, code: String): NfcTagLink`
  - `LinkNfcTagUseCase(tagProvisioner, blockRepository, backupRepository)`
  - Test fakes: `FakeTagProvisioner(result, codeOnTag, id)` with `provisionedCodes`, `provisionCount`, `readCount`; `allocateTag(): Tag`.

- [ ] **Step 1: Shared tag fakes**

Create `app/src/test/kotlin/com/monolith/app/domain/usecase/TagFakes.kt`:

```kotlin
package com.monolith.app.domain.usecase

import android.nfc.Tag
import com.monolith.app.domain.model.NfcLinkResult
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.TagLinkMode
import com.monolith.app.domain.repository.TagProvisioner

class FakeTagProvisioner(
    var result: NfcLinkResult = NfcLinkResult.Failure("unset"),
    var codeOnTag: String? = null,
    var id: String = "tag",
) : TagProvisioner {
    val provisionedCodes = mutableListOf<String?>()
    val provisionCount get() = provisionedCodes.size
    var readCount = 0
        private set

    override suspend fun provisionTag(tag: Tag, code: String?): NfcLinkResult {
        provisionedCodes += code
        return result
    }

    override fun identifyTag(tag: Tag): String = id

    override fun dispatchTechFor(tag: Tag): String? = null

    override fun readCode(tag: Tag): String? {
        readCount++
        return codeOnTag
    }

    override fun existingLink(tag: Tag, code: String) = NfcTagLink(
        uid = id,
        mode = TagLinkMode.SMART_NDEF,
        ndefUri = "monolith://tag/$id",
        linkedAtMillis = 0L,
        code = code,
    )
}

/**
 * android.nfc.Tag has no constructor to call, and the fakes never read it, so an empty instance
 * is all these tests need.
 */
fun allocateTag(): Tag {
    val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    val unsafe = unsafeField.get(null)
    val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
    return allocate.invoke(unsafe, Tag::class.java) as Tag
}
```

- [ ] **Step 2: Rewrite LinkNfcTagUseCaseTest with the new tests**

Replace the whole file:

```kotlin
package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.NfcLinkResult
import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.TagLinkMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkNfcTagUseCaseTest {

    private val tag = allocateTag()
    private val link = NfcTagLink(uid = "abc", mode = TagLinkMode.FALLBACK_UID, linkedAtMillis = 0L)
    private val code = "c".repeat(43)

    @Test
    fun `linking while monolith is off saves the tag`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Success(link))
        val blockRepository = FakeBlockRepository(initiallyActive = false, linkedTag = null)

        val result = LinkNfcTagUseCase(provisioner, blockRepository, FakeBackupRepository())(tag)

        assertEquals(NfcLinkResult.Success(link), result)
        assertEquals(link, blockRepository.observeLinkedTag().first())
    }

    @Test
    fun `an active monolith refuses to swap the key it is holding`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Success(link))
        val blockRepository = FakeBlockRepository(initiallyActive = true)

        val result = LinkNfcTagUseCase(provisioner, blockRepository, FakeBackupRepository())(tag)

        assertEquals(NfcLinkResult.Locked, result)
        assertEquals("a refused link must not write to the tag either", 0, provisioner.provisionCount)
    }

    @Test
    fun `a failed provision links nothing`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Failure("unreadable"))
        val blockRepository = FakeBlockRepository(initiallyActive = false, linkedTag = null)

        val result = LinkNfcTagUseCase(provisioner, blockRepository, FakeBackupRepository())(tag)

        assertEquals(NfcLinkResult.Failure("unreadable"), result)
        assertNull(blockRepository.observeLinkedTag().first())
    }

    @Test
    fun `with backup on the tag is given the recovery code`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Success(link))
        val backup = FakeBackupRepository(enabled = true).apply { localCode = code }

        LinkNfcTagUseCase(provisioner, FakeBlockRepository(initiallyActive = false), backup)(tag)

        assertEquals(listOf<String?>(code), provisioner.provisionedCodes)
    }

    @Test
    fun `with backup off the tag gets no code`() = runBlocking {
        val provisioner = FakeTagProvisioner(NfcLinkResult.Success(link))
        val backup = FakeBackupRepository(enabled = false).apply { localCode = code }

        LinkNfcTagUseCase(provisioner, FakeBlockRepository(initiallyActive = false), backup)(tag)

        assertEquals(listOf<String?>(null), provisioner.provisionedCodes)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.monolith.app.domain.usecase.LinkNfcTagUseCaseTest"`
Expected: FAIL, compilation errors (no `code` parameter on `NfcTagLink`, `provisionTag` overrides nothing, `LinkNfcTagUseCase` takes two arguments).

- [ ] **Step 4: NfcTagLink**

Add after `dispatchTech` in `NfcTagLink`:

```kotlin
    /**
     * The recovery code this tag was last seen carrying, written at link time or read on a tap.
     * Null when it carries none. Only ever set on [TagLinkMode.SMART_NDEF] links.
     */
    val code: String? = null,
    /** False once a write found this tag too small to hold the code alongside its URI. */
    val codeFits: Boolean = true,
```

- [ ] **Step 5: Persist the two fields**

In `MonolithPreferences.kt` `Keys`, after `TAG_DISPATCH_TECH`:

```kotlin
    val TAG_CODE = stringPreferencesKey("tag_code")
    val TAG_CODE_FITS = booleanPreferencesKey("tag_code_fits")
```

In `linkedTag`, add to the `NfcTagLink(...)` call:

```kotlin
            code = prefs[Keys.TAG_CODE],
            codeFits = prefs[Keys.TAG_CODE_FITS] ?: true,
```

In `saveLinkedTag`, at the end of the edit block:

```kotlin
            if (link.code != null) {
                prefs[Keys.TAG_CODE] = link.code
            } else {
                prefs.remove(Keys.TAG_CODE)
            }
            prefs[Keys.TAG_CODE_FITS] = link.codeFits
```

Then check every other place that removes `Keys.TAG_UID` (`grep -n "remove(Keys.TAG_" app/src/main/kotlin/com/monolith/app/data/datastore/MonolithPreferences.kt`) and remove `TAG_CODE` and `TAG_CODE_FITS` alongside it. If there is none, nothing to do.

- [ ] **Step 6: TagProvisioner**

Replace the interface body:

```kotlin
interface TagProvisioner {
    /** Writes Monolith's URI to [tag], and [code] beside it when given and the tag has room. */
    suspend fun provisionTag(tag: Tag, code: String?): NfcLinkResult

    /** Extracts a stable identifier (UID, or the NDEF URI if present) from a tapped tag. */
    fun identifyTag(tag: Tag): String

    /** The narrowest NFC technology [tag] can be listened for on, or null if there is none. */
    fun dispatchTechFor(tag: Tag): String?

    /**
     * The recovery code [tag] carries, from the NDEF message Android cached when it was
     * discovered. Null when there is none, or it does not decode for this tag. Writes nothing.
     */
    fun readCode(tag: Tag): String?

    /** The link for a tag Monolith already wrote, carrying [code], made without writing to it. */
    fun existingLink(tag: Tag, code: String): NfcTagLink
}
```

(add `import com.monolith.app.domain.model.NfcTagLink`).

- [ ] **Step 7: NfcManager**

Add imports `com.monolith.app.data.backup.BackupCrypto`, `com.monolith.app.data.backup.TagCodeCodec`. Replace `provisionTag` and `writeNdefUri` with:

```kotlin
    override suspend fun provisionTag(tag: Tag, code: String?): NfcLinkResult = withContext(Dispatchers.IO) {
        val uid = bytesToHex(tag.id)
        if (uid.isBlank()) {
            return@withContext NfcLinkResult.Failure("Tag has no readable identifier.")
        }

        val uri = "$TAG_BASE_URL$uid"
        val codeRecord = code?.let(BackupCrypto::decodeCode)?.let { codeRecord(tag.id, it) }
        val written = runCatching { writeNdef(tag, uri, codeRecord) }.getOrDefault(NdefWrite.FAILED)

        val link = when (written) {
            // Nothing to register: the tag now carries a monolith:// URI, and the NDEF filter
            // that matches it cannot be triggered by anybody else's tag.
            NdefWrite.WITH_CODE -> NfcTagLink(uid = uid, mode = TagLinkMode.SMART_NDEF, ndefUri = uri, code = code)
            // Asked for a code and got only the URI: the tag is too small, and stays quiet about it.
            NdefWrite.URI_ONLY -> NfcTagLink(uid = uid, mode = TagLinkMode.SMART_NDEF, ndefUri = uri, codeFits = codeRecord == null)
            NdefWrite.FAILED -> NfcTagLink(
                uid = uid,
                mode = TagLinkMode.FALLBACK_UID,
                ndefUri = null,
                dispatchTech = dispatchTechFor(tag),
            )
        }
        NfcLinkResult.Success(link)
    }

    override fun identifyTag(tag: Tag): String = bytesToHex(tag.id)

    override fun dispatchTechFor(tag: Tag): String? =
        NfcDispatchTech.narrowest(tag.techList.toList())

    override fun readCode(tag: Tag): String? {
        val message = Ndef.get(tag)?.cachedNdefMessage ?: return null
        val record = message.records.firstOrNull {
            it.tnf == NdefRecord.TNF_EXTERNAL_TYPE && String(it.type, Charsets.US_ASCII) == CODE_RECORD_TYPE
        } ?: return null
        return TagCodeCodec.decode(tag.id, record.payload)?.let(BackupCrypto::encodeCode)
    }

    override fun existingLink(tag: Tag, code: String): NfcTagLink {
        val uid = bytesToHex(tag.id)
        return NfcTagLink(uid = uid, mode = TagLinkMode.SMART_NDEF, ndefUri = "$TAG_BASE_URL$uid", code = code)
    }

    private enum class NdefWrite { WITH_CODE, URI_ONLY, FAILED }

    private fun codeRecord(uid: ByteArray, master: ByteArray): NdefRecord =
        NdefRecord.createExternal(CODE_DOMAIN, CODE_TYPE, TagCodeCodec.encode(uid, master))

    /** The URI always comes first: Android dispatches a background tap on the first record only. */
    private fun writeNdef(tag: Tag, uri: String, codeRecord: NdefRecord?): NdefWrite {
        val uriRecord = NdefRecord.createUri(uri)
        val uriOnly = NdefMessage(arrayOf(uriRecord))
        val withCode = codeRecord?.let { NdefMessage(arrayOf(uriRecord, it)) }

        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                when {
                    !ndef.isWritable -> NdefWrite.FAILED
                    withCode != null && withCode.byteArrayLength <= ndef.maxSize -> {
                        ndef.writeNdefMessage(withCode)
                        NdefWrite.WITH_CODE
                    }
                    uriOnly.byteArrayLength <= ndef.maxSize -> {
                        ndef.writeNdefMessage(uriOnly)
                        NdefWrite.URI_ONLY
                    }
                    else -> NdefWrite.FAILED
                }
            } catch (_: Exception) {
                NdefWrite.FAILED
            } finally {
                runCatching { ndef.close() }
            }
        }

        NdefFormatable.get(tag)?.let { formatable ->
            // A blank tag says nothing about its size until formatted, so try both records first
            // and fall back to the URI alone.
            if (withCode != null && format(formatable, withCode)) return NdefWrite.WITH_CODE
            return if (format(formatable, uriOnly)) NdefWrite.URI_ONLY else NdefWrite.FAILED
        }

        return NdefWrite.FAILED
    }

    private fun format(formatable: NdefFormatable, message: NdefMessage): Boolean = try {
        formatable.connect()
        formatable.format(message)
        true
    } catch (_: Exception) {
        false
    } finally {
        runCatching { formatable.close() }
    }
```

Delete the old `override fun identifyTag` and `override fun dispatchTechFor` (now inside the block above). In the companion object add:

```kotlin
        private const val CODE_DOMAIN = "monolith.app"
        private const val CODE_TYPE = "k"
        /** How Android spells an external record's type: lower-case "domain:type". */
        private const val CODE_RECORD_TYPE = "$CODE_DOMAIN:$CODE_TYPE"
```

- [ ] **Step 8: LinkNfcTagUseCase**

```kotlin
class LinkNfcTagUseCase @Inject constructor(
    private val tagProvisioner: TagProvisioner,
    private val blockRepository: BlockRepository,
    private val backupRepository: BackupRepository,
) {
    suspend operator fun invoke(tag: Tag): NfcLinkResult {
        // (existing comment about re-linking while on stays here)
        if (blockRepository.observeBlockState().first().isActive) {
            return NfcLinkResult.Locked
        }
        // The code rides along only while backup is on: with it off there is nothing behind it
        // for a reinstall to restore.
        val code = if (backupRepository.observeBackupEnabled().first()) backupRepository.localRecoveryCode() else null
        val result = tagProvisioner.provisionTag(tag, code)
        if (result is NfcLinkResult.Success) {
            blockRepository.saveLinkedTag(result.link)
        }
        return result
    }
}
```

(add `import com.monolith.app.domain.repository.BackupRepository`).

- [ ] **Step 9: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. If `ToggleBlockModeFromTagUseCase` or another caller fails to compile on `provisionTag`, it only ever calls `identifyTag`/`dispatchTechFor`; nothing else should break.

- [ ] **Step 10: Commit**

```bash
git add -A app/src/main/kotlin app/src/test
git commit -m "feat: write the recovery code to a writable tag when linking"
```

---

### Task 4: Know whether the tag's code is current

**Files:**
- Create: `app/src/main/kotlin/com/monolith/app/domain/model/TagCodeState.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/domain/usecase/ToggleBlockModeFromTagUseCase.kt`
- Test: `app/src/test/kotlin/com/monolith/app/domain/model/TagCodeStateTest.kt`
- Test: `app/src/test/kotlin/com/monolith/app/domain/usecase/ToggleBlockModeFromTagUseCaseTest.kt`

**Interfaces:**
- Consumes: `NfcTagLink.code/codeFits`, `TagProvisioner.readCode`, `FakeTagProvisioner`, `allocateTag` (Task 3).
- Produces: `enum class TagCodeState { CURRENT, MISSING, STALE }`, `fun tagCodeState(link: NfcTagLink?, backupEnabled: Boolean, recoveryCode: String?): TagCodeState?`

- [ ] **Step 1: Write the failing tests**

`TagCodeStateTest.kt`:

```kotlin
package com.monolith.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagCodeStateTest {

    private val code = "a".repeat(43)
    private val smart = NfcTagLink(uid = "u", mode = TagLinkMode.SMART_NDEF, ndefUri = "monolith://tag/u", linkedAtMillis = 0L)

    @Test
    fun `the code on the tag matching is current`() {
        assertEquals(TagCodeState.CURRENT, tagCodeState(smart.copy(code = code), backupEnabled = true, recoveryCode = code))
    }

    @Test
    fun `a writable tag without a code is missing it`() {
        assertEquals(TagCodeState.MISSING, tagCodeState(smart, backupEnabled = true, recoveryCode = code))
    }

    @Test
    fun `another code on the tag is stale`() {
        assertEquals(TagCodeState.STALE, tagCodeState(smart.copy(code = "b".repeat(43)), backupEnabled = true, recoveryCode = code))
    }

    @Test
    fun `a UID-only tag never comes up`() {
        val uidOnly = NfcTagLink(uid = "u", mode = TagLinkMode.FALLBACK_UID, linkedAtMillis = 0L)
        assertNull(tagCodeState(uidOnly, backupEnabled = true, recoveryCode = code))
    }

    @Test
    fun `a tag too small for the code never comes up`() {
        assertNull(tagCodeState(smart.copy(codeFits = false), backupEnabled = true, recoveryCode = code))
    }

    @Test
    fun `nothing comes up with backup off`() {
        assertNull(tagCodeState(smart, backupEnabled = false, recoveryCode = code))
    }

    @Test
    fun `nothing comes up before the identity has a code`() {
        // Offline setup: the tag carries a code the identity will only get once it registers.
        assertNull(tagCodeState(smart.copy(code = code), backupEnabled = true, recoveryCode = null))
    }

    @Test
    fun `no tag, nothing to say`() {
        assertNull(tagCodeState(null, backupEnabled = true, recoveryCode = code))
    }
}
```

`ToggleBlockModeFromTagUseCaseTest.kt`:

```kotlin
package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.NfcTagLink
import com.monolith.app.domain.model.TagLinkMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ToggleBlockModeFromTagUseCaseTest {

    private val tag = allocateTag()
    private val code = "a".repeat(43)
    private val smart = NfcTagLink(uid = "tag", mode = TagLinkMode.SMART_NDEF, ndefUri = "monolith://tag/tag", linkedAtMillis = 0L)

    private fun toggle(provisioner: FakeTagProvisioner, blocks: FakeBlockRepository) =
        ToggleBlockModeFromTagUseCase(provisioner, blocks, FakeAppUnlockRepository())

    @Test
    fun `a tap records the code the tag carries`() = runBlocking {
        val blocks = FakeBlockRepository(initiallyActive = false, linkedTag = smart)

        toggle(FakeTagProvisioner(codeOnTag = code), blocks)(tag)

        assertEquals(code, blocks.observeLinkedTag().first()!!.code)
    }

    @Test
    fun `a tap that reads no code keeps the one on record`() = runBlocking {
        // A missed read is not proof the code is gone; it must not raise the Settings row.
        val blocks = FakeBlockRepository(initiallyActive = false, linkedTag = smart.copy(code = code))

        toggle(FakeTagProvisioner(codeOnTag = null), blocks)(tag)

        assertEquals(code, blocks.observeLinkedTag().first()!!.code)
    }

    @Test
    fun `a UID-only tag is never read for a code`() = runBlocking {
        val uidOnly = NfcTagLink(uid = "tag", mode = TagLinkMode.FALLBACK_UID, linkedAtMillis = 0L, dispatchTech = "NfcA")
        val provisioner = FakeTagProvisioner(codeOnTag = code)

        toggle(provisioner, FakeBlockRepository(initiallyActive = false, linkedTag = uidOnly))(tag)

        assertEquals(0, provisioner.readCount)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.monolith.app.domain.model.TagCodeStateTest" --tests "com.monolith.app.domain.usecase.ToggleBlockModeFromTagUseCaseTest"`
Expected: FAIL: `tagCodeState` unresolved; `a tap records the code the tag carries` fails (code stays null).

- [ ] **Step 3: TagCodeState**

```kotlin
package com.monolith.app.domain.model

/** Whether the linked tag holds the recovery code a reinstall would need. */
enum class TagCodeState { CURRENT, MISSING, STALE }

/**
 * Null when the question does not apply: no tag, a UID-only tag, one too small for the code,
 * backup off, or an identity without a code yet. Those users never hear about codes on tags.
 */
fun tagCodeState(link: NfcTagLink?, backupEnabled: Boolean, recoveryCode: String?): TagCodeState? {
    if (link == null || link.mode != TagLinkMode.SMART_NDEF || !link.codeFits) return null
    if (!backupEnabled || recoveryCode == null) return null
    return when (link.code) {
        null -> TagCodeState.MISSING
        recoveryCode -> TagCodeState.CURRENT
        else -> TagCodeState.STALE
    }
}
```

- [ ] **Step 4: Toggle records the code it finds**

In `ToggleBlockModeFromTagUseCase.invoke`, right after the existing `FALLBACK_UID` dispatch-tech block:

```kotlin
        // What a writable tag carries can change behind this phone's back: re-linked on another
        // phone, or linked before codes were written. Only a code actually read is recorded; a
        // missed read is not proof the code is gone.
        if (linked.mode == TagLinkMode.SMART_NDEF) {
            tagProvisioner.readCode(tag)?.let { onTag ->
                if (onTag != linked.code) blockRepository.saveLinkedTag(linked.copy(code = onTag))
            }
        }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/kotlin/com/monolith/app/domain app/src/test
git commit -m "feat: notice when the linked tag's recovery code is missing or old"
```

---

### Task 5: Restore drops apps this phone does not have

**Files:**
- Modify: `app/src/main/kotlin/com/monolith/app/domain/repository/AppRepository.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/data/repository/AppRepositoryImpl.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/domain/usecase/RestoreBackupUseCase.kt`
- Modify: `app/src/test/kotlin/com/monolith/app/domain/usecase/FakeRepositories.kt:134-147`
- Test: `app/src/test/kotlin/com/monolith/app/domain/usecase/RestoreBackupUseCaseTest.kt`

**Interfaces:**
- Produces: `AppRepository.installedPackages(): Set<String>`; `RestoreBackupUseCase(blockRepository, backupRepository, leaderboardRepository, snapshotWriter, afterRestore, appRepository)`; `FakeAppRepository(blocked, labels, installed)`.

- [ ] **Step 1: Write the failing test**

In `RestoreBackupUseCaseTest.kt`, change the `Harness` to take installed packages and pass an app repository:

```kotlin
    private class Harness(
        active: Boolean,
        fetch: LeaderboardResult<FetchedBackup>,
        installed: Set<String> = setOf("com.example.feed"),
    ) {
        val blocks = FakeBlockRepository(initiallyActive = active)
        val backup = FakeBackupRepository(fetch)
        val leaderboard = FakeLeaderboardRepository()
        val writer = RecordingWriter()
        val after = CountingAfterRestore()
        val useCase = RestoreBackupUseCase(blocks, backup, leaderboard, writer, after, FakeAppRepository(installed = installed))
        // assertNothingWritten unchanged
    }
```

Add the test (import `com.monolith.app.data.backup.BackupSnapshot.PersonEntry`):

```kotlin
    @Test
    fun `apps this phone lacks are dropped from the restore`() = runBlocking {
        val full = snapshot.copy(
            blockedPackages = listOf("com.example.feed", "com.example.gone"),
            importantPeople = listOf(PersonEntry("com.example.chat", "Sam"), PersonEntry("com.example.gone", "Lea")),
        )
        val h = Harness(active = false, fetch = fetched(full), installed = setOf("com.example.feed", "com.example.chat"))

        h.useCase.prepare(code)
        assertEquals(RestoreOutcome.Done, h.useCase.confirm())

        val written = h.writer.writes.single()
        assertEquals(listOf("com.example.feed"), written.blockedPackages)
        assertEquals(listOf(PersonEntry("com.example.chat", "Sam")), written.importantPeople)
        assertEquals("history is never filtered", full.sessions, written.sessions)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.monolith.app.domain.usecase.RestoreBackupUseCaseTest"`
Expected: FAIL, compilation error (no `installed` parameter, constructor takes 5 arguments).

- [ ] **Step 3: AppRepository.installedPackages**

Interface, after `getInstalledApps`:

```kotlin
    /** The package names [getInstalledApps] would list, without loading a label or icon for each. */
    suspend fun installedPackages(): Set<String>
```

`AppRepositoryImpl`:

```kotlin
    override suspend fun installedPackages(): Set<String> = withContext(Dispatchers.IO) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .mapTo(mutableSetOf()) { it.activityInfo.packageName }
            .apply { remove(context.packageName) }
    }
```

`FakeAppRepository`: add constructor parameter `private val installed: Set<String> = emptySet(),` and

```kotlin
    override suspend fun installedPackages(): Set<String> = installed
```

- [ ] **Step 4: RestoreBackupUseCase filters**

Add constructor parameter `private val appRepository: AppRepository,` (last; import `com.monolith.app.domain.repository.AppRepository`). In `confirm`, replace `snapshotWriter.write(snapshot)` with:

```kotlin
        snapshotWriter.write(snapshot.keepingOnly(appRepository.installedPackages()))
```

At the bottom of the file:

```kotlin
/**
 * Drops what names an app this phone doesn't have: a blocked app that isn't installed shows in
 * the list as a bare package name, and an important person on a missing app can never get
 * through. Dropped rather than hidden, so the next upload matches what this phone has.
 */
private fun BackupSnapshot.keepingOnly(installed: Set<String>) = copy(
    blockedPackages = blockedPackages.filter { it in installed },
    importantPeople = importantPeople.filter { it.packageName in installed },
)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, including the existing `confirm writes the snapshot...` test (its one blocked app is in the default installed set).

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main/kotlin app/src/test
git commit -m "fix: leave out apps this phone doesn't have when restoring"
```

---

### Task 6: TagRestoreUseCase

**Files:**
- Create: `app/src/main/kotlin/com/monolith/app/domain/usecase/TagRestoreUseCase.kt`
- Test: `app/src/test/kotlin/com/monolith/app/domain/usecase/TagRestoreUseCaseTest.kt`

**Interfaces:**
- Consumes: `RestoreBackupUseCase.prepare/confirm` (+ Task 5 constructor), `TagProvisioner.readCode/existingLink` (Task 3), fakes from Tasks 2, 3, 5.
- Produces:

```kotlin
sealed interface TagCheck {
    data object NoCode : TagCheck
    data class Offer(val backupAt: Long?, val hasBackup: Boolean) : TagCheck
    data object Unreachable : TagCheck
    data object Unreadable : TagCheck
}
sealed interface TagRestoreResult {
    data class Done(val hasBackup: Boolean) : TagRestoreResult
    data object Failed : TagRestoreResult
}
class TagRestoreUseCase { suspend fun check(tag: Tag): TagCheck; suspend fun recheck(): TagCheck; suspend fun restore(): TagRestoreResult }
```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.monolith.app.domain.usecase

import com.monolith.app.data.backup.BackupSnapshot
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.model.LeaderboardResult
import com.monolith.app.domain.repository.FetchedBackup
import com.monolith.app.domain.repository.MeInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagRestoreUseCaseTest {

    private val tag = allocateTag()
    private val code = "c".repeat(43)
    private val me = MeInfo("Ana", emptyList(), backupAt = 1_700_000_100_000L)
    private val snapshot = BackupSnapshot(
        createdAt = 1_700_000_000_000L,
        sessions = emptyList(),
        blockedPackages = emptyList(),
        importantPeople = emptyList(),
        schedules = emptyList(),
        strictness = null,
    )

    private class Harness(codeOnTag: String?, fetch: LeaderboardResult<FetchedBackup>) {
        val provisioner = FakeTagProvisioner(codeOnTag = codeOnTag)
        val blocks = FakeBlockRepository(initiallyActive = false, linkedTag = null)
        val backup = FakeBackupRepository(fetch)
        val leaderboard = FakeLeaderboardRepository()
        val writer = RecordingWriter()
        val restore = RestoreBackupUseCase(blocks, backup, leaderboard, writer, CountingAfterRestore(), FakeAppRepository())
        val useCase = TagRestoreUseCase(provisioner, blocks, restore)
    }

    private fun fetched(snapshot: BackupSnapshot?) = LeaderboardResult.Ok(FetchedBackup(code, me, snapshot))

    @Test
    fun `a tag without a code has nothing to offer`() = runBlocking {
        val h = Harness(codeOnTag = null, fetch = fetched(snapshot))

        assertEquals(TagCheck.NoCode, h.useCase.check(tag))
        assertTrue("no code, no network", h.backup.fetchCalls.isEmpty())
    }

    @Test
    fun `a code with a backup is offered with its date`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = fetched(snapshot))

        assertEquals(TagCheck.Offer(backupAt = me.backupAt, hasBackup = true), h.useCase.check(tag))
    }

    @Test
    fun `a code with only friends is offered without a backup`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = fetched(null))

        assertEquals(TagCheck.Offer(backupAt = me.backupAt, hasBackup = false), h.useCase.check(tag))
    }

    @Test
    fun `a code whose identity is gone reads as a blank tag`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = LeaderboardResult.Err(LeaderboardError.UNAUTHORIZED))

        assertEquals(TagCheck.NoCode, h.useCase.check(tag))
    }

    @Test
    fun `offline is never mistaken for a blank tag`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = LeaderboardResult.Err(LeaderboardError.NETWORK))
        assertEquals(TagCheck.Unreachable, h.useCase.check(tag))

        h.backup.fetchResult = LeaderboardResult.Err(LeaderboardError.SERVER)
        assertEquals(TagCheck.Unreachable, h.useCase.check(tag))
    }

    @Test
    fun `an unreadable backup is reported`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = LeaderboardResult.Err(LeaderboardError.INVALID))

        assertEquals(TagCheck.Unreadable, h.useCase.check(tag))
    }

    @Test
    fun `recheck asks again with the same code`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = LeaderboardResult.Err(LeaderboardError.NETWORK))
        h.useCase.check(tag)

        h.backup.fetchResult = fetched(snapshot)
        assertEquals(TagCheck.Offer(backupAt = me.backupAt, hasBackup = true), h.useCase.recheck())
        assertEquals(listOf(code, code), h.backup.fetchCalls)
    }

    @Test
    fun `restore writes the backup and links the tag without writing to it`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = fetched(snapshot))
        h.useCase.check(tag)

        assertEquals(TagRestoreResult.Done(hasBackup = true), h.useCase.restore())
        assertEquals(1, h.writer.writes.size)
        assertEquals(h.provisioner.existingLink(tag, code), h.blocks.observeLinkedTag().first())
        assertEquals("the tag already carries this code", 0, h.provisioner.provisionCount)
    }

    @Test
    fun `a restore that fails after the offer links nothing`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = fetched(snapshot))
        h.useCase.check(tag)
        h.leaderboard.restoreResult = LeaderboardResult.Err(LeaderboardError.NETWORK)

        assertEquals(TagRestoreResult.Failed, h.useCase.restore())
        assertNull(h.blocks.observeLinkedTag().first())
        assertTrue(h.writer.writes.isEmpty())
    }

    @Test
    fun `restore without a check does nothing`() = runBlocking {
        val h = Harness(codeOnTag = code, fetch = fetched(snapshot))

        assertEquals(TagRestoreResult.Failed, h.useCase.restore())
        assertNull(h.blocks.observeLinkedTag().first())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.monolith.app.domain.usecase.TagRestoreUseCaseTest"`
Expected: FAIL, compilation error "Unresolved reference: TagRestoreUseCase".

- [ ] **Step 3: Write the implementation**

```kotlin
package com.monolith.app.domain.usecase

import android.nfc.Tag
import com.monolith.app.domain.model.LeaderboardError
import com.monolith.app.domain.repository.BlockRepository
import com.monolith.app.domain.repository.TagProvisioner
import javax.inject.Inject

/** What a tag tapped during setup could bring back. */
sealed interface TagCheck {
    /** No code, one that does not decode, or one whose identity is gone: a blank tag to link. */
    data object NoCode : TagCheck
    data class Offer(val backupAt: Long?, val hasBackup: Boolean) : TagCheck

    /** Offline or a server error. Never treated as a blank tag: that would overwrite the way back. */
    data object Unreachable : TagCheck
    data object Unreadable : TagCheck
}

sealed interface TagRestoreResult {
    data class Done(val hasBackup: Boolean) : TagRestoreResult
    data object Failed : TagRestoreResult
}

/**
 * The setup step's half of restoring from a tag: read the code it carries, ask what it would
 * restore, and on a yes restore it and link the tag as it is. Holds the tag and code between
 * [check] and [restore]; one instance per screen.
 */
class TagRestoreUseCase @Inject constructor(
    private val tagProvisioner: TagProvisioner,
    private val blockRepository: BlockRepository,
    private val restoreBackup: RestoreBackupUseCase,
) {
    private var tag: Tag? = null
    private var code: String? = null
    private var hasBackup = false

    suspend fun check(tag: Tag): TagCheck {
        this.tag = null
        code = null
        val found = tagProvisioner.readCode(tag) ?: return TagCheck.NoCode
        this.tag = tag
        code = found
        return recheck()
    }

    /** Asks again with the code [check] found, after a failure to reach the server. */
    suspend fun recheck(): TagCheck {
        val found = code ?: return TagCheck.NoCode
        return when (val outcome = restoreBackup.prepare(found)) {
            is RestoreOutcome.Ready -> {
                hasBackup = outcome.hasBackup
                TagCheck.Offer(outcome.backupAt, outcome.hasBackup)
            }
            is RestoreOutcome.Failed -> when (outcome.error) {
                LeaderboardError.UNAUTHORIZED -> TagCheck.NoCode
                LeaderboardError.INVALID -> TagCheck.Unreadable
                else -> TagCheck.Unreachable
            }
            // Monolith is on: linking refuses on its own, so there is nothing to offer.
            RestoreOutcome.Refused, RestoreOutcome.Done -> TagCheck.NoCode
        }
    }

    suspend fun restore(): TagRestoreResult {
        val tag = tag ?: return TagRestoreResult.Failed
        val code = code ?: return TagRestoreResult.Failed
        if (restoreBackup.confirm() != RestoreOutcome.Done) return TagRestoreResult.Failed
        // Linked only once the restore went through, and as it is: it already carries this code.
        blockRepository.saveLinkedTag(tagProvisioner.existingLink(tag, code))
        return TagRestoreResult.Done(hasBackup)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/monolith/app/domain/usecase/TagRestoreUseCase.kt app/src/test/kotlin/com/monolith/app/domain/usecase/TagRestoreUseCaseTest.kt
git commit -m "feat: restore from the code a tag carries"
```

---

### Task 7: Tag first in setup, with the restore offer

**Files:**
- Modify: `app/src/main/kotlin/com/monolith/app/ui/nfclink/NfcLinkViewModel.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/ui/nfclink/NfcLinkScreen.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/ui/navigation/MonolithNavHost.kt:30-87`
- Modify: `app/src/main/res/values/strings.xml` and `values-{de,es,fr,it,nl,pt-rBR}/strings.xml`

**Interfaces:**
- Consumes: `TagRestoreUseCase`, `TagCheck`, `TagRestoreResult` (Task 6); `BackupRepository.enableByDefault()` (Task 2); `NfcTagLink.code` (Task 3).
- Produces: `NfcLinkScreen(onBack, onboardingStep, onboardingSubtitle, onSkip, onLinked, offersRestore: Boolean = false, onRestored: (() -> Unit)? = null, viewModel)`.

No unit tests: the logic lives in `TagRestoreUseCase` (Task 6) and the view model is a thin mapping. Verified by build and on device (Task 9).

- [ ] **Step 1: Strings**

Add to `values/strings.xml`, next to the other `nfc_link_*` strings:

```xml
    <string name="nfc_link_carries_code">Your tag also restores your progress if you reinstall.</string>
    <string name="tag_restore_checking">Checking your tag…</string>
    <string name="tag_restore_restoring">Restoring…</string>
    <string name="tag_restore_title">Welcome back</string>
    <string name="tag_restore_body">Restore progress from %1$s?</string>
    <string name="tag_restore_friends_body">Restore your friends groups?</string>
    <string name="tag_restore_cta">Restore</string>
    <string name="tag_start_fresh">Start fresh</string>
    <string name="tag_check_failed">Can\'t check this tag\'s backup right now.</string>
    <string name="tag_try_again">Try again</string>
    <string name="tag_start_fresh_confirm">This replaces the recovery code on the tag.</string>
    <string name="tag_tap_again">Hold your tag again to save a new code</string>
```

`values-de`:

```xml
    <string name="nfc_link_carries_code">Ihr Tag stellt Ihren Fortschritt auch nach einer Neuinstallation wieder her.</string>
    <string name="tag_restore_checking">Tag wird geprüft…</string>
    <string name="tag_restore_restoring">Wird wiederhergestellt…</string>
    <string name="tag_restore_title">Willkommen zurück</string>
    <string name="tag_restore_body">Fortschritt vom %1$s wiederherstellen?</string>
    <string name="tag_restore_friends_body">Ihre Freundesgruppen wiederherstellen?</string>
    <string name="tag_restore_cta">Wiederherstellen</string>
    <string name="tag_start_fresh">Neu beginnen</string>
    <string name="tag_check_failed">Die Sicherung dieses Tags kann gerade nicht geprüft werden.</string>
    <string name="tag_try_again">Erneut versuchen</string>
    <string name="tag_start_fresh_confirm">Dadurch wird der Wiederherstellungscode auf dem Tag ersetzt.</string>
    <string name="tag_tap_again">Halten Sie Ihren Tag erneut an das Telefon, um einen neuen Code zu speichern</string>
```

`values-es`:

```xml
    <string name="nfc_link_carries_code">Su etiqueta también restaura su progreso si reinstala la app.</string>
    <string name="tag_restore_checking">Comprobando su etiqueta…</string>
    <string name="tag_restore_restoring">Restaurando…</string>
    <string name="tag_restore_title">Bienvenido de nuevo</string>
    <string name="tag_restore_body">¿Restaurar el progreso del %1$s?</string>
    <string name="tag_restore_friends_body">¿Restaurar sus grupos de amigos?</string>
    <string name="tag_restore_cta">Restaurar</string>
    <string name="tag_start_fresh">Empezar de cero</string>
    <string name="tag_check_failed">Ahora mismo no se puede comprobar la copia de esta etiqueta.</string>
    <string name="tag_try_again">Reintentar</string>
    <string name="tag_start_fresh_confirm">Esto reemplaza el código de recuperación de la etiqueta.</string>
    <string name="tag_tap_again">Acerque de nuevo su etiqueta para guardar un código nuevo</string>
```

`values-fr`:

```xml
    <string name="nfc_link_carries_code">Votre tag restaure aussi votre progression si vous réinstallez l\'app.</string>
    <string name="tag_restore_checking">Vérification de votre tag…</string>
    <string name="tag_restore_restoring">Restauration…</string>
    <string name="tag_restore_title">Bon retour</string>
    <string name="tag_restore_body">Restaurer la progression du %1$s ?</string>
    <string name="tag_restore_friends_body">Restaurer vos groupes d\'amis ?</string>
    <string name="tag_restore_cta">Restaurer</string>
    <string name="tag_start_fresh">Repartir de zéro</string>
    <string name="tag_check_failed">Impossible de vérifier la sauvegarde de ce tag pour l\'instant.</string>
    <string name="tag_try_again">Réessayer</string>
    <string name="tag_start_fresh_confirm">Cela remplace le code de récupération du tag.</string>
    <string name="tag_tap_again">Approchez de nouveau votre tag pour enregistrer un nouveau code</string>
```

`values-it`:

```xml
    <string name="nfc_link_carries_code">Il tag ripristina anche i suoi progressi se reinstalla l\'app.</string>
    <string name="tag_restore_checking">Controllo del tag…</string>
    <string name="tag_restore_restoring">Ripristino…</string>
    <string name="tag_restore_title">Bentornato</string>
    <string name="tag_restore_body">Ripristinare i progressi del %1$s?</string>
    <string name="tag_restore_friends_body">Ripristinare i suoi gruppi di amici?</string>
    <string name="tag_restore_cta">Ripristina</string>
    <string name="tag_start_fresh">Ricomincia da capo</string>
    <string name="tag_check_failed">Al momento non è possibile controllare il backup di questo tag.</string>
    <string name="tag_try_again">Riprova</string>
    <string name="tag_start_fresh_confirm">Questo sostituisce il codice di recupero sul tag.</string>
    <string name="tag_tap_again">Avvicini di nuovo il tag per salvare un nuovo codice</string>
```

`values-nl`:

```xml
    <string name="nfc_link_carries_code">Uw tag herstelt ook uw voortgang als u de app opnieuw installeert.</string>
    <string name="tag_restore_checking">Uw tag wordt gecontroleerd…</string>
    <string name="tag_restore_restoring">Herstellen…</string>
    <string name="tag_restore_title">Welkom terug</string>
    <string name="tag_restore_body">Voortgang van %1$s herstellen?</string>
    <string name="tag_restore_friends_body">Uw vriendengroepen herstellen?</string>
    <string name="tag_restore_cta">Herstellen</string>
    <string name="tag_start_fresh">Opnieuw beginnen</string>
    <string name="tag_check_failed">De back-up van deze tag kan nu niet worden gecontroleerd.</string>
    <string name="tag_try_again">Opnieuw proberen</string>
    <string name="tag_start_fresh_confirm">Hiermee wordt de herstelcode op de tag vervangen.</string>
    <string name="tag_tap_again">Houd uw tag nogmaals tegen de telefoon om een nieuwe code op te slaan</string>
```

`values-pt-rBR`:

```xml
    <string name="nfc_link_carries_code">Sua tag também restaura seu progresso se você reinstalar o app.</string>
    <string name="tag_restore_checking">Verificando sua tag…</string>
    <string name="tag_restore_restoring">Restaurando…</string>
    <string name="tag_restore_title">Bem-vindo de volta</string>
    <string name="tag_restore_body">Restaurar o progresso de %1$s?</string>
    <string name="tag_restore_friends_body">Restaurar seus grupos de amigos?</string>
    <string name="tag_restore_cta">Restaurar</string>
    <string name="tag_start_fresh">Começar do zero</string>
    <string name="tag_check_failed">Não é possível verificar o backup desta tag agora.</string>
    <string name="tag_try_again">Tentar novamente</string>
    <string name="tag_start_fresh_confirm">Isso substitui o código de recuperação na tag.</string>
    <string name="tag_tap_again">Encoste sua tag de novo para salvar um novo código</string>
```

- [ ] **Step 2: NfcLinkViewModel**

Replace the file:

```kotlin
package com.monolith.app.ui.nfclink

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.monolith.app.domain.model.NfcLinkResult
import com.monolith.app.domain.model.TagLinkMode
import com.monolith.app.domain.repository.BackupRepository
import com.monolith.app.domain.usecase.LinkNfcTagUseCase
import com.monolith.app.domain.usecase.TagCheck
import com.monolith.app.domain.usecase.TagRestoreResult
import com.monolith.app.domain.usecase.TagRestoreUseCase
import com.monolith.app.nfc.NfcBusMode
import com.monolith.app.nfc.NfcManager
import com.monolith.app.nfc.NfcTagBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface NfcLinkStatus {
    data object NfcUnsupported : NfcLinkStatus
    data object WaitingForTap : NfcLinkStatus
    data object Writing : NfcLinkStatus
    data class Success(val mode: TagLinkMode, val carriesCode: Boolean) : NfcLinkStatus
    data class Error(val message: String) : NfcLinkStatus

    /** Reached by tapping a tag on this screen while Monolith is active. */
    data object Locked : NfcLinkStatus

    // Setup only: the tapped tag carries a code from an earlier install.
    data object Checking : NfcLinkStatus
    data class OfferRestore(val backupAt: Long?, val hasBackup: Boolean) : NfcLinkStatus
    data object CheckFailed : NfcLinkStatus
    data object Unreadable : NfcLinkStatus
    data object Restoring : NfcLinkStatus
    data class Restored(val hasBackup: Boolean) : NfcLinkStatus

    /** Start fresh was chosen. The tag has usually left the phone by then, so it has to come back. */
    data object TapAgain : NfcLinkStatus
}

@HiltViewModel
class NfcLinkViewModel @Inject constructor(
    private val linkNfcTag: LinkNfcTagUseCase,
    private val tagRestore: TagRestoreUseCase,
    private val backupRepository: BackupRepository,
    private val nfcTagBus: NfcTagBus,
    nfcManager: NfcManager,
) : ViewModel() {

    private val _status = MutableStateFlow<NfcLinkStatus>(
        if (nfcManager.isNfcSupported) NfcLinkStatus.WaitingForTap else NfcLinkStatus.NfcUnsupported,
    )
    val status: StateFlow<NfcLinkStatus> = _status

    /** Set by the setup step: only there can a tapped tag bring an earlier install back. */
    var offersRestore = false

    init {
        nfcTagBus.setMode(NfcBusMode.LINKING)
        nfcTagBus.tagEvents.onEach(::onTag).launchIn(viewModelScope)
    }

    private suspend fun onTag(tag: Tag) {
        if (_status.value in BUSY || _status.value is NfcLinkStatus.Restored) return
        if (offersRestore && _status.value != NfcLinkStatus.TapAgain) {
            _status.value = NfcLinkStatus.Checking
            if (show(tagRestore.check(tag))) return
        }
        link(tag)
    }

    /** True when [check] needs an answer before anything is written to the tag. */
    private fun show(check: TagCheck): Boolean {
        _status.value = when (check) {
            TagCheck.NoCode -> return false
            is TagCheck.Offer -> NfcLinkStatus.OfferRestore(check.backupAt, check.hasBackup)
            TagCheck.Unreachable -> NfcLinkStatus.CheckFailed
            TagCheck.Unreadable -> NfcLinkStatus.Unreadable
        }
        return true
    }

    private suspend fun link(tag: Tag) {
        _status.value = NfcLinkStatus.Writing
        // A new install backs up by default. Turned on before linking, so the tag gets the code.
        if (offersRestore) backupRepository.enableByDefault()
        _status.value = when (val result = linkNfcTag(tag)) {
            is NfcLinkResult.Success -> NfcLinkStatus.Success(result.link.mode, carriesCode = result.link.code != null)
            is NfcLinkResult.Failure -> NfcLinkStatus.Error(result.reason)
            NfcLinkResult.Locked -> NfcLinkStatus.Locked
        }
    }

    fun restore() {
        if (_status.value !is NfcLinkStatus.OfferRestore) return
        _status.value = NfcLinkStatus.Restoring
        viewModelScope.launch {
            _status.value = when (val result = tagRestore.restore()) {
                is TagRestoreResult.Done -> {
                    // A restored backup turns itself back on; friends alone don't.
                    if (!result.hasBackup) backupRepository.enableByDefault()
                    NfcLinkStatus.Restored(result.hasBackup)
                }
                TagRestoreResult.Failed -> NfcLinkStatus.CheckFailed
            }
        }
    }

    fun tryAgain() {
        _status.value = NfcLinkStatus.Checking
        viewModelScope.launch {
            // Nothing to offer any more (the identity went meanwhile): a blank tag, still to write.
            if (!show(tagRestore.recheck())) startFresh()
        }
    }

    fun startFresh() {
        _status.value = NfcLinkStatus.TapAgain
    }

    /** Skipping setup's tag still leaves a new install backing up by default. */
    fun skip() {
        if (offersRestore) viewModelScope.launch { backupRepository.enableByDefault() }
    }

    fun retry() {
        _status.value = NfcLinkStatus.WaitingForTap
    }

    override fun onCleared() {
        nfcTagBus.setMode(NfcBusMode.TOGGLE)
        super.onCleared()
    }

    private companion object {
        val BUSY = setOf(NfcLinkStatus.Checking, NfcLinkStatus.Writing, NfcLinkStatus.Restoring)
    }
}
```

- [ ] **Step 3: NfcLinkScreen**

Signature gains two parameters (after `onLinked`):

```kotlin
    offersRestore: Boolean = false,
    onRestored: (() -> Unit)? = null,
```

At the top of the body, after `val status by ...`:

```kotlin
    val context = LocalContext.current
    var confirmFresh by remember { mutableStateOf(false) }
    SideEffect { viewModel.offersRestore = offersRestore }
    LaunchedEffect(status) {
        val restored = status as? NfcLinkStatus.Restored ?: return@LaunchedEffect
        // A backup brings the whole setup back; friends alone still need it walked through.
        if (restored.hasBackup) onRestored?.invoke() else onLinked?.invoke()
    }
```

In the `Success` branch, after the message `Text` and before its `Spacer(24)`:

```kotlin
                            if (current.carriesCode) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.nfc_link_carries_code),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
```

New `when` branches (before `NfcLinkStatus.Locked`):

```kotlin
                        NfcLinkStatus.Checking, NfcLinkStatus.Restoring, is NfcLinkStatus.Restored -> {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                            Spacer(Modifier.height(24.dp))
                            Text(
                                stringResource(
                                    if (current == NfcLinkStatus.Checking) R.string.tag_restore_checking else R.string.tag_restore_restoring,
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }

                        is NfcLinkStatus.OfferRestore -> {
                            Text(
                                stringResource(R.string.tag_restore_title),
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (current.hasBackup) {
                                    stringResource(R.string.tag_restore_body, current.backupAt?.let { formatDateTime(context, it) }.orEmpty())
                                } else {
                                    stringResource(R.string.tag_restore_friends_body)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = viewModel::restore, modifier = Modifier.fillMaxWidth(), shape = MonolithButtonShape) {
                                Text(stringResource(R.string.tag_restore_cta))
                            }
                            TextButton(onClick = viewModel::startFresh, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.tag_start_fresh))
                            }
                        }

                        NfcLinkStatus.CheckFailed -> {
                            Text(
                                stringResource(R.string.tag_check_failed),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = viewModel::tryAgain, shape = MonolithButtonShape) {
                                Text(stringResource(R.string.tag_try_again))
                            }
                            // The code on the tag could not be checked: overwriting it may lose the only way back.
                            TextButton(onClick = { confirmFresh = true }) {
                                Text(stringResource(R.string.tag_start_fresh))
                            }
                        }

                        NfcLinkStatus.Unreadable -> {
                            Text(
                                stringResource(R.string.backup_restore_unreadable),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            TextButton(onClick = viewModel::startFresh) {
                                Text(stringResource(R.string.tag_start_fresh))
                            }
                        }

                        NfcLinkStatus.TapAgain -> {
                            Icon(
                                Icons.Filled.Nfc,
                                contentDescription = null,
                                modifier = Modifier.height(96.dp),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                            Spacer(Modifier.height(24.dp))
                            Text(
                                stringResource(R.string.tag_tap_again),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
```

Replace the skip button block:

```kotlin
            val settled = status is NfcLinkStatus.Success || status is NfcLinkStatus.Restored || status == NfcLinkStatus.Restoring
            if (onSkip != null && !settled) {
                Button(
                    onClick = {
                        viewModel.skip()
                        onSkip()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MonolithButtonShape,
                ) {
                    Text(stringResource(R.string.not_now_cta))
                }
            }
```

After the `Scaffold` call closes:

```kotlin
    if (confirmFresh) {
        AlertDialog(
            onDismissRequest = { confirmFresh = false },
            text = { Text(stringResource(R.string.tag_start_fresh_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmFresh = false
                    viewModel.startFresh()
                }) { Text(stringResource(R.string.tag_start_fresh)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmFresh = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
```

Imports to add: `androidx.compose.material3.AlertDialog`, `androidx.compose.material3.TextButton`, `androidx.compose.runtime.LaunchedEffect`, `androidx.compose.runtime.SideEffect`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.setValue`, `androidx.compose.ui.platform.LocalContext`, `com.monolith.app.util.formatDateTime`.

- [ ] **Step 4: Navigation order**

In `MonolithNavHost.kt`:

```kotlin
/** Tag, name, apps, strictness. The permission step before them is not counted. */
private const val ONBOARDING_STEPS = 4
```

`Onboarding`'s `onFinished` navigates to `MonolithDestination.OnboardingNfcLink.route`.

`OnboardingNfcLink`:

```kotlin
        composable(MonolithDestination.OnboardingNfcLink.route) {
            NfcLinkScreen(
                onBack = { navController.popBackStack() },
                onboardingStep = 1 to ONBOARDING_STEPS,
                onboardingSubtitle = stringResource(R.string.onboarding_link_tag_subtitle),
                // First, so a tag from an earlier install can bring everything back before any of
                // it is set up by hand. Skipping still walks through the strictness step later,
                // which lets only Standard be picked until a tag exists.
                onSkip = { navController.navigate(MonolithDestination.OnboardingName.route) },
                onLinked = { navController.navigate(MonolithDestination.OnboardingName.route) },
                offersRestore = true,
                onRestored = { navController.navigate(MonolithDestination.OnboardingComplete.route) },
            )
        }
```

`OnboardingName`: `onboardingStep = 2 to ONBOARDING_STEPS`, `onContinue` unchanged (AppSelector).
`OnboardingAppSelector`: `onboardingStep = 3 to ONBOARDING_STEPS`, `onContinue = { navController.navigate(MonolithDestination.OnboardingStrictness.route) }`.
`OnboardingStrictness`: unchanged (`4 to ONBOARDING_STEPS`).

- [ ] **Step 5: Build**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main
git commit -m "feat: link the tag first in setup and offer to restore from it"
```

---

### Task 8: Settings row and captions

**Files:**
- Modify: `app/src/main/kotlin/com/monolith/app/ui/settings/SettingsViewModel.kt:39-88`
- Modify: `app/src/main/kotlin/com/monolith/app/ui/settings/BackupSection.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/ui/settings/SettingsScreen.kt:180-185`
- Modify: `app/src/main/res/values/strings.xml` and the six translations

**Interfaces:**
- Consumes: `tagCodeState`, `TagCodeState` (Task 4); `ObserveLinkedTagUseCase` (exists, already injected).
- Produces: `BackupUiState.tagCode: TagCodeState?`, `BackupUiState.tagCarriesCode: Boolean`; `BackupSection(state, isLocked, onToggle, onRestore, onSaveToTag)`.

- [ ] **Step 1: Strings**

`values`:

```xml
    <string name="backup_save_to_tag">Save recovery code to tag</string>
    <string name="backup_tag_missing">Your tag doesn\'t have your recovery code yet</string>
    <string name="backup_tag_stale">Your tag has an old recovery code</string>
    <string name="backup_code_warning_tag">Anyone with this code can restore your data. Your tag carries it too, so keep both private.</string>
```

`values-de`:

```xml
    <string name="backup_save_to_tag">Wiederherstellungscode auf Tag speichern</string>
    <string name="backup_tag_missing">Ihr Tag hat Ihren Wiederherstellungscode noch nicht</string>
    <string name="backup_tag_stale">Ihr Tag hat einen alten Wiederherstellungscode</string>
    <string name="backup_code_warning_tag">Wer diesen Code hat, kann Ihre Daten wiederherstellen. Ihr Tag trägt ihn auch, halten Sie also beide geheim.</string>
```

`values-es`:

```xml
    <string name="backup_save_to_tag">Guardar el código de recuperación en la etiqueta</string>
    <string name="backup_tag_missing">Su etiqueta aún no tiene su código de recuperación</string>
    <string name="backup_tag_stale">Su etiqueta tiene un código de recuperación antiguo</string>
    <string name="backup_code_warning_tag">Cualquiera con este código puede restaurar sus datos. Su etiqueta también lo lleva, así que mantenga ambos en privado.</string>
```

`values-fr`:

```xml
    <string name="backup_save_to_tag">Enregistrer le code de récupération sur le tag</string>
    <string name="backup_tag_missing">Votre tag n\'a pas encore votre code de récupération</string>
    <string name="backup_tag_stale">Votre tag a un ancien code de récupération</string>
    <string name="backup_code_warning_tag">Toute personne ayant ce code peut restaurer vos données. Votre tag le porte aussi, gardez donc les deux privés.</string>
```

`values-it`:

```xml
    <string name="backup_save_to_tag">Salva il codice di recupero sul tag</string>
    <string name="backup_tag_missing">Il suo tag non ha ancora il codice di recupero</string>
    <string name="backup_tag_stale">Il suo tag ha un codice di recupero vecchio</string>
    <string name="backup_code_warning_tag">Chiunque abbia questo codice può ripristinare i suoi dati. Anche il tag lo contiene, quindi li tenga privati entrambi.</string>
```

`values-nl`:

```xml
    <string name="backup_save_to_tag">Herstelcode op tag opslaan</string>
    <string name="backup_tag_missing">Uw tag heeft uw herstelcode nog niet</string>
    <string name="backup_tag_stale">Uw tag heeft een oude herstelcode</string>
    <string name="backup_code_warning_tag">Iedereen met deze code kan uw gegevens herstellen. Uw tag bevat hem ook, dus houd beide privé.</string>
```

`values-pt-rBR`:

```xml
    <string name="backup_save_to_tag">Salvar o código de recuperação na tag</string>
    <string name="backup_tag_missing">Sua tag ainda não tem seu código de recuperação</string>
    <string name="backup_tag_stale">Sua tag tem um código de recuperação antigo</string>
    <string name="backup_code_warning_tag">Qualquer pessoa com este código pode restaurar seus dados. Sua tag também o carrega, então mantenha os dois em sigilo.</string>
```

- [ ] **Step 2: SettingsViewModel**

`BackupUiState` gains:

```kotlin
    /** Null unless the Settings row should offer to put the code on the tag. */
    val tagCode: TagCodeState? = null,
    /** The tag carries a code, so the privacy caption names it too. */
    val tagCarriesCode: Boolean = false,
```

Replace `backupState` (keep `observeLinkedTag` as a constructor parameter and use it here; it is already used for `uiState`, so call it once and share the flow):

```kotlin
    private val linkedTag = observeLinkedTag()

    val backupState: StateFlow<BackupUiState> = combine(
        backupRepository.observeBackupEnabled(),
        backupRepository.observeLastBackupAt(),
        backupRepository.observeRecoveryCode(),
        backupBusy,
        linkedTag,
    ) { enabled, lastBackupAt, code, busy, tag ->
        val state = tagCodeState(tag, enabled, code)
        BackupUiState(
            enabled = enabled,
            lastBackupAt = lastBackupAt,
            recoveryCode = code,
            busy = busy,
            // CURRENT needs no row: the tag already does its job.
            tagCode = state?.takeIf { it != TagCodeState.CURRENT },
            tagCarriesCode = tag?.code != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BackupUiState())
```

Where `uiState` calls `observeLinkedTag()`, use `linkedTag` instead. Imports: `com.monolith.app.domain.model.TagCodeState`, `com.monolith.app.domain.model.tagCodeState`. `observeLinkedTag` must stay a constructor parameter (not `private val`) but is now read in a property initializer; declare `linkedTag` before both `uiState` and `backupState`.

- [ ] **Step 3: BackupSection**

Signature gains `onSaveToTag: () -> Unit` after `onRestore`. After the recovery-code block and before the restore row's divider:

```kotlin
            state.tagCode?.let { tagCode ->
                SettingsDivider(startInset = 20.dp)
                SettingsRow(
                    icon = Icons.Filled.Nfc,
                    label = stringResource(R.string.backup_save_to_tag),
                    enabled = !isLocked,
                    onClick = onSaveToTag,
                )
                Text(
                    stringResource(
                        when {
                            isLocked -> R.string.backup_restore_refused
                            tagCode == TagCodeState.STALE -> R.string.backup_tag_stale
                            else -> R.string.backup_tag_missing
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                )
            }
```

Replace the warning caption's string with:

```kotlin
            stringResource(if (state.tagCarriesCode) R.string.backup_code_warning_tag else R.string.backup_code_warning),
```

Imports: `androidx.compose.material.icons.filled.Nfc`, `com.monolith.app.domain.model.TagCodeState`.

- [ ] **Step 4: SettingsScreen**

```kotlin
                BackupSection(
                    state = backupState,
                    isLocked = uiState.isLocked,
                    onToggle = viewModel::setBackupEnabled,
                    onRestore = { showRestore = true },
                    // Re-linking the same tag rewrites both records.
                    onSaveToTag = onLinkTag,
                )
```

- [ ] **Step 5: Build**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main
git commit -m "feat: offer to save the recovery code to the tag from Settings"
```

---

### Task 9: Verify

- [ ] **Step 1: Full test suite and release build**

Run: `./gradlew :app:testDebugUnitTest assembleRelease`
Expected: BUILD SUCCESSFUL; APK at `app/build/outputs/apk/release/`.

- [ ] **Step 2: Translation coverage**

Run: `for l in de es fr it nl pt-rBR; do for k in nfc_link_carries_code tag_restore_checking tag_restore_restoring tag_restore_title tag_restore_body tag_restore_friends_body tag_restore_cta tag_start_fresh tag_check_failed tag_try_again tag_start_fresh_confirm tag_tap_again backup_save_to_tag backup_tag_missing backup_tag_stale backup_code_warning_tag; do grep -q "name=\"$k\"" app/src/main/res/values-$l/strings.xml || echo "missing $l $k"; done; done`
Expected: no output.

- [ ] **Step 3: On device (needs a phone and a writable NTAG tag; no emulator)**

Use the debug build (`com.monolith.app.debug`); never restore or uninstall the real install.

1. Fresh debug install, go through permissions: the first numbered step is the tag (1/4).
2. Tap a blank writable tag: "Tag successfully linked" plus "Your tag also restores your progress if you reinstall."; continues to name.
3. Finish setup, block a few apps including one not installed on the restore phone if possible, wait for a backup (Settings shows "Last backed up").
4. Clear the debug app's data, set up again, tap the same tag at step 1: "Welcome back. Restore progress from <date>?" Restore lands on the completion screen; history and setup are back; an app missing from the phone is not in the blocked list.
5. Repeat 4 with airplane mode on: "Can't check this tag's backup right now."; Start fresh asks to confirm; Try again works once back online.
6. Repeat with a UID-only tag (e.g. a transit card or a read-only tag): "UID successfully stored", no line about the code, no Settings row.
7. On the real install, after updating: Settings shows "Save recovery code to tag" with "Your tag doesn't have your recovery code yet" (only if the tag is writable and backup is on); tapping it and re-linking hides the row.
