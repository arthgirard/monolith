# Recovery code on the tag

Builds on `2026-09-28-encrypted-backup-design.md`. That spec stands unless this document changes it.

## Why

After a reinstall, getting progress back means finding the recovery code and pasting it. Most
people never saved it. The tag is the one thing a Monolith user is guaranteed to keep, so linking
a writable tag should also store the recovery code on it, and re-linking that tag on a fresh
install should offer to restore everything.

## Decisions

- **The code is hidden on the tag, not protected.** It is encrypted with a key derived from the
  tag's UID and an app constant. A generic NFC reader app shows opaque bytes; someone who reads
  Monolith's source can still recover it. Accepted because whoever holds the tag can already turn
  Monolith off: the tag is already a key.
- **Written only on writable tags, only while backup is on.** UID-only tags (`FALLBACK_UID`) and
  NDEF tags too small for both records are linked exactly as today. For them the feature does not
  exist: no message, no link-screen line, no Settings row, no tag-state check.
- **Tag linking becomes the first onboarding step.** Order: permissions, tag (1/4), name, apps,
  strictness, complete.
- **Restore is a one-tap confirm, never silent.** A tag can belong to someone else.
- **Backup is on by default for new installs only.** Onboarding turns it on when setup completes
  (not at the tag step, so the first upload carries the apps chosen after it); the tag step still
  writes the code. Existing installs keep their stored value; one that never touched the toggle
  stays off.
- **Restore drops apps this phone does not have.** Blocked apps and important people whose app is
  not installed are removed from the snapshot before it is written. This applies to every restore,
  including the paste-the-code one.
- **Existing users** save the code to their tag from a Settings row. No Home card.
- **No server changes.**

## Tag format

Two NDEF records, in this order:

1. `monolith://tag/<UID>`, unchanged. Android dispatches on the first record only, so background
   tap routing is untouched.
2. NDEF external record, type `monolith.app:k`, payload:
   `0x01 (version) ‖ 12-byte random nonce ‖ AES-256-GCM(tagKey, nonce, master)` (61 bytes; the GCM
   tag is appended by the cipher).

`tagKey = HKDF-SHA256(ikm = UID bytes, salt = "monolith", info = "tag v1", 32 bytes)`. Binding the
key to the UID means a record copied onto another tag does not decode.

Both records together are about 110 bytes, inside NTAG213's 144. Before writing, `NfcManager`
checks `ndef.maxSize` against the two-record message; if it does not fit, it writes the URI record
alone and the link is treated as a tag without a code.

`TagCodeCodec` (pure JVM, next to `BackupCrypto`): `encode(uid: ByteArray, master: ByteArray):
ByteArray` and `decode(uid: ByteArray, payload: ByteArray): ByteArray?` (null on a wrong UID, a
tampered payload, or an unknown version byte). It reuses `BackupCrypto`'s HKDF and AES-GCM.

## Android

### Master before registration

Writing the code during onboarding cannot wait for the network.

- `IdentityStore` adds `unregistered_master`.
- `IdentityManager.localMaster()`: returns the stored identity's master, else the unregistered
  master, else generates one and stores it as unregistered.
- `register()` uses the unregistered master when present instead of generating a new one, and
  clears it when the identity is saved.
- `BackupScheduler` already calls `ensureIdentity` on app open while backup is on, so an offline
  link registers on the next online moment.

### Linking

`TagProvisioner.provisionTag(tag, master: ByteArray?)`. `LinkNfcTagUseCase` passes
`localMaster()` when backup is on, null otherwise. It still returns `Locked` while Monolith is on.

`NfcTagLink` adds `code: String?` (the recovery code last written to or read from the tag; null when
it carries none) and `codeFits: Boolean` (false once a write found the tag too small for record 2).
A link "carries a code" when `code != null`. Everything visible in this spec keys off these two.

`TagProvisioner` also gains `readCode(tag): String?`, which decodes record 2 from the NDEF message
Android cached at discovery (no I/O, nothing written), and `existingLink(tag, code): NfcTagLink`,
which describes a Monolith-written tag without writing to it.

### Tag state check

