# Encrypted Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The recovery code restores the user's time gained history and app setup on a new phone, from an end-to-end encrypted backup that uploads automatically.

**Architecture:** The recovery code becomes a phone-generated 32-byte master. HKDF derives the Bearer token (the server stores only its SHA-256) and a separate AES key that never leaves the phone. The phone uploads `version ‖ nonce ‖ AES-GCM(gzip(snapshot JSON))` to a `backups` table; restoring downloads, decrypts and replaces sessions and setup in one DataStore edit, refused while Monolith is on.

**Tech Stack:** TypeScript Cloudflare Worker + D1 + vitest; Kotlin, `javax.crypto` (HMAC-SHA256, AES/GCM/NoPadding), `java.util.zip`, kotlinx.serialization, DataStore, Compose, Hilt, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-28-encrypted-backup-design.md` (builds on the friends leaderboard and multi-group specs)

## Global Constraints

- Commit messages: conventional prefix, no Co-Authored-By or AI attribution trailer, no em-dashes.
- No new npm runtime dependencies; no new Gradle dependencies (crypto and gzip from the JDK/Android platform only).
- Token and recovery code: 43 base64url characters (32 bytes, no padding).
- HKDF-SHA256 with salt `"monolith"`; info `"auth v1"` for the token, `"backup v1"` for the AES key; 32-byte outputs.
- Blob: `0x01 ‖ 12-byte nonce ‖ AES-256-GCM ciphertext+tag` of gzip(snapshot JSON).
- Backup size 1 to 1,048,576 bytes; `413 too_large` above.
- Upload throttle: at most once per 3 hours, 30-second debounce, immediate first upload after enabling.
- Error codes added: `token_taken` 409, `no_backup` 404, `too_large` 413.
- Restore is refused while block mode is active; restore never touches tag link, block state, bypass, unlocks, session start, block hits, pause log, code breakers, schedule watermark.
- New English strings in `values/strings.xml` only; controller writes locales.
- Device verification restores onto the debug build only; the real install is updated in place (`adb install -r`), never uninstalled or restored.

## Review Focus

1. **Restoring while Monolith is on** must write nothing and say to turn it off with the tag. Test: `RestoreBackupUseCaseTest` "refuses while active and writes nothing" (Task 7).
2. **A wrong recovery code or a tampered blob** must fail cleanly, never write partial data. Tests: `BackupCryptoTest` "tampered byte fails" and `RestoreBackupUseCaseTest` "unreadable backup writes nothing" (Tasks 4, 7).
3. **Leaving the last friends group with backup on** must keep the identity and the backup. Test: `backup.test.ts` "leaving the last group keeps a user with a backup" (Task 2).
4. **An identity from the previous build** (server-issued token) must migrate once without losing groups. Tests: `groups.test.ts` "token rotation keeps groups" (Task 1) and `BackupRepositoryTest` "legacy identity migrates to a master" (Task 6).
5. **Restore must not touch excluded keys** (tag link, block state, pause log). Test: `BackupPrefsTest` "restore leaves excluded keys untouched" (Task 5).

---

### Task 1: Server identity from the phone

**Files:**
- Create: `server/migrations/0003_backup.sql`
- Modify: `server/src/groups.ts` (create/join require bearer, empty-name rule, `createUser`, `rotateToken`, `getMe` adds `backupAt`), `server/src/validate.ts` (`parseToken`), `server/src/index.ts` (routes), `server/src/invariants.ts` (invariant 4)
- Modify: `server/test/helpers.ts`, `server/test/groups.test.ts` (adapt no-token creates), `server/test/*.test.ts` that use `newUser`/`join` (through the helpers only)

**Interfaces:**
- Produces: `POST /users {token, displayName?}` → `201 {}`; `POST /me/token {token}` → 204; `GET /me` adds `backupAt`; create/join require bearer and return `{group}` only; helpers `genToken()`, `register(displayName?)`, `newUser(name, share)` (register + create, returns `{token, group}`), `join(code, name, share, token?)` (registers when no token).

- [ ] **Step 1: Migration**

`server/migrations/0003_backup.sql`:
```sql
CREATE TABLE backups (
  user_id    TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  blob       BLOB NOT NULL,
  updated_at INTEGER NOT NULL
);
```

- [ ] **Step 2: Helpers**

Replace `newUser` and `join` in `server/test/helpers.ts` and add:
```ts
export function genToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Registers a phone-generated identity and returns its token. */
export async function register(displayName?: string) {
  const token = genToken();
  const res = await call("POST", "/users", displayName === undefined ? { token } : { token, displayName });
  if (res.status !== 201) throw new Error(`register ${res.status} ${JSON.stringify(res.body)}`);
  return token;
}

export async function newUser(displayName = "Ana", s: Share = share()) {
  const token = await register(displayName);
  const res = await call("POST", "/groups", { share: s }, token);
  if (res.status !== 201) throw new Error(`newUser ${res.status} ${JSON.stringify(res.body)}`);
  return { token, group: res.body.group as Group };
}

