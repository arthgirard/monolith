# Encrypted backup with the recovery code

Builds on `2026-09-28-friends-leaderboard-design.md` and `2026-09-28-friends-multi-group-design.md`.
Those specs stand unless this document changes them.

## Why

The recovery code only restores the leaderboard identity and groups. A new phone starts with no
history and no setup. Users want the recovery code to bring back their time gained history and
their app setup, whether or not they use the friends feature.

## Decisions

- **What is restored:** time gained history (every retained block session) and app setup (blocked
  apps, important people, schedules, strictness level). Not restored: NFC tag link, block mode
  state, bypass, app unlocks, live session start, block hits, pause log, code breakers, schedule
  alarm watermark.
- **Everyone can back up**, from app Settings, with or without friends groups. Friends reuses the
  same identity and recovery code.
- **Automatic** background upload.
- **"Show recovery code" moves to app Settings** (out of the Friends settings sheet).
- **End-to-end encrypted.** The server stores an opaque blob it cannot decrypt. Losing the recovery
  code loses the backup.
- **Restore is refused while Monolith is on**, so restoring an old or empty setup can't bypass the
  tag.
- **Restore replaces** local history and setup after a confirmation; it does not merge.
- **Turning backup off deletes the server copy.** Leaving the last friends group no longer deletes
  a user who has a backup.
- Storage in D1, blob capped at 1 MB. The 120-day inactivity rule counts a sync or a backup upload
  as activity.

## Key scheme

The recovery code is a 32-byte master secret generated on the phone, shown as 43 base64url
characters. It never leaves the phone.

```
master
 ├─ token = base64url(HKDF-SHA256(ikm = master, salt = "monolith", info = "auth v1", 32 bytes))
 │     sent as the Bearer token; the server stores only SHA-256(token)
 └─ key   = HKDF-SHA256(ikm = master, salt = "monolith", info = "backup v1", 32 bytes)
       AES-256 key, never leaves the phone
```

The server sees only the token, from which neither the master nor the key can be derived.

Blob format: `0x01 (version) ‖ 12-byte random nonce ‖ AES-256-GCM(key, nonce, gzip(snapshot JSON))`
(the GCM tag is appended by the cipher). The server never parses it.

## Server

### Schema (`server/migrations/0003_backup.sql`)

```sql
CREATE TABLE backups (
  user_id    TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  blob       BLOB NOT NULL,
  updated_at INTEGER NOT NULL
);
```

A user without a display name (backup only) is stored with `display_name = ''`.

### Identity

- The server no longer generates tokens. `POST /groups` and `POST /join` require a bearer token;
  without one they return 401.
- `POST /users {token, displayName?}` → `201 {}`. `token` must be 43 base64url characters; a token
  whose hash is already registered → `409 token_taken`. `displayName`, if present, follows the
  existing 1 to 24 rule; absent means `''`.
- `POST /groups` and `POST /join` with a user whose display name is `''` require `displayName` in
  the body (400 `invalid_body` otherwise) and store it. With a non-empty stored name the body's
  `displayName` is ignored.
- `POST /me/token {token}` (bearer: current token) replaces the stored token hash with the hash of
  the new token (same format rule, `409 token_taken` on collision) → `204`. Used once to move an
  identity created by an earlier build (server-issued token) onto a phone-generated master.