Only for `SMART_NDEF` links. On each recognised tap (background or foreground),
`ToggleBlockModeFromTagUseCase` reads the tag's code with `readCode` and, when it differs from
`link.code`, saves the link with the code it found. The state is derived, never stored:

- `CURRENT`: `link.code` equals the current recovery code.
- `MISSING`: `link.code` is null.
- `STALE`: `link.code` is another code (the identity was cleared and made again).

There is no state at all (nothing shown) for a `FALLBACK_UID` link, a link with `codeFits = false`,
or while backup is off. An existing user whose writable tag predates this feature has
`code = null` and `codeFits = true`, so they see `MISSING` and the Settings row appears.

### Onboarding tag step

On a tap, read the tag first and write nothing. Then:

| Tag | Result |
|---|---|
| UID-only, or no record 2, or record 2 does not decode | Link as today (writing a code when backup is on and the tag allows it), continue to name |
| Record 2 decodes, `prepare(code)` is `Ready(hasBackup = true)` | "Welcome back. Restore progress from <date>?" |
| Record 2 decodes, `Ready(hasBackup = false)` | "Welcome back. Restore your friends groups?" |
| `Failed(UNAUTHORIZED)` (identity gone) | Treat as a blank tag: link, write a new code, say nothing |
| `Failed(NETWORK)` or `Failed(SERVER)` | "Can't check this tag's backup right now." with Try again and Start fresh |
| `Failed(INVALID)` (backup does not decrypt) | "This backup can't be read." with Start fresh |

- **Restore:** `confirm()`, then save the tag link with `existingLink` (the tag already carries
  this code, so nothing is written). With a backup, go straight to complete, skipping name, apps
  and strictness. Friends only: continue to name.
- **Start fresh:** the tag has usually left the phone by the time the button is pressed, so the
  step asks "Hold your tag again to save a new code". The next tap skips the check, writes a new
  code over the old one and continues to name. When the backup could not be checked, Start fresh
  first confirms: "This replaces the recovery code on the tag."
- **Skip** on the tag step continues to name.

`ONBOARDING_STEPS` stays 4; the step numbers move with the new order.

### Restore filtering

`RestoreBackupUseCase.confirm()` filters the snapshot before `snapshotWriter.write`:
`blockedPackages` keeps only packages in `AppRepository.installedPackages()` (new: the launcher
package names, without loading labels or icons), and `importantPeople`
keeps only entries whose `packageName` is installed. History, schedules and strictness are written
as they are. Dropped entries are gone; installing the app later does not bring them back.

### Settings (Backup section)

- New row under "Show recovery code": "Save recovery code to tag". Shown only when the tag state
  is `MISSING` or `STALE`. Caption: "Your tag doesn't
  have your recovery code yet" (`MISSING`) or "Your tag has an old recovery code" (`STALE`). While
  Monolith is on the row is disabled with "Turn Monolith off with your tag first." Tapping opens
  the link screen; linking the same tag rewrites both records.
- The privacy caption reads "Anyone with this code can restore your data. Your tag carries it too,
  so keep both private." only when the linked tag carries a code; otherwise it is unchanged.

### Link screen

After a link that wrote the code, one muted line: "Your tag also restores your progress if you
reinstall." Nothing for a link without a code.

## Testing

- `TagCodeCodec`: round trip; wrong UID, a tampered byte and an unknown version all return null;
  the two-record message for a 7-byte UID stays under 144 bytes.
- `IdentityManager`: `localMaster()` is stable across calls; `register()` uses the unregistered
  master and clears it (fake API).
- `LinkNfcTagUseCase`: passes a master when backup is on, none when off; still `Locked` while
  active.
- Onboarding tag step: every row of the table above, with fakes.
- Tag state: `CURRENT`, `MISSING`, `STALE` derived correctly; no state for UID-only links, for
  `codeFits = false`, or with backup off; a tap that finds a different code on the tag updates the
  link.
- Restore filtering: uninstalled blocked apps and important people dropped; installed ones kept;
  history untouched.
- On device (needs a phone and a writable tag, no emulator): link on the debug build, clear the
  debug app's data, re-link and restore; check history, setup, and that an app missing from the
  phone is not in the list. Repeat with a UID-only tag and confirm nothing about the code appears.
  The real install is never restored or uninstalled.