export async function join(inviteCode: string, displayName: string, s: Share = share(), token?: string) {
  const t = token ?? (await register(displayName));
  const res = await call("POST", "/join", { inviteCode, share: s }, t);
  if (res.status !== 201) throw new Error(`join ${res.status} ${JSON.stringify(res.body)}`);
  return { token: t, group: res.body.group as Group };
}
```

- [ ] **Step 3: Failing tests** (append to `groups.test.ts`; then adapt existing tests that POST `/groups` or `/join` without a token: they now either use `register()` first or assert 401)

```ts
describe("phone-generated identity", () => {
  it("registers a token and rejects a duplicate or malformed one", async () => {
    const token = genToken();
    expect((await call("POST", "/users", { token })).status).toBe(201);
    expect(await call("POST", "/users", { token })).toEqual({ status: 409, body: { error: "token_taken" } });
    for (const bad of ["short", "x".repeat(44), "!".repeat(43), 42]) {
      expect((await call("POST", "/users", { token: bad })).status).toBe(400);
    }
  });

  it("create and join need a bearer", async () => {
    expect((await call("POST", "/groups", { displayName: "Ana", share: share() })).status).toBe(401);
    expect((await call("POST", "/join", { inviteCode: "ABCDEFGH", displayName: "Ana", share: share() })).status).toBe(401);
  });

  it("a nameless identity must name itself when it first creates or joins", async () => {
    const token = await register();
    expect(await call("POST", "/groups", { share: share() }, token)).toEqual({ status: 400, body: { error: "invalid_body" } });
    const res = await call("POST", "/groups", { displayName: "Ana", share: share() }, token);
    expect(res.status).toBe(201);
    expect((await call("GET", "/me", undefined, token)).body.displayName).toBe("Ana");
  });

  it("a named identity's body name is ignored", async () => {
    const token = await register("Ana");
    await call("POST", "/groups", { displayName: "Zed", share: share() }, token);
    expect((await call("GET", "/me", undefined, token)).body.displayName).toBe("Ana");
  });

  it("token rotation keeps groups", async () => {
    const ana = await newUser("Ana");
    const next = genToken();
    expect((await call("POST", "/me/token", { token: next }, ana.token)).status).toBe(204);
    expect((await call("GET", "/me", undefined, ana.token)).status).toBe(401);
    const me = await call("GET", "/me", undefined, next);
    expect(me.body.groups.map((g: { id: string }) => g.id)).toEqual([ana.group.id]);
    const other = await register("Ben");
    expect(await call("POST", "/me/token", { token: other }, next)).toEqual({ status: 409, body: { error: "token_taken" } });
  });

  it("GET /me reports backupAt as null without a backup", async () => {
    const token = await register("Ana");
    expect((await call("GET", "/me", undefined, token)).body).toEqual({ displayName: "Ana", groups: [], backupAt: null });
  });
});
```
(Import `genToken` and `register` from `./helpers`.)

- [ ] **Step 4: Run to see failures**

Run: `cd server && npx vitest run`
Expected: new tests FAIL (routes missing), several adapted tests FAIL until handlers change.

- [ ] **Step 5: Implement**

`server/src/validate.ts` append:
```ts
export function parseToken(v: unknown): string {
  if (typeof v !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(v)) throw invalidBody();
  return v;
}
```

`server/src/groups.ts`:
- Remove `resolveUser`, `newToken` import and the no-token user creation. `createGroup` and `joinGroup` start with `const user = await authenticate(req, env);` (import from `./auth`), keep their existing checks (`assertRoomForAnotherGroup`, `already_member`, `group_full`, invite code), and build `const nameUpdate = await nameForFirstGroup(db, user, body);` added to the batch:
```ts
/** A backup-only identity has no name yet; its first group needs one. */
function nameForFirstGroup(db: D1Database, user: UserRow, body: Record<string, unknown>): D1PreparedStatement[] {
  if (user.display_name !== "") return [];
  const name = parseDisplayName(body.displayName);
  return [db.prepare("UPDATE users SET display_name = ?2 WHERE id = ?1").bind(user.id, name)];
}
```
  Responses become `json({ group: await groupInfo(db, user.id, groupId) }, 201)`.
- Add:
```ts
export async function createUser(req: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(req);
  const token = parseToken(body.token);
  const name = body.displayName === undefined ? "" : parseDisplayName(body.displayName);
  try {
    await insertUser(env.monolith_leaderboard, crypto.randomUUID(), name, await sha256Hex(token), now).run();
  } catch (e) {
    if (String(e).includes("UNIQUE")) throw new HttpError(409, "token_taken");
    throw e;
  }
  return json({}, 201);
}