- `GET /me` adds `backupAt: number | null` (the backup's `updated_at`).

### Backup endpoints (bearer required)

| Method | Path | Body | Response |
|---|---|---|---|
| PUT | `/backup` | raw bytes, `content-type: application/octet-stream`, 1 to 1,048,576 bytes | 204; larger → `413 too_large`; empty → 400 |
| GET | `/backup` | | 200 raw bytes (`application/octet-stream`); none → `404 no_backup` |
| DELETE | `/backup` | | 204 (idempotent) |

### Invariants (changes)

- Invariant 4 becomes: a user with no memberships **and no backup** is deleted, with their days.
- `DELETE /backup` runs invariant 4 for the caller.
- Inactive cleanup: a user is stale when `COALESCE(MAX(last_sync_at, backup updated_at), created_at)`
  is older than 120 days. Board cleanup keeps its current trigger and uses this definition.
  `PUT /backup` additionally deletes up to 50 stale users that have no memberships (backup-only),
  lazily.

## Android

### Identity and keys

- `IdentityStore` adds `master` (base64url) and `backup_enabled`, `last_backup_at`.
- `BackupCrypto` (pure JVM, no new dependencies): `hkdf`, `deriveToken(master)`,
  `deriveKey(master)`, `encrypt(key, plain): ByteArray`, `decrypt(key, blob): ByteArray`
  (throws on a wrong key, a tampered blob, or an unknown version byte).
- Every new identity (first create/join, or turning backup on) is created by generating a master,
  deriving the token, and calling `POST /users`, then proceeding.
- An existing identity without a master (created by an earlier build) is migrated once, when the
  app next runs online with that identity: generate a master, derive a token, `POST /me/token`,
  store both. Until it succeeds, "Show recovery code" is hidden.
- The recovery code shown to the user is the master. Restore accepts a master.

### Snapshot

`BackupSnapshot` (kotlinx.serialization), version 1:
`{version, createdAt, sessions: [{start, end}], blockedPackages, importantPeople: [{packageName,
name?, handle?}], schedules: [{id, enabled, days, startMinuteOfDay}], strictness}`.
Read from and written to `MonolithPreferences` through two methods, `exportSnapshot()` and
`restoreSnapshot(snapshot)`; the restore is one atomic DataStore edit that replaces exactly the
included keys and leaves every excluded key untouched.

### Upload

`BackupScheduler` (app-scoped, started from `MonolithApplication`, idle unless backup is enabled):
watches sessions, blocked apps, important people, schedules and strictness (each
`distinctUntilChanged`), debounces 30 s, and uploads when the last upload is older than 3 hours;
also checks on app open. The first upload after enabling is immediate. Failures are logged and
swallowed (catching `Exception`, rethrowing `CancellationException`); the next trigger retries.

### Restore

Entry points: Settings "Restore from a backup", and the first-use Friends screen's "Restore with a
recovery code".

1. User pastes the code; the phone derives the token and key.
2. `GET /me` (401 → "That recovery code doesn't match anyone"), then `GET /backup`.
3. If Monolith is active: refuse with "Turn Monolith off with your tag first." Nothing is written.
4. If a backup exists: decrypt ("This backup can't be read." on failure) and show a confirmation
   naming the backup's date. On confirm: `restoreSnapshot`, store the master and token, refresh
   the widget, re-arm the schedule alarm, turn backup on.
5. If no backup exists: store the identity (groups come back) and say "No backup found. Your
   friends groups are back."

### Settings screen

A new "Backup" section (SettingsGroup card):
- `SettingsToggleRow` "Back up automatically".
- Caption under the toggle: "Last backed up <relative time>" (time in mono) or "Not backed up yet".
- Row "Show recovery code" → reveals the code (mono, SelectionContainer) with a copy button using
  the sensitive clipboard; hidden until a master exists.
- Row "Restore from a backup" → dialog with a code field (password keyboard, no autocorrect).
- Muted caption below the card: "Anyone with this code can restore your data. Keep it private."

The Friends settings sheet's "You, in every group" section keeps only the display name.

## Testing

- Server: `POST /users` (format, `token_taken`), create/join requiring a bearer, empty-name users
  must supply a name, `POST /me/token` rotation keeps groups, backup PUT/GET/DELETE (size limits,
  404, idempotent delete), invariant 4 with and without a backup, stale backup-only cleanup.
- Android: HKDF against RFC 5869 test case 1; deriveToken/deriveKey differ and are deterministic;
  encrypt/decrypt round trip; wrong key, tampered byte and unknown version all fail; snapshot
  round trip and unknown-version rejection; restore refused while active and replaces data when
  inactive (fake preferences); scheduler throttle as a pure function; legacy identity migration
  (fake API).
- On device: back up from the real install; restore onto the debug build (separate app id on the
  same phone) and compare history and setup; the real install is never restored or uninstalled.