export async function rotateToken(req: Request, env: Env, user: UserRow): Promise<Response> {
  const token = parseToken((await readJson(req)).token);
  try {
    await env.monolith_leaderboard.prepare("UPDATE users SET token_hash = ?2 WHERE id = ?1").bind(user.id, await sha256Hex(token)).run();
  } catch (e) {
    if (String(e).includes("UNIQUE")) throw new HttpError(409, "token_taken");
    throw e;
  }
  return empty();
}
```
- `meBody` adds `backupAt`:
```ts
async function meBody(db: D1Database, userId: string, displayName: string) {
  const backup = await db.prepare("SELECT updated_at FROM backups WHERE user_id = ?1").bind(userId).first<{ updated_at: number }>();
  return { displayName, groups: await groupInfos(db, userId), backupAt: backup?.updated_at ?? null };
}
```

`server/src/index.ts`: route `POST /users` → `createUser` (unauthenticated, like the old create), add `"POST /me/token": rotateToken` to `authedRoutes`. `POST /groups` and `POST /join` now go through `authenticate` (keep them as special cases before the group-path match, but call the handlers which authenticate themselves).

`server/src/invariants.ts`, the user deletion becomes:
```ts
db.prepare(
  `DELETE FROM users WHERE id = ?1
     AND NOT EXISTS (SELECT 1 FROM memberships WHERE user_id = ?1)
     AND NOT EXISTS (SELECT 1 FROM backups WHERE user_id = ?1)`,
).bind(u),
```
(the `DELETE FROM days ... NOT EXISTS memberships` statement stays: leaderboard days go with the last group either way).

- [ ] **Step 6: Run everything**

Run: `cd server && npx vitest run && npx tsc`
Expected: PASS; tsc 0.

- [ ] **Step 7: Commit**

```bash
git add -A server
git commit -m "feat(server): phone-generated identities and token rotation"
```

---

### Task 2: Server backup storage

**Files:**
- Create: `server/src/backup.ts`, `server/test/backup.test.ts`
- Modify: `server/src/index.ts`, `server/src/board.ts` (stale definition), `server/test/helpers.ts` (`callBytes`)

**Interfaces:**
- Produces: `PUT /backup` (octet-stream, 1..1,048,576 bytes) → 204 / 400 / 413; `GET /backup` → 200 bytes / 404 `no_backup`; `DELETE /backup` → 204 and invariant 4 for the caller.

- [ ] **Step 1: Helper for raw bodies** (append to `helpers.ts`)

```ts
export async function callBytes(method: string, path: string, token: string, body?: Uint8Array) {
  const res = await SELF.fetch(`https://leaderboard.test${path}`, {
    method,
    headers: { authorization: `Bearer ${token}`, ...(body ? { "content-type": "application/octet-stream" } : {}) },
    body,
  });
  const bytes = new Uint8Array(await res.arrayBuffer());
  return { status: res.status, bytes, json: () => JSON.parse(new TextDecoder().decode(bytes)) };
}
```

- [ ] **Step 2: Failing tests** (`server/test/backup.test.ts`)

```ts
import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { call, callBytes, newUser, register } from "./helpers";

const blob = (n: number, fill = 7) => new Uint8Array(n).fill(fill);

describe("backup", () => {
  it("stores, returns and replaces a blob", async () => {
    const token = await register();
    expect((await callBytes("PUT", "/backup", token, blob(10))).status).toBe(204);
    expect((await callBytes("PUT", "/backup", token, blob(5, 9))).status).toBe(204);
    const got = await callBytes("GET", "/backup", token);
    expect(got.status).toBe(200);
    expect(Array.from(got.bytes)).toEqual([9, 9, 9, 9, 9]);
    const me = await call("GET", "/me", undefined, token);
    expect(me.body.backupAt).toBeGreaterThan(Date.now() - 60000);
  });

  it("no backup is 404 no_backup", async () => {
    const token = await register();
    const got = await callBytes("GET", "/backup", token);
    expect(got.status).toBe(404);
    expect(got.json()).toEqual({ error: "no_backup" });
  });

  it("rejects empty and oversized blobs", async () => {
    const token = await register();
    expect((await callBytes("PUT", "/backup", token, new Uint8Array(0))).status).toBe(400);
    const big = await callBytes("PUT", "/backup", token, blob(1_048_577));
    expect(big.status).toBe(413);
    expect(big.json()).toEqual({ error: "too_large" });
    expect((await callBytes("PUT", "/backup", token, blob(1_048_576))).status).toBe(204);
  });

  it("delete is idempotent and removes a groupless user", async () => {
    const token = await register();
    await callBytes("PUT", "/backup", token, blob(3));
    expect((await callBytes("DELETE", "/backup", token)).status).toBe(204);
    expect((await call("GET", "/me", undefined, token)).status).toBe(401);
  });

  it("delete keeps a user who is still in a group", async () => {
    const ana = await newUser("Ana");
    await callBytes("PUT", "/backup", ana.token, blob(3));
    expect((await callBytes("DELETE", "/backup", ana.token)).status).toBe(204);
    expect((await callBytes("DELETE", "/backup", ana.token)).status).toBe(204);
    const me = await call("GET", "/me", undefined, ana.token);
    expect(me.status).toBe(200);
    expect(me.body.backupAt).toBeNull();
  });

  it("leaving the last group keeps a user with a backup", async () => {
    const ana = await newUser("Ana");
    await callBytes("PUT", "/backup", ana.token, blob(3));
    expect((await call("DELETE", `/groups/${ana.group.id}`, undefined, ana.token)).status).toBe(204);
    const me = await call("GET", "/me", undefined, ana.token);
    expect(me.body).toMatchObject({ displayName: "Ana", groups: [] });
    expect((await callBytes("GET", "/backup", ana.token)).status).toBe(200);
  });

  it("a backup upload removes stale backup-only users", async () => {
    const stale = await register();
    await callBytes("PUT", "/backup", stale, blob(3));
    const old = Date.now() - 121 * 24 * 60 * 60 * 1000;
    await env.monolith_leaderboard.batch([
      env.monolith_leaderboard.prepare("UPDATE users SET created_at = ?1").bind(old),
      env.monolith_leaderboard.prepare("UPDATE backups SET updated_at = ?1").bind(old),
    ]);
    const fresh = await register();
    await callBytes("PUT", "/backup", fresh, blob(3));
    expect((await call("GET", "/me", undefined, stale)).status).toBe(401);
    expect((await call("GET", "/me", undefined, fresh)).status).toBe(200);
  });

  it("a recent backup keeps a group member off the board cleanup", async () => {
    const ana = await newUser("Ana");
    const { join } = await import("./helpers");
    const ben = await join(ana.group.inviteCode, "Ben");
    await callBytes("PUT", "/backup", ben.token, blob(3));
    const old = Date.now() - 121 * 24 * 60 * 60 * 1000;
    await env.monolith_leaderboard.prepare("UPDATE users SET created_at = ?1").bind(old).run();
    await env.monolith_leaderboard.prepare("UPDATE users SET last_sync_at = ?1 WHERE display_name = 'Ana'").bind(Date.now()).run();
    const today = new Date().toISOString().slice(0, 10);
    const board = await call("GET", `/groups/${ana.group.id}/board?window=day&date=${today}`, undefined, ana.token);
    expect(board.body.rows.map((r: { name: string }) => r.name).sort()).toEqual(["Ana", "Ben"]);
  });
});
```

- [ ] **Step 3: Run to see failures**

Run: `cd server && npx vitest run test/backup.test.ts`
Expected: FAIL (routes missing).

- [ ] **Step 4: Implement**

`server/src/backup.ts`:
```ts
import { empty, HttpError, json } from "./http";
import { invariantStatements } from "./invariants";
import { DAY_MS, INACTIVE_DAYS, type Env, type UserRow } from "./types";

const MAX_BACKUP_BYTES = 1_048_576;

/** Backup-only users are never on a board, so their inactivity is swept here instead. */
async function removeStaleBackupOnlyUsers(db: D1Database, now: number) {
  const staleBefore = now - INACTIVE_DAYS * DAY_MS;
  const { results } = await db
    .prepare(
      `SELECT u.id FROM users u LEFT JOIN backups b ON b.user_id = u.id
       WHERE NOT EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = u.id)
         AND MAX(COALESCE(u.last_sync_at, u.created_at), COALESCE(b.updated_at, 0)) < ?1
       LIMIT 50`,
    )
    .bind(staleBefore)
    .all<{ id: string }>();
  if (results.length === 0) return;
  await db.batch(results.map((r) => db.prepare("DELETE FROM users WHERE id = ?1").bind(r.id)));
}

export async function putBackup(req: Request, env: Env, user: UserRow, now: number): Promise<Response> {
  const bytes = new Uint8Array(await req.arrayBuffer());
  if (bytes.byteLength === 0) throw new HttpError(400, "invalid_body");
  if (bytes.byteLength > MAX_BACKUP_BYTES) throw new HttpError(413, "too_large");
  const db = env.monolith_leaderboard;
  await db
    .prepare(
      `INSERT INTO backups (user_id, blob, updated_at) VALUES (?1, ?2, ?3)
       ON CONFLICT (user_id) DO UPDATE SET blob = excluded.blob, updated_at = excluded.updated_at`,
    )
    .bind(user.id, bytes, now)
    .run();
  await removeStaleBackupOnlyUsers(db, now);
  return empty();
}

export async function getBackup(_req: Request, env: Env, user: UserRow): Promise<Response> {
  const row = await env.monolith_leaderboard
    .prepare("SELECT blob FROM backups WHERE user_id = ?1")
    .bind(user.id)
    .first<{ blob: ArrayBuffer | number[] }>();
  if (!row) throw new HttpError(404, "no_backup");
  const bytes = row.blob instanceof ArrayBuffer ? new Uint8Array(row.blob) : Uint8Array.from(row.blob);
  return new Response(bytes, { headers: { "content-type": "application/octet-stream" } });
}

export async function deleteBackup(_req: Request, env: Env, user: UserRow): Promise<Response> {
  const db = env.monolith_leaderboard;
  await db.batch([
    db.prepare("DELETE FROM backups WHERE user_id = ?1").bind(user.id),
    ...invariantStatements(db, [user.id], []),
  ]);
  return empty();
}
```
(`json` import unused: remove it if tsc complains.)

`server/src/index.ts`: add `"PUT /backup": putBackup`, `"GET /backup": getBackup`, `"DELETE /backup": deleteBackup` to `authedRoutes`.

`server/src/board.ts` `removeStaleMembers`: the stale predicate becomes
```sql
SELECT u.id FROM memberships m JOIN users u ON u.id = m.user_id
LEFT JOIN backups b ON b.user_id = u.id
WHERE m.group_id = ?1 AND u.id != ?2
  AND MAX(COALESCE(u.last_sync_at, u.created_at), COALESCE(b.updated_at, 0)) < ?3
```
and the deletion must also remove those users' backups: add `db.prepare(\`DELETE FROM backups WHERE user_id IN (${placeholders})\`).bind(...userIds)` before the invariants in the batch.

- [ ] **Step 5: Run everything**

Run: `cd server && npx vitest run && npx tsc`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A server
git commit -m "feat(server): encrypted backup storage"
```

---

### Task 3: Deploy (controller)

- [ ] `cd server && npx wrangler d1 migrations apply monolith-leaderboard --remote && npx wrangler deploy`.
- [ ] Smoke: register, PUT 10 bytes, GET returns them, `/me.backupAt` set, DELETE, GET `/me` 401.
- [ ] Note: from this deploy on, the app build on the user's phone can't create or join groups (it still sends no bearer) until the Task 9 install. Existing groups keep working.

---

### Task 4: Android crypto

**Files:**
- Create: `app/src/main/kotlin/com/monolith/app/data/backup/BackupCrypto.kt`
- Test: `app/src/test/kotlin/com/monolith/app/data/backup/BackupCryptoTest.kt`

**Interfaces:**
```kotlin
object BackupCrypto {
    fun newMaster(): ByteArray                        // 32 SecureRandom bytes
    fun encodeCode(bytes: ByteArray): String          // base64url, no padding (43 chars for 32 bytes)
    fun decodeCode(code: String): ByteArray?          // trims; null unless exactly 32 bytes of valid base64url
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray
    fun deriveToken(master: ByteArray): String        // encodeCode(hkdf(master, "monolith", "auth v1", 32))
    fun deriveKey(master: ByteArray): ByteArray       // hkdf(master, "monolith", "backup v1", 32)
    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray   // 0x01 ‖ nonce(12) ‖ AES-GCM(gzip(plain)), 128-bit tag
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray    // throws BackupCryptoException on anything wrong
}
class BackupCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
```
Use `java.util.Base64.getUrlEncoder().withoutPadding()` (API 26+, fine for minSdk 26), `javax.crypto.Mac("HmacSHA256")`, `Cipher.getInstance("AES/GCM/NoPadding")` with `GCMParameterSpec(128, nonce)`, `GZIPOutputStream`/`GZIPInputStream`.

- [ ] **Step 1: Failing tests**

```kotlin
package com.monolith.app.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val okm = BackupCrypto.hkdf(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun `token and key are deterministic and different`() {
        val master = ByteArray(32) { it.toByte() }
        assertEquals(BackupCrypto.deriveToken(master), BackupCrypto.deriveToken(master))
        assertEquals(43, BackupCrypto.deriveToken(master).length)
        assertNotEquals(BackupCrypto.encodeCode(BackupCrypto.deriveKey(master)), BackupCrypto.deriveToken(master))
    }

    @Test
    fun `codes round trip and reject junk`() {
        val master = BackupCrypto.newMaster()
        val code = BackupCrypto.encodeCode(master)
        assertEquals(43, code.length)
        assertArrayEquals(master, BackupCrypto.decodeCode("  $code\n"))
        assertNull(BackupCrypto.decodeCode("short"))
        assertNull(BackupCrypto.decodeCode("!".repeat(43)))
    }

    @Test
    fun `encrypt then decrypt returns the plaintext`() {
        val key = BackupCrypto.deriveKey(BackupCrypto.newMaster())
        val plain = "{\"version\":1}".repeat(500).toByteArray()
        val blob = BackupCrypto.encrypt(key, plain)
        assertEquals(1, blob[0].toInt())
        assertArrayEquals(plain, BackupCrypto.decrypt(key, blob))
    }

    @Test
    fun `wrong key, tampered byte and unknown version fail`() {
        val key = BackupCrypto.deriveKey(BackupCrypto.newMaster())
        val blob = BackupCrypto.encrypt(key, "hello".toByteArray())
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(BackupCrypto.deriveKey(BackupCrypto.newMaster()), blob) }
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(key, tampered) }
        val future = blob.copyOf().also { it[0] = 2 }
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(key, future) }
        assertThrows(BackupCryptoException::class.java) { BackupCrypto.decrypt(key, ByteArray(5)) }
    }
}
```

- [ ] **Step 2: Run to see it fail** — `./gradlew testDebugUnitTest --tests 'com.monolith.app.data.backup.BackupCryptoTest'` (unresolved `BackupCrypto`).

- [ ] **Step 3: Implement** `BackupCrypto` per Interfaces. HKDF: `prk = HMAC(salt, ikm)`; `T(i) = HMAC(prk, T(i-1) ‖ info ‖ i)`; concatenate to `length`. `decrypt`: size < 1 + 12 + 16 → throw; version byte != 1 → throw; GCM failure (`AEADBadTagException`/`GeneralSecurityException`) → throw; gzip failure (`IOException`) → throw; all wrapped in `BackupCryptoException`.

- [ ] **Step 4: Pass** — same command, PASS.

- [ ] **Step 5: Commit** — `feat: backup encryption with keys derived from the recovery code`.

---

### Task 5: Snapshot and preferences export/restore

**Files:**
- Create: `app/src/main/kotlin/com/monolith/app/data/backup/BackupSnapshot.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/data/datastore/MonolithPreferences.kt` (add `exportSnapshot()`, `restoreSnapshot(snapshot)`, and internal pure `readSnapshot(prefs: Preferences, now: Long): BackupSnapshot`, `applySnapshot(prefs: MutablePreferences, snapshot: BackupSnapshot)`)
- Test: `app/src/test/kotlin/com/monolith/app/data/backup/BackupPrefsTest.kt`

**Interfaces:**
```kotlin
@Serializable data class BackupSnapshot(
    val version: Int = 1,
    val createdAt: Long,
    val sessions: List<SessionEntry>,
    val blockedPackages: List<String>,
    val importantPeople: List<PersonEntry>,
    val schedules: List<ScheduleEntry>,
    val strictness: String?,
) {
    @Serializable data class SessionEntry(val start: Long, val end: Long)
    @Serializable data class PersonEntry(val packageName: String, val name: String? = null, val handle: String? = null)
    @Serializable data class ScheduleEntry(val id: String, val enabled: Boolean, val days: List<String>, val startMinuteOfDay: Int)
}
object BackupSnapshotCodec {
    fun encode(snapshot: BackupSnapshot): ByteArray            // UTF-8 JSON (LeaderboardJson-style: ignoreUnknownKeys)
    fun decode(bytes: ByteArray): BackupSnapshot                // throws BackupCryptoException("unsupported version") when version != 1
}
// in MonolithPreferences.kt:
internal fun readSnapshot(prefs: Preferences, now: Long): BackupSnapshot
internal fun applySnapshot(prefs: MutablePreferences, snapshot: BackupSnapshot)
class MonolithPreferences { suspend fun exportSnapshot(): BackupSnapshot; suspend fun restoreSnapshot(snapshot: BackupSnapshot) }
```
Mapping: sessions ↔ `Keys.BLOCK_SESSIONS` (JSON of the private `BlockSessionDto`), blocked packages ↔ `Keys.BLOCKED_PACKAGES` (string set), important people ↔ `Keys.IMPORTANT_PEOPLE`, schedules ↔ `Keys.BLOCK_SCHEDULES` (private `BlockScheduleDto`), strictness ↔ `Keys.STRICTNESS_LEVEL` (null removes the key). `applySnapshot` writes exactly these five keys and nothing else. `restoreSnapshot` = one `context.dataStore.edit { applySnapshot(it, snapshot) }`.

- [ ] **Step 1: Failing tests**

```kotlin
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
```
(Verify the real key names `tag_uid`, `block_mode_active`, `session_started_at`, `pause_log` in `MonolithPreferences.Keys` and adjust the test if they differ.)

- [ ] **Step 2: Run to see it fail.** **Step 3: Implement** per Interfaces. **Step 4: Pass** (`./gradlew testDebugUnitTest`). **Step 5: Commit** — `feat: export and restore history and setup as a snapshot`.

---

### Task 6: Identity with a master, backup API and repository

**Files:**
- Modify: `data/leaderboard/IdentityStore.kt` (add `master: String?` to what it stores, `backupEnabled`, `lastBackupAt`), `domain/model/Leaderboard.kt` (`Identity` gains `master: String?`, `backupAt: Long?`; new errors `TOKEN_TAKEN`, `NO_BACKUP`, `TOO_LARGE`), `data/leaderboard/LeaderboardDtos.kt` (`RegisterRequest(token, displayName?)`, `RotateTokenRequest(token)`, `MeResponse.backupAt: Long? = null`, error codes), `data/leaderboard/LeaderboardApi.kt` (`register`, `rotateToken`, `putBackup(token, bytes)`, `getBackup(token): LeaderboardResult<ByteArray>`, `deleteBackup(token)`; a bytes variant of `call`), `data/repository/LeaderboardRepositoryImpl.kt` (identity creation via master)
- Create: `domain/repository/BackupRepository.kt`, `data/repository/BackupRepositoryImpl.kt`
- Modify: `di/RepositoryModule.kt`
- Test: `app/src/test/kotlin/com/monolith/app/data/repository/BackupRepositoryTest.kt`, update `LeaderboardRepositoryImplTest.kt` fakes

**Interfaces:**
```kotlin
interface BackupRepository {
    fun observeBackupEnabled(): Flow<Boolean>
    fun observeLastBackupAt(): Flow<Long?>
    fun observeRecoveryCode(): Flow<String?>                  // the master, null until one exists
    suspend fun ensureIdentity(displayName: String?): LeaderboardResult<Unit>   // creates master + POST /users if none; migrates a legacy identity
    suspend fun setBackupEnabled(enabled: Boolean): LeaderboardResult<Unit>     // on: ensureIdentity + immediate upload; off: DELETE /backup
    suspend fun upload(snapshot: BackupSnapshot, now: Long): LeaderboardResult<Unit>
    suspend fun fetch(recoveryCode: String): LeaderboardResult<FetchedBackup>   // derive token/key, GET /me then GET /backup
}
data class FetchedBackup(val master: String, val me: MeInfo, val snapshot: BackupSnapshot?)  // snapshot null when no_backup
data class MeInfo(val displayName: String, val groups: List<GroupInfo>, val backupAt: Long?)
```
Behavior:
- `LeaderboardRepositoryImpl.createGroup/joinGroup` call `backupRepository.ensureIdentity(displayName)` (or an equivalent shared `IdentityManager` both use; pick one and keep it in `data/repository/`) before creating or joining, then always send the bearer; send `displayName` in the body only when the stored display name is empty. Remove the old path that saved a server-issued token.
- `ensureIdentity`: no stored identity → `newMaster`, `deriveToken`, `POST /users {token, displayName?}`, save `Identity(token, displayName ?: "", emptyList(), master)`. Stored identity with `master == null` (legacy) → `newMaster`, `POST /me/token` with the derived token using the old token as bearer, then save the new token and master; failures leave the legacy identity untouched and return the error.
- `fetch(code)`: `decodeCode` null → `Err(UNAUTHORIZED)`; else `GET /me` with the derived token (401 → `Err(UNAUTHORIZED)`), then `GET /backup`: 404 → snapshot null; bytes → `BackupSnapshotCodec.decode(BackupCrypto.decrypt(deriveKey(master), bytes))`, a `BackupCryptoException` → `Err(INVALID)`. `fetch` writes nothing.
- `upload`: requires identity with master and backup enabled; `PUT /backup` with `encrypt(deriveKey(master), encode(snapshot))`; on Ok store `lastBackupAt = now`.
- `setBackupEnabled(false)`: `DELETE /backup`; on Ok store disabled and clear `lastBackupAt`. If the identity then has no groups, clear the identity (the server deleted it).
- The friends "restore with a recovery code" path (`LeaderboardRepository.restore`) now takes a master: derive the token, `GET /me`, save identity with the master. (The full data restore lives in Task 7's use case, which calls `fetch` and then this.)

- [ ] **Step 1: Failing tests** in `BackupRepositoryTest` (fake API + in-memory IdentityStore, `runBlocking`):
  1. `ensureIdentity without identity registers a phone-generated token` (captured register token == `deriveToken(stored master)`).
  2. `legacy identity migrates to a master` (stored identity has token "old", no master; rotate called with bearer "old" and a derived token; stored token becomes the derived one; groups unchanged).
  3. `failed migration keeps the legacy identity`.
  4. `upload encrypts with the key from the master` (captured bytes decrypt with `deriveKey(master)` to the snapshot).
  5. `fetch with a bad code is UNAUTHORIZED and writes nothing`.
  6. `fetch without a backup returns me and a null snapshot`.
  7. `fetch with an undecryptable blob is INVALID`.
  8. `disabling deletes the server copy and clears lastBackupAt`.
  And in `LeaderboardRepositoryImplTest`: `create sends the bearer and the name only for a nameless identity`.
- [ ] **Step 2: Run to see failures.** **Step 3: Implement.** **Step 4:** `./gradlew testDebugUnitTest assembleDebug` PASS. **Step 5: Commit** — `feat: phone-generated recovery code and backup repository`.

---

### Task 7: Backup scheduler and restore use case

**Files:**
- Create: `service/BackupScheduler.kt`, `domain/usecase/BackupThrottle.kt`, `domain/usecase/RestoreBackupUseCase.kt`
- Modify: `MonolithApplication.kt` (start scheduler), `ui/MainActivity.kt` (`onResume` → `backupScheduler.onAppOpen()`)
- Test: `app/src/test/kotlin/com/monolith/app/domain/usecase/BackupThrottleTest.kt`, `RestoreBackupUseCaseTest.kt`

**Interfaces:**
```kotlin
object BackupThrottle {
    const val MIN_INTERVAL_MILLIS = 3 * 60 * 60 * 1000L
    fun due(lastBackupAt: Long?, now: Long): Boolean = lastBackupAt == null || now - lastBackupAt >= MIN_INTERVAL_MILLIS
}

sealed interface RestoreOutcome {
    data class Ready(val backupAt: Long?, val hasBackup: Boolean) : RestoreOutcome   // fetched, waiting for confirm
    data object Refused : RestoreOutcome            // Monolith is on
    data class Failed(val error: LeaderboardError) : RestoreOutcome
    data object Done : RestoreOutcome
}
class RestoreBackupUseCase @Inject constructor(blockRepository: BlockRepository, backupRepository: BackupRepository,
    leaderboardRepository: LeaderboardRepository, snapshotWriter: SnapshotWriter, afterRestore: AfterRestore) {
    suspend fun prepare(code: String): RestoreOutcome   // refuse if active; fetch; keep result in memory
    suspend fun confirm(): RestoreOutcome               // re-check active; write snapshot; save identity; enable backup; afterRestore
}
interface SnapshotWriter { suspend fun write(snapshot: BackupSnapshot) }   // MonolithPreferences.restoreSnapshot in prod
interface AfterRestore { suspend fun run() }                              // widget refresh + ScheduleTrigger.reconcile in prod
```
`BackupScheduler`: like `LeaderboardSyncer` (read it): gated on `observeBackupEnabled()`; triggers from `observeBlockSessions`, blocked packages, important people, schedules, strictness (each `distinctUntilChanged().map {}`), `debounce(30_000)`, plus `onAppOpen()` and an immediate request when backup turns on; in the collector, upload only if `BackupThrottle.due(lastBackupAt, now)` or the trigger is the enable request; `exportSnapshot()` then `upload`; catch `Exception` (rethrow `CancellationException`) with `Log.w`.

- [ ] **Step 1: Failing tests**
  - `BackupThrottleTest`: null → due; 2h59m → not due; exactly 3h → due.
  - `RestoreBackupUseCaseTest` (fakes: `FakeBlockRepository`, fake BackupRepository, fake SnapshotWriter recording writes, fake AfterRestore counting runs):
    1. `refuses while active and writes nothing` (prepare returns Refused; no fetch).
    2. `bad code fails and writes nothing`.
    3. `unreadable backup writes nothing` (fetch Err INVALID → Failed(INVALID)).
    4. `confirm writes the snapshot, saves the identity and runs afterRestore`.
    5. `confirm re-checks active` (activate between prepare and confirm → Refused, no write).
    6. `no backup restores only the identity` (Ready(hasBackup = false); confirm writes no snapshot, saves identity).
- [ ] **Step 2: Run to see failures.** **Step 3: Implement**, with Hilt bindings for `SnapshotWriter` (MonolithPreferences adapter) and `AfterRestore` (calls `TimeSavedWidgetRefresher.refresh()` and `ScheduleTrigger.reconcile()`). **Step 4:** `./gradlew testDebugUnitTest assembleDebug` PASS. **Step 5: Commit** — `feat: automatic backups and a guarded restore`.

---

### Task 8: Settings backup section and restore UI

**Files:**
- Modify: `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `ui/friends/GroupSheet.kt` (remove recovery card), `ui/friends/FriendsScreen.kt` + `FriendsViewModel.kt` (first-use "Restore with a recovery code" uses `RestoreBackupUseCase`)
- Create (if it keeps files focused): `ui/settings/BackupSection.kt`, `ui/settings/RestoreDialog.kt`
- Modify: `res/values/strings.xml`

**New strings:**
```xml
<string name="backup_section">Backup</string>
<string name="backup_auto">Back up automatically</string>
<string name="backup_last">Last backed up %1$s</string>
<string name="backup_never">Not backed up yet</string>
<string name="backup_show_code">Show recovery code</string>
<string name="backup_restore">Restore from a backup</string>
<string name="backup_code_warning">Anyone with this code can restore your data. Keep it private.</string>
<string name="backup_restore_title">Restore from a backup</string>
<string name="backup_restore_confirm_body">Replace this phone\'s history and setup with the backup from %1$s?</string>
<string name="backup_restore_confirm">Replace</string>
<string name="backup_restore_refused">Turn Monolith off with your tag first.</string>
<string name="backup_restore_unreadable">This backup can\'t be read.</string>
<string name="backup_restore_none">No backup found. Your friends groups are back.</string>
<string name="backup_restore_done">Restored.</string>
```
(Reuse `friends_error_recovery` for a wrong code, `friends_recovery_label` for the field, `friends_copy`, `friends_restore_confirm`.)

**Behavior:**
- Settings gains the "Backup" section as specified in the spec (SettingsGroup: `SettingsToggleRow` auto backup; caption with relative time in mono or "Not backed up yet"; `SettingsDivider(startInset = 20.dp)`; "Show recovery code" row revealing the code in mono inside `SelectionContainer` with a copy `IconButton` using the existing sensitive-clipboard helper (move it to a shared place, e.g. `ui/components/SensitiveClipboard.kt`, and use it from both); "Restore from a backup" row opening `RestoreDialog`; muted warning caption below the card). "Show recovery code" is hidden until a master exists.
- `RestoreDialog`: code field (`KeyboardType.Password`, no autocorrect), Restore button → `prepare`; Refused / Failed messages inline; Ready with a backup → confirmation with the date → `confirm` → "Restored." and close; Ready without a backup → confirm restores identity → `backup_restore_none`.
- Toggling auto backup off shows no confirmation (the code still restores friends groups while any exist); toggling on with no identity creates one (display name empty).
- The Friends settings sheet's "You, in every group" loses the recovery card (display name only). The first-use Friends "Restore with a recovery code" flow uses the same `RestoreBackupUseCase` (same messages).

- [ ] **Step 1:** Add a ViewModel-free pure helper test if any formatting logic is introduced (e.g. `backupCaption(lastBackupAt, now)` returning null for never): write and run a failing test first.
- [ ] **Step 2:** Implement. **Step 3:** `./gradlew testDebugUnitTest assembleDebug` PASS. **Step 4: Commit** — `feat: backup settings and restore flow`.

---

### Task 9: Translations, release, device check (controller)

- [ ] Translate the new `backup_*` strings into the six locales with the apply script; commit.
- [ ] `./gradlew assembleRelease`; verify signer; `adb install -r` the release; confirm `firstInstallTime` unchanged and accessibility enabled.
- [ ] On the real install: open the app online (legacy identity migrates), turn on backup, wait for "Last backed up", show and copy the recovery code (user reads it out privately or the controller reads it via the clipboard only with the user's consent).
- [ ] `./gradlew assembleDebug` and `adb install -r` the debug build (`com.monolith.app.debug`, separate data). In the debug app: Settings → Restore from a backup with the code; confirm; compare the Time gained year view and blocked apps with the real install.
- [ ] Afterwards, remove the debug app's copy with `adb uninstall com.monolith.app.debug` (debug package only, never `com.monolith.app`). Do NOT turn backup off inside the debug app: it shares the identity, and turning it off deletes the real backup on the server.
- [ ] Friends: in the real install, create a group with a scripted friend to confirm create/join work with the new identity flow.
