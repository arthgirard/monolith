# Friends Multi-Group Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one user belong to up to 10 groups, each with its own board and its own sharing choices, under a single identity and recovery code.

**Architecture:** The server splits the old `members` table into `users` (identity, streak, sync time), `memberships` (per-group share flags) and `groups` (invite code, optional name), with `days` keyed by user. Four invariants run after every membership change. The phone keeps one token, caches its group list, uploads once per sync filtered by the union of its groups' share flags, and shows a chip row to switch boards.

**Tech Stack:** TypeScript Cloudflare Worker, D1, vitest + @cloudflare/vitest-pool-workers; Kotlin, Jetpack Compose (Material3 1.2.1), Hilt, DataStore, kotlinx.serialization, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-28-friends-multi-group-design.md` (amends `docs/superpowers/specs/2026-09-28-friends-leaderboard-design.md`)

## Global Constraints

- Commit messages: conventional prefix, no Co-Authored-By or any AI attribution trailer, no em-dashes.
- No new npm runtime dependencies, no new Gradle dependencies.
- Limits: display name 1 to 24 characters after trim; group name 1 to 32 after trim; at most 10 groups per user; at most 20 members per group; 120-day inactivity; 35-day window (36 at the edge); 40 days per sync; `savedMs` 0..86,400,000; counts 0..10,000.
- Error codes: `unauthorized` 401, `invalid_body` 400, `invite_not_found` 404, `not_member` 404, `group_full` 409, `too_many_groups` 409, `already_member` 409, `name_needs_three` 409, `hidden_signal` 422.
- Visual direction: Inter for text, JetBrains Mono (`TextStyle.mono()`) for data only, cards via `SettingsGroup` / `SettingsToggleRow` / `SettingsDivider(startInset = 20.dp)`, sentence case, no all-caps.
- New English strings go in `app/src/main/res/values/strings.xml` only; the controller writes the other six locales.
- Device-bound work ends with `./gradlew assembleRelease`; installs on the user's phone are in place only (`adb install -r`), never uninstall.
- The migration resets server data (approved: current data is test data).

## Review Focus

1. **Same two people in two groups with different flags** must see each group's own reciprocity, not a mix. Test: `board.test.ts` "per-group reciprocity" (Task 3).
2. **Hiding a signal in your last sharing group** must delete it on the server immediately, and a later upload of it must be refused. Tests: `groups.test.ts` "hiding a signal everywhere nulls it" (Task 2) and `sync.test.ts` "union decides hidden_signal" (Task 3).
3. **Leaving your last group** must not show "You're no longer in a group" (that notice is for being removed). Test: `LeaderboardRepositoryImplTest` "leaving the last group clears without a notice" (Task 6).
4. **A named group dropping to 2 members** must lose its name, and a 2-member group must refuse a name. Test: `groups.test.ts` "names need three and vanish below three" (Task 2).
5. **An inactive user in several groups** must disappear from all of them, and a group left empty must be deleted. Test: `board.test.ts` "cleanup removes stale users from every group" (Task 3).

---

## File map

**Server**

| File | Change |
|---|---|
| `server/migrations/0002_multi_group.sql` | create: drop old tables, create new schema |
| `server/src/types.ts` | `UserRow`, `ShareColumns`, `MAX_GROUPS`; drop `MemberRow` |
| `server/src/auth.ts` | `authenticate` returns `UserRow`; add `optionalUser` |
| `server/src/validate.ts` | add `parseGroupName`; `parseSync` unchanged |
| `server/src/invariants.ts` | create: `invariantStatements(db, userIds, groupIds)` |
| `server/src/groups.ts` | rewrite: `groupInfos`, `createGroup`, `joinGroup`, `getMe`, `updateMe`, `updateGroup`, `leaveGroup` |
| `server/src/sync.ts` | union share, `user_id` |
| `server/src/board.ts` | per-group query, cross-group cleanup; `rankRows` unchanged |
| `server/src/index.ts` | router with `/groups/:id` and `/groups/:id/board` |
| `server/test/helpers.ts`, `groups.test.ts`, `me.test.ts`, `sync.test.ts`, `board.test.ts` | rewrite for the new API |

**Android** (`app/src/main/kotlin/com/monolith/app/`)

| File | Change |
|---|---|
| `domain/model/Leaderboard.kt` | `GroupInfo`, `Identity`, `GroupLabel`, new errors; drop `GroupMembership` |
| `domain/usecase/Groups.kt` | create: pure `shareUnion`, `groupLabel` |
| `data/leaderboard/LeaderboardDtos.kt` | group DTOs, new requests/responses, new error codes |
| `data/leaderboard/LeaderboardApi.kt` | new endpoints |
| `data/leaderboard/IdentityStore.kt` | create (replaces `MembershipStore.kt`) |
| `domain/repository/LeaderboardRepository.kt`, `data/repository/LeaderboardRepositoryImpl.kt` | multi-group API |
| `service/LeaderboardSyncer.kt` | observe identity instead of membership |
| `ui/friends/FriendsViewModel.kt`, `FriendsScreen.kt`, `GroupSheet.kt`, `AddGroupSheet.kt` (create) | chips, per-group board, sheets |
| `di/RepositoryModule.kt` | bind `IdentityStore` |

---

### Task 1: Server identity, schema, create, join, me

**Files:**
- Create: `server/migrations/0002_multi_group.sql`
- Modify: `server/src/types.ts`, `server/src/auth.ts`, `server/src/validate.ts`, `server/src/index.ts`
- Rewrite: `server/src/groups.ts` (this task: `groupInfos`, `createGroup`, `joinGroup`, `getMe`, `updateMe`)
- Temporarily: `server/src/sync.ts` and `server/src/board.ts` keep compiling only if you stub them; instead, remove `POST /sync` and `GET /board` from the router in this task and delete `server/test/sync.test.ts` and `server/test/board.test.ts` (Task 3 rewrites both). Delete `server/test/me.test.ts` (folded into `groups.test.ts`).
- Rewrite: `server/test/helpers.ts`, `server/test/groups.test.ts`

**Interfaces:**
- Produces: `UserRow {id, display_name}`, `ShareColumns {share_saved, share_streak, share_pauses}`, `shareOf(c: ShareColumns): Share`, `MAX_GROUPS = 10`; `authenticate(req, env): Promise<UserRow>`, `optionalUser(req, env): Promise<UserRow | null>`; `parseGroupName(v): string | null`; `GroupInfo {id, inviteCode, name, memberCount, otherMembers, share}`, `groupInfos(db, userId): Promise<GroupInfo[]>`, `groupInfo(db, userId, groupId): Promise<GroupInfo>`; router supports `POST /groups/:id`, `DELETE /groups/:id`, `GET /groups/:id/board` via a `groupRoutes` map that Tasks 2 and 3 fill.
- Wire: `POST /groups` and `POST /join` return `201 {token?, group}`; `GET /me` and `POST /me` return `{displayName, groups}`.

- [ ] **Step 1: Migration**

`server/migrations/0002_multi_group.sql`:
```sql
-- Resets leaderboard data: everything before this was pre-release test data.
DROP TABLE IF EXISTS days;
DROP TABLE IF EXISTS members;
DROP TABLE IF EXISTS groups;

CREATE TABLE users (
  id                TEXT PRIMARY KEY,
  token_hash        TEXT NOT NULL UNIQUE,
  display_name      TEXT NOT NULL,
  streak_started_at INTEGER,
  last_sync_at      INTEGER,
  created_at        INTEGER NOT NULL
);

CREATE TABLE groups (
  id          TEXT PRIMARY KEY,
  invite_code TEXT NOT NULL UNIQUE,
  name        TEXT,
  created_at  INTEGER NOT NULL
);

CREATE TABLE memberships (
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  group_id     TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  share_saved  INTEGER NOT NULL,
  share_streak INTEGER NOT NULL,
  share_pauses INTEGER NOT NULL,
  joined_at    INTEGER NOT NULL,
  PRIMARY KEY (user_id, group_id)
);
CREATE INDEX memberships_group ON memberships(group_id);

CREATE TABLE days (
  user_id      TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  date         TEXT NOT NULL,
  saved_ms     INTEGER,
  bypass_count INTEGER,
  unlock_count INTEGER,
  PRIMARY KEY (user_id, date)
);
```

- [ ] **Step 2: Types, auth, name validation**

`server/src/types.ts` (replace `MemberRow` and `shareOf`; keep `Env`, `Share` and the other constants):
```ts
export interface UserRow {
  id: string;
  display_name: string;
}

export interface ShareColumns {
  share_saved: number;
  share_streak: number;
  share_pauses: number;
}

export function shareOf(c: ShareColumns): Share {
  return { saved: c.share_saved === 1, streak: c.share_streak === 1, pauses: c.share_pauses === 1 };
}

export const MAX_GROUPS = 10;
```

`server/src/auth.ts`:
```ts
import { sha256Hex } from "./codes";
import { HttpError } from "./http";
import type { Env, UserRow } from "./types";

async function userFor(header: string | null, env: Env): Promise<UserRow | null | undefined> {
  if (header === null) return undefined;
  const match = header.match(/^Bearer (\S+)$/);
  if (!match) return null;
  return env.monolith_leaderboard
    .prepare("SELECT id, display_name FROM users WHERE token_hash = ?1")
    .bind(await sha256Hex(match[1]))
    .first<UserRow>();
}

export async function authenticate(req: Request, env: Env): Promise<UserRow> {
  const user = await userFor(req.headers.get("authorization"), env);
  if (!user) throw new HttpError(401, "unauthorized");
  return user;
}

/** No header means "new user"; a header that doesn't resolve is still a 401, never a silent new identity. */
export async function optionalUser(req: Request, env: Env): Promise<UserRow | null> {
  const user = await userFor(req.headers.get("authorization"), env);
  if (user === undefined) return null;
  if (user === null) throw new HttpError(401, "unauthorized");
  return user;
}
```

Append to `server/src/validate.ts`:
```ts
/** A group name, or null to clear it ("" and null both clear). */
export function parseGroupName(v: unknown): string | null {
  if (v === null) return null;
  if (typeof v !== "string") throw invalidBody();
  const name = v.trim();
  if (name === "") return null;
  if ([...name].length > 32) throw invalidBody();
  return name;
}
```

- [ ] **Step 3: Test helpers**

`server/test/helpers.ts`:
```ts
import { SELF } from "cloudflare:test";
import { addDays, utcToday } from "../src/dates";
import type { Share } from "../src/types";

export const share = (saved = true, streak = true, pauses = true): Share => ({ saved, streak, pauses });

export async function call(method: string, path: string, body?: unknown, token?: string) {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["content-type"] = "application/json";
  if (token) headers.authorization = `Bearer ${token}`;
  const res = await SELF.fetch(`https://leaderboard.test${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return { status: res.status, body: (text ? JSON.parse(text) : null) as any };
}

export interface Group {
  id: string;
  inviteCode: string;
  name: string | null;
  memberCount: number;
  otherMembers: string[];
  share: Share;
}

/** New user + new group. */
export async function newUser(displayName = "Ana", s: Share = share()) {
  const res = await call("POST", "/groups", { displayName, share: s });
  if (res.status !== 201) throw new Error(`newUser ${res.status} ${JSON.stringify(res.body)}`);
  return res.body as { token: string; group: Group };
}

/** Existing user creates another group. */
export async function anotherGroup(token: string, s: Share = share()) {
  const res = await call("POST", "/groups", { share: s }, token);
  if (res.status !== 201) throw new Error(`anotherGroup ${res.status} ${JSON.stringify(res.body)}`);
  return res.body.group as Group;
}

/** Join as a new user (no token) or as an existing one. Returns the token in use and the group. */
export async function join(inviteCode: string, displayName: string, s: Share = share(), token?: string) {
  const res = await call("POST", "/join", { inviteCode, displayName, share: s }, token);
  if (res.status !== 201) throw new Error(`join ${res.status} ${JSON.stringify(res.body)}`);
  return { token: (res.body.token as string | undefined) ?? token!, group: res.body.group as Group };
}

export const today = (offset = 0) => addDays(utcToday(Date.now()), offset);
```

- [ ] **Step 4: Failing tests**

`server/test/groups.test.ts` (this task's cases; Task 2 appends more):
```ts
import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { anotherGroup, call, join, newUser, share } from "./helpers";

const count = async (table: string) =>
  (await env.monolith_leaderboard.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first<{ n: number }>())!.n;

describe("identity and groups", () => {
  it("creating without a token makes a user, a group and a membership", async () => {
    const res = await call("POST", "/groups", { displayName: " Ana ", share: share(true, false, true) });
    expect(res.status).toBe(201);
    expect(res.body.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(res.body.group).toMatchObject({
      name: null,
      memberCount: 1,
      otherMembers: [],
      share: { saved: true, streak: false, pauses: true },
    });
    expect(res.body.group.inviteCode).toMatch(/^[0-9A-HJKMNP-TV-Z]{8}$/);
    expect(await count("users")).toBe(1);
    expect(await count("memberships")).toBe(1);
  });

  it("only a hash of the token is stored", async () => {
    const { token } = await newUser();
    const row = await env.monolith_leaderboard.prepare("SELECT token_hash FROM users").first<{ token_hash: string }>();
    expect(row!.token_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(row!.token_hash).not.toBe(token);
  });

  it("creating with a token adds a group to the same user and returns no new token", async () => {
    const { token } = await newUser();
    const res = await call("POST", "/groups", { share: share() }, token);
    expect(res.status).toBe(201);
    expect(res.body.token).toBeUndefined();
    expect(await count("users")).toBe(1);
    const me = await call("GET", "/me", undefined, token);
    expect(me.body.groups).toHaveLength(2);
  });

  it("a creation without a token needs a valid display name", async () => {
    for (const body of [{ share: share() }, { displayName: "  ", share: share() }, { displayName: "x".repeat(25), share: share() }]) {
      expect(await call("POST", "/groups", body)).toEqual({ status: 400, body: { error: "invalid_body" } });
    }
  });

  it("a bad token is a 401, never a new identity", async () => {
    expect((await call("POST", "/groups", { displayName: "Ana", share: share() }, "nope")).status).toBe(401);
    expect(await count("users")).toBe(0);
  });

  it("joining as a new user and as an existing user", async () => {
    const ana = await newUser("Ana");
    const ben = await join(ana.group.inviteCode, "Ben");
    expect(ben.group).toMatchObject({ memberCount: 2, otherMembers: ["Ana"] });

    const cat = await newUser("Cat");
    const res = await call("POST", "/join", { inviteCode: ana.group.inviteCode, share: share() }, cat.token);
    expect(res.status).toBe(201);
    expect(res.body.token).toBeUndefined();
    expect(res.body.group.otherMembers.sort()).toEqual(["Ana", "Ben"]);
  });

  it("join normalizes a sloppy invite code", async () => {
    const ana = await newUser();
    const code = ana.group.inviteCode;
    const sloppy = `${code.slice(0, 4).toLowerCase()} - ${code.slice(4).toLowerCase()}`;
    expect((await call("POST", "/join", { inviteCode: sloppy, displayName: "Ben", share: share() })).status).toBe(201);
  });

  it("join errors: unknown code, already a member, full group", async () => {
    const ana = await newUser();
    expect(await call("POST", "/join", { inviteCode: "ZZZZZZZZ", displayName: "Ben", share: share() }))
      .toEqual({ status: 404, body: { error: "invite_not_found" } });
    expect(await call("POST", "/join", { inviteCode: ana.group.inviteCode, share: share() }, ana.token))
      .toEqual({ status: 409, body: { error: "already_member" } });
    for (let i = 1; i < 20; i++) await join(ana.group.inviteCode, `m${i}`);
    expect(await call("POST", "/join", { inviteCode: ana.group.inviteCode, displayName: "late", share: share() }))
      .toEqual({ status: 409, body: { error: "group_full" } });
  });

  it("at most 10 groups per user, for creating and for joining", async () => {
    const ana = await newUser();
    for (let i = 1; i < 10; i++) await anotherGroup(ana.token);
    expect(await call("POST", "/groups", { share: share() }, ana.token))
      .toEqual({ status: 409, body: { error: "too_many_groups" } });
    const other = await newUser("Ben");
    expect(await call("POST", "/join", { inviteCode: other.group.inviteCode, share: share() }, ana.token))
      .toEqual({ status: 409, body: { error: "too_many_groups" } });
  });

  it("GET /me lists groups oldest first; POST /me renames everywhere", async () => {
    const ana = await newUser("Ana");
    const second = await anotherGroup(ana.token, share(false, false, false));
    const me = await call("GET", "/me", undefined, ana.token);
    expect(me.body.displayName).toBe("Ana");
    expect(me.body.groups.map((g: { id: string }) => g.id)).toEqual([ana.group.id, second.id]);

    const ben = await join(ana.group.inviteCode, "Ben");
    const renamed = await call("POST", "/me", { displayName: "Anna" }, ana.token);
    expect(renamed.body.displayName).toBe("Anna");
    const benView = await call("GET", "/me", undefined, ben.token);
    expect(benView.body.groups[0].otherMembers).toEqual(["Anna"]);
    expect((await call("POST", "/me", { displayName: "" }, ana.token)).status).toBe(400);
  });

  it("requires a valid token on authed routes", async () => {
    expect(await call("GET", "/me")).toEqual({ status: 401, body: { error: "unauthorized" } });
  });

  it("unknown routes are not_found", async () => {
    expect((await call("GET", "/nope")).status).toBe(404);
  });
});
```

- [ ] **Step 5: Run to see it fail**

Run: `cd server && npx vitest run test/groups.test.ts`
Expected: FAIL (old handlers, missing tables and helpers).

- [ ] **Step 6: Implement `groups.ts` (create, join, me)**

Replace `server/src/groups.ts` with:
```ts
import { optionalUser } from "./auth";
import { newInviteCode, newToken, normalizeInviteCode, sha256Hex } from "./codes";
import { HttpError, json, readJson } from "./http";
import { MAX_GROUPS, MAX_MEMBERS, shareOf, type Env, type Share, type ShareColumns, type UserRow } from "./types";
import { parseDisplayName, parseShare } from "./validate";

export interface GroupInfo {
  id: string;
  inviteCode: string;
  name: string | null;
  memberCount: number;
  otherMembers: string[];
  share: Share;
}

interface GroupInfoRow extends ShareColumns {
  id: string;
  invite_code: string;
  name: string | null;
  member_count: number;
  others: string;
}

export async function groupInfos(db: D1Database, userId: string): Promise<GroupInfo[]> {
  const { results } = await db
    .prepare(
      `SELECT g.id, g.invite_code, g.name, m.share_saved, m.share_streak, m.share_pauses,
              (SELECT COUNT(*) FROM memberships c WHERE c.group_id = g.id) AS member_count,
              (SELECT json_group_array(u.display_name) FROM memberships o JOIN users u ON u.id = o.user_id
                 WHERE o.group_id = g.id AND o.user_id != ?1) AS others
       FROM memberships m JOIN groups g ON g.id = m.group_id
       WHERE m.user_id = ?1
       ORDER BY m.joined_at, g.id`,
    )
    .bind(userId)
    .all<GroupInfoRow>();
  return results.map((r) => ({
    id: r.id,
    inviteCode: r.invite_code,
    name: r.name,
    memberCount: r.member_count,
    otherMembers: (JSON.parse(r.others) as string[]).sort((a, b) => a.localeCompare(b)),
    share: shareOf(r),
  }));
}

export async function groupInfo(db: D1Database, userId: string, groupId: string): Promise<GroupInfo> {
  const info = (await groupInfos(db, userId)).find((g) => g.id === groupId);
  if (!info) throw new HttpError(404, "not_member");
  return info;
}

async function meBody(db: D1Database, userId: string, displayName: string) {
  return { displayName, groups: await groupInfos(db, userId) };
}

function insertUser(db: D1Database, id: string, name: string, tokenHash: string, now: number) {
  return db
    .prepare("INSERT INTO users (id, token_hash, display_name, created_at) VALUES (?1, ?2, ?3, ?4)")
    .bind(id, tokenHash, name, now);
}

function insertMembership(db: D1Database, userId: string, groupId: string, share: Share, now: number) {
  return db
    .prepare(
      `INSERT INTO memberships (user_id, group_id, share_saved, share_streak, share_pauses, joined_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6)`,
    )
    .bind(userId, groupId, +share.saved, +share.streak, +share.pauses, now);
}

async function assertRoomForAnotherGroup(db: D1Database, userId: string) {
  const row = await db.prepare("SELECT COUNT(*) AS n FROM memberships WHERE user_id = ?1").bind(userId).first<{ n: number }>();
  if ((row?.n ?? 0) >= MAX_GROUPS) throw new HttpError(409, "too_many_groups");
}

/** The existing user, or the statements and token that create a new one from the body's display name. */
async function resolveUser(req: Request, env: Env, body: Record<string, unknown>, now: number) {
  const existing = await optionalUser(req, env);
  if (existing) return { userId: existing.id, token: undefined, create: [] as D1PreparedStatement[] };
  const name = parseDisplayName(body.displayName);
  const userId = crypto.randomUUID();
  const token = newToken();
  return { userId, token, create: [insertUser(env.monolith_leaderboard, userId, name, await sha256Hex(token), now)] };
}

export async function createGroup(req: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(req);
  const share = parseShare(body.share);
  const db = env.monolith_leaderboard;
  const { userId, token, create } = await resolveUser(req, env, body, now);
  if (create.length === 0) await assertRoomForAnotherGroup(db, userId);
  // An invite code collision is astronomically unlikely, but it is a UNIQUE violation, not a crash.
  for (let attempt = 0; attempt < 3; attempt++) {
    const groupId = crypto.randomUUID();
    try {
      await db.batch([
        ...create,
        db.prepare("INSERT INTO groups (id, invite_code, created_at) VALUES (?1, ?2, ?3)").bind(groupId, newInviteCode(), now),
        insertMembership(db, userId, groupId, share, now),
      ]);
      return json({ token, group: await groupInfo(db, userId, groupId) }, 201);
    } catch (e) {
      if (!String(e).includes("UNIQUE")) throw e;
    }
  }
  throw new HttpError(500, "internal");
}

export async function joinGroup(req: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(req);
  const share = parseShare(body.share);
  if (typeof body.inviteCode !== "string") throw new HttpError(400, "invalid_body");
  const code = normalizeInviteCode(body.inviteCode);
  if (!code) throw new HttpError(404, "invite_not_found");
  const db = env.monolith_leaderboard;
  const group = await db.prepare("SELECT id FROM groups WHERE invite_code = ?1").bind(code).first<{ id: string }>();
  if (!group) throw new HttpError(404, "invite_not_found");

  const { userId, token, create } = await resolveUser(req, env, body, now);
  if (create.length === 0) {
    const already = await db
      .prepare("SELECT 1 AS x FROM memberships WHERE user_id = ?1 AND group_id = ?2")
      .bind(userId, group.id)
      .first();
    if (already) throw new HttpError(409, "already_member");
  }
  const size = await db.prepare("SELECT COUNT(*) AS n FROM memberships WHERE group_id = ?1").bind(group.id).first<{ n: number }>();
  // Count then insert: two joins racing for the last seat can make it 21. Accepted for friends.
  if ((size?.n ?? 0) >= MAX_MEMBERS) throw new HttpError(409, "group_full");
  if (create.length === 0) await assertRoomForAnotherGroup(db, userId);

  await db.batch([...create, insertMembership(db, userId, group.id, share, now)]);
  return json({ token, group: await groupInfo(db, userId, group.id) }, 201);
}

export async function getMe(_req: Request, env: Env, user: UserRow): Promise<Response> {
  return json(await meBody(env.monolith_leaderboard, user.id, user.display_name));
}

export async function updateMe(req: Request, env: Env, user: UserRow): Promise<Response> {
  const body = await readJson(req);
  const name = parseDisplayName(body.displayName);
  const db = env.monolith_leaderboard;
  await db.prepare("UPDATE users SET display_name = ?2 WHERE id = ?1").bind(user.id, name).run();
  return json(await meBody(db, user.id, name));
}
```

- [ ] **Step 7: Router**

Replace `server/src/index.ts`:
```ts
import { authenticate } from "./auth";
import { createGroup, getMe, joinGroup, updateMe } from "./groups";
import { HttpError, json } from "./http";
import type { Env, UserRow } from "./types";

type AuthedHandler = (req: Request, env: Env, user: UserRow, now: number) => Promise<Response>;
type GroupHandler = (req: Request, env: Env, user: UserRow, now: number, groupId: string) => Promise<Response>;

export const authedRoutes: Record<string, AuthedHandler> = {
  "GET /me": getMe,
  "POST /me": updateMe,
};

/** Keyed by method plus "" for /groups/:id or "/board" for /groups/:id/board. */
export const groupRoutes: Record<string, GroupHandler> = {};

const GROUP_PATH = /^\/groups\/([^/]+)(\/board)?$/;

async function route(req: Request, env: Env, now: number): Promise<Response> {
  const path = new URL(req.url).pathname;
  const key = `${req.method} ${path}`;
  if (key === "POST /groups") return createGroup(req, env, now);
  if (key === "POST /join") return joinGroup(req, env, now);

  const groupMatch = path.match(GROUP_PATH);
  if (groupMatch) {
    const handler = groupRoutes[`${req.method} ${groupMatch[2] ?? ""}`];
    if (!handler) throw new HttpError(404, "not_found");
    return handler(req, env, await authenticate(req, env), now, decodeURIComponent(groupMatch[1]));
  }

  const handler = authedRoutes[key];
  if (!handler) throw new HttpError(404, "not_found");
  return handler(req, env, await authenticate(req, env), now);
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try {
      return await route(req, env, Date.now());
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.code }, e.status);
      return json({ error: "internal" }, 500);
    }
  },
} satisfies ExportedHandler<Env>;
```

Delete `server/test/me.test.ts`, `server/test/sync.test.ts` and `server/test/board.test.ts`. `server/src/sync.ts` and `server/src/board.ts` are unrouted until Task 3; in this task only replace `MemberRow` with `UserRow` in their imports and signatures (and `shareOf(member)` with `shareOf(member as never)` where needed) so `npx tsc` passes. Their SQL is stale on purpose; Task 3 rewrites both files.

- [ ] **Step 8: Run tests and typecheck**

Run: `cd server && npx vitest run && npx tsc`
Expected: all remaining tests PASS (`codes`, `dates`, `groups`); tsc exits 0.

- [ ] **Step 9: Commit**

```bash
git add -A server
git commit -m "feat(server): one identity across several groups"
```

---

### Task 2: Server group updates, leaving, invariants

**Files:**
- Create: `server/src/invariants.ts`
- Modify: `server/src/groups.ts` (add `updateGroup`, `leaveGroup`), `server/src/index.ts` (register in `groupRoutes`)
- Test: append to `server/test/groups.test.ts`

**Interfaces:**
- Consumes: Task 1 (`groupInfo`, `parseGroupName`, `parseShare`, `groupRoutes`).
- Produces: `invariantStatements(db, userIds: string[], groupIds: string[]): D1PreparedStatement[]`; routes `POST /groups/:id {share?, name?}` → `{group}`, `DELETE /groups/:id` → 204.

- [ ] **Step 1: Failing tests** (append inside a new `describe` in `groups.test.ts`)

```ts
describe("updating and leaving groups", () => {
  const stored = (userName: string) =>
    env.monolith_leaderboard
      .prepare(
        `SELECT u.streak_started_at, d.saved_ms, d.bypass_count, d.unlock_count
         FROM users u JOIN days d ON d.user_id = u.id WHERE u.display_name = ?1`,
      )
      .bind(userName)
      .first();

  async function seed(userName: string) {
    await env.monolith_leaderboard.batch([
      env.monolith_leaderboard.prepare("UPDATE users SET streak_started_at = 1000 WHERE display_name = ?1").bind(userName),
      env.monolith_leaderboard
        .prepare("INSERT INTO days (user_id, date, saved_ms, bypass_count, unlock_count) SELECT id, '2026-09-01', 5, 1, 2 FROM users WHERE display_name = ?1")
        .bind(userName),
    ]);
  }

  it("share changes are per group and returned in the group", async () => {
    const ana = await newUser("Ana");
    const second = await anotherGroup(ana.token);
    const res = await call("POST", `/groups/${second.id}`, { share: share(false, true, true) }, ana.token);
    expect(res.status).toBe(200);
    expect(res.body.group.share).toEqual({ saved: false, streak: true, pauses: true });
    const me = await call("GET", "/me", undefined, ana.token);
    expect(me.body.groups[0].share.saved).toBe(true);
  });

  it("hiding a signal in one group keeps the data while another group shares it", async () => {
    const ana = await newUser("Ana");
    const second = await anotherGroup(ana.token);
    await seed("Ana");
    await call("POST", `/groups/${second.id}`, { share: share(false, false, false) }, ana.token);
    expect(await stored("Ana")).toEqual({ streak_started_at: 1000, saved_ms: 5, bypass_count: 1, unlock_count: 2 });
  });

  it("hiding a signal everywhere nulls it", async () => {
    const ana = await newUser("Ana");
    const second = await anotherGroup(ana.token);
    await seed("Ana");
    await call("POST", `/groups/${ana.group.id}`, { share: share(false, true, false) }, ana.token);
    await call("POST", `/groups/${second.id}`, { share: share(false, false, false) }, ana.token);
    expect(await stored("Ana")).toEqual({ streak_started_at: 1000, saved_ms: null, bypass_count: null, unlock_count: null });
    await call("POST", `/groups/${ana.group.id}`, { share: share(false, false, false) }, ana.token);
    expect(await stored("Ana")).toMatchObject({ streak_started_at: null });
  });

  it("names need three and vanish below three", async () => {
    const ana = await newUser("Ana");
    const ben = await join(ana.group.inviteCode, "Ben");
    expect(await call("POST", `/groups/${ana.group.id}`, { name: "Flat" }, ana.token))
      .toEqual({ status: 409, body: { error: "name_needs_three" } });

    const cat = await join(ana.group.inviteCode, "Cat");
    const named = await call("POST", `/groups/${ana.group.id}`, { name: "  Flatmates " }, ben.token);
    expect(named.body.group.name).toBe("Flatmates");
    expect((await call("POST", `/groups/${ana.group.id}`, { name: "x".repeat(33) }, ana.token)).status).toBe(400);

    expect((await call("DELETE", `/groups/${ana.group.id}`, undefined, cat.token)).status).toBe(204);
    const me = await call("GET", "/me", undefined, ana.token);
    expect(me.body.groups[0]).toMatchObject({ name: null, memberCount: 2, otherMembers: ["Ben"] });
  });

  it("an empty name clears it", async () => {
    const ana = await newUser("Ana");
    await join(ana.group.inviteCode, "Ben");
    await join(ana.group.inviteCode, "Cat");
    await call("POST", `/groups/${ana.group.id}`, { name: "Flat" }, ana.token);
    const cleared = await call("POST", `/groups/${ana.group.id}`, { name: "" }, ana.token);
    expect(cleared.body.group.name).toBeNull();
  });

  it("leaving one group keeps the user; leaving the last deletes them and their days", async () => {
    const ana = await newUser("Ana");
    const second = await anotherGroup(ana.token);
    await seed("Ana");
    expect((await call("DELETE", `/groups/${ana.group.id}`, undefined, ana.token)).status).toBe(204);
    expect((await call("GET", "/me", undefined, ana.token)).body.groups).toHaveLength(1);
    expect((await call("DELETE", `/groups/${second.id}`, undefined, ana.token)).status).toBe(204);
    expect((await call("GET", "/me", undefined, ana.token)).status).toBe(401);
    expect(await count("users")).toBe(0);
    expect(await count("days")).toBe(0);
    expect(await count("groups")).toBe(0);
  });

  it("a group is deleted when its last member leaves, others remain", async () => {
    const ana = await newUser("Ana");
    const ben = await join(ana.group.inviteCode, "Ben");
    await anotherGroup(ben.token);
    await call("DELETE", `/groups/${ana.group.id}`, undefined, ana.token);
    expect(await count("groups")).toBe(2);
    await call("DELETE", `/groups/${ana.group.id}`, undefined, ben.token);
    expect(await count("groups")).toBe(1);
  });

  it("updates and leaves for a group you're not in are not_member", async () => {
    const ana = await newUser("Ana");
    const ben = await newUser("Ben");
    expect(await call("POST", `/groups/${ben.group.id}`, { share: share() }, ana.token))
      .toEqual({ status: 404, body: { error: "not_member" } });
    expect((await call("DELETE", `/groups/${ben.group.id}`, undefined, ana.token)).status).toBe(404);
    expect((await call("DELETE", "/groups/nope", undefined, ana.token)).status).toBe(404);
  });
});
```

- [ ] **Step 2: Run to see it fail**

Run: `cd server && npx vitest run test/groups.test.ts`
Expected: the new cases FAIL with 404 `not_found`.

- [ ] **Step 3: Invariants**

`server/src/invariants.ts`:
```ts
/**
 * Statements that restore the spec's invariants after memberships changed. Append them to the
 * same batch as the change so both commit together.
 *   1. A group with no members is deleted.
 *   2. A group with fewer than 3 members has no name.
 *   3. A signal none of a user's memberships share is not stored.
 *   4. A user with no memberships is deleted, with their days.
 */
export function invariantStatements(db: D1Database, userIds: string[], groupIds: string[]): D1PreparedStatement[] {
  const statements: D1PreparedStatement[] = [];
  for (const g of groupIds) {
    statements.push(
      db.prepare("DELETE FROM groups WHERE id = ?1 AND NOT EXISTS (SELECT 1 FROM memberships WHERE group_id = ?1)").bind(g),
      db.prepare("UPDATE groups SET name = NULL WHERE id = ?1 AND (SELECT COUNT(*) FROM memberships WHERE group_id = ?1) < 3").bind(g),
    );
  }
  const noneShares = (column: string) =>
    `NOT EXISTS (SELECT 1 FROM memberships WHERE user_id = ?1 AND ${column} = 1)`;
  for (const u of userIds) {
    statements.push(
      db.prepare(`UPDATE days SET saved_ms = NULL WHERE user_id = ?1 AND ${noneShares("share_saved")}`).bind(u),
      db.prepare(`UPDATE users SET streak_started_at = NULL WHERE id = ?1 AND ${noneShares("share_streak")}`).bind(u),
      db.prepare(`UPDATE days SET bypass_count = NULL, unlock_count = NULL WHERE user_id = ?1 AND ${noneShares("share_pauses")}`).bind(u),
      db.prepare("DELETE FROM days WHERE user_id = ?1 AND NOT EXISTS (SELECT 1 FROM memberships WHERE user_id = ?1)").bind(u),
      db.prepare("DELETE FROM users WHERE id = ?1 AND NOT EXISTS (SELECT 1 FROM memberships WHERE user_id = ?1)").bind(u),
    );
  }
  return statements;
}
```

- [ ] **Step 4: Handlers**

Append to `server/src/groups.ts` (add imports `empty` from `./http`, `parseGroupName` from `./validate`, `invariantStatements` from `./invariants`):
```ts
async function assertMember(db: D1Database, userId: string, groupId: string) {
  const row = await db
    .prepare("SELECT 1 AS x FROM memberships WHERE user_id = ?1 AND group_id = ?2")
    .bind(userId, groupId)
    .first();
  if (!row) throw new HttpError(404, "not_member");
}

export async function updateGroup(req: Request, env: Env, user: UserRow, _now: number, groupId: string): Promise<Response> {
  const db = env.monolith_leaderboard;
  await assertMember(db, user.id, groupId);
  const body = await readJson(req);
  const statements: D1PreparedStatement[] = [];
  if (body.share !== undefined) {
    const share = parseShare(body.share);
    statements.push(
      db
        .prepare("UPDATE memberships SET share_saved = ?3, share_streak = ?4, share_pauses = ?5 WHERE user_id = ?1 AND group_id = ?2")
        .bind(user.id, groupId, +share.saved, +share.streak, +share.pauses),
    );
  }
  if (body.name !== undefined) {
    const name = parseGroupName(body.name);
    if (name !== null) {
      const size = await db.prepare("SELECT COUNT(*) AS n FROM memberships WHERE group_id = ?1").bind(groupId).first<{ n: number }>();
      if ((size?.n ?? 0) < 3) throw new HttpError(409, "name_needs_three");
    }
    statements.push(db.prepare("UPDATE groups SET name = ?2 WHERE id = ?1").bind(groupId, name));
  }
  if (statements.length > 0) await db.batch([...statements, ...invariantStatements(db, [user.id], [groupId])]);
  return json({ group: await groupInfo(db, user.id, groupId) });
}

export async function leaveGroup(_req: Request, env: Env, user: UserRow, _now: number, groupId: string): Promise<Response> {
  const db = env.monolith_leaderboard;
  await assertMember(db, user.id, groupId);
  await db.batch([
    db.prepare("DELETE FROM memberships WHERE user_id = ?1 AND group_id = ?2").bind(user.id, groupId),
    ...invariantStatements(db, [user.id], [groupId]),
  ]);
  return empty();
}
```

In `server/src/index.ts` import them and set:
```ts
export const groupRoutes: Record<string, GroupHandler> = {
  "POST ": updateGroup,
  "DELETE ": leaveGroup,
};
```

- [ ] **Step 5: Run tests and typecheck**

Run: `cd server && npx vitest run && npx tsc`
Expected: PASS; tsc 0.

- [ ] **Step 6: Commit**

```bash
git add -A server
git commit -m "feat(server): per-group sharing, names and leaving"
```

---

### Task 3: Server sync and per-group board

**Files:**
- Rewrite: `server/src/sync.ts`, `server/src/board.ts` (keep `rankRows`, `BoardRow`, `BoardQueryRow` exports as they are)
- Modify: `server/src/index.ts`
- Create: `server/test/sync.test.ts`, `server/test/board.test.ts`

**Interfaces:**
- Consumes: `invariantStatements`, `groupRoutes`, `authedRoutes`, `shareOf`, `parseSync(body, share, now)` (unchanged), helpers.
- Produces: `POST /sync` (union share), `GET /groups/:id/board?window&date`.

- [ ] **Step 1: Failing tests**

`server/test/sync.test.ts`:
```ts
import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { anotherGroup, call, newUser, share, today } from "./helpers";

const days = () =>
  env.monolith_leaderboard.prepare("SELECT date, saved_ms, bypass_count, unlock_count FROM days ORDER BY date").all().then((r) => r.results);

describe("POST /sync", () => {
  it("stores one copy of the days for the user, streak and sync time", async () => {
    const ana = await newUser();
    await anotherGroup(ana.token);
    const res = await call(
      "POST",
      "/sync",
      { days: [{ date: today(), savedMs: 3600000, bypassCount: 1, unlockCount: 2 }], streakStartedAt: 1234 },
      ana.token,
    );
    expect(res.status).toBe(204);
    expect(await days()).toEqual([{ date: today(), saved_ms: 3600000, bypass_count: 1, unlock_count: 2 }]);
    const u = await env.monolith_leaderboard.prepare("SELECT streak_started_at, last_sync_at FROM users").first<{ streak_started_at: number; last_sync_at: number }>();
    expect(u!.streak_started_at).toBe(1234);
    expect(u!.last_sync_at).toBeGreaterThan(Date.now() - 60000);
  });

  it("is idempotent and the latest upload wins", async () => {
    const ana = await newUser();
    const body = (saved: number) => ({ days: [{ date: today(), savedMs: saved, bypassCount: 0, unlockCount: 0 }] });
    await call("POST", "/sync", body(100), ana.token);
    await call("POST", "/sync", body(250), ana.token);
    expect(await days()).toEqual([{ date: today(), saved_ms: 250, bypass_count: 0, unlock_count: 0 }]);
  });

  it("union decides hidden_signal", async () => {
    const ana = await newUser("Ana", share(false, false, false));
    const second = await anotherGroup(ana.token, share(true, false, false));
    expect((await call("POST", "/sync", { days: [{ date: today(), savedMs: 1 }] }, ana.token)).status).toBe(204);
    expect(await call("POST", "/sync", { days: [{ date: today(), bypassCount: 1 }] }, ana.token))
      .toEqual({ status: 422, body: { error: "hidden_signal" } });
    expect(await call("POST", "/sync", { days: [], streakStartedAt: 5 }, ana.token))
      .toEqual({ status: 422, body: { error: "hidden_signal" } });
    await call("POST", `/groups/${second.id}`, { share: share(false, false, false) }, ana.token);
    expect((await call("POST", "/sync", { days: [{ date: today(), savedMs: 1 }] }, ana.token)).status).toBe(422);
  });

  it("accepts the window edges, clamps a future streak, prunes old days", async () => {
    const ana = await newUser();
    await env.monolith_leaderboard.prepare("INSERT INTO days (user_id, date, saved_ms) SELECT id, ?1, 1 FROM users").bind(today(-40)).run();
    const edge = [today(-36), today(1)].map((date) => ({ date, savedMs: 1, bypassCount: 0, unlockCount: 0 }));
    const future = Date.now() + 3_600_000;
    expect((await call("POST", "/sync", { days: edge, streakStartedAt: future }, ana.token)).status).toBe(204);
    expect((await days()).map((d) => d.date)).toEqual([today(-36), today(1)]);
    const u = await env.monolith_leaderboard.prepare("SELECT streak_started_at FROM users").first<{ streak_started_at: number }>();
    expect(u!.streak_started_at).toBeLessThanOrEqual(Date.now());
  });

  it("rejects out-of-range bodies", async () => {
    const ana = await newUser();
    for (const body of [
      { days: [{ date: today(-37), savedMs: 1 }] },
      { days: [{ date: today(), savedMs: 86400001 }] },
      { days: [{ date: today() }, { date: today() }] },
      { days: "nope" },
    ]) {
      expect((await call("POST", "/sync", body, ana.token)).status).toBe(400);
    }
  });
});
```

`server/test/board.test.ts`:
```ts
import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { anotherGroup, call, join, newUser, share, today } from "./helpers";

async function syncDay(token: string, savedMs: number, bypassCount = 0, unlockCount = 0, streakStartedAt?: number) {
  const res = await call("POST", "/sync", { days: [{ date: today(), savedMs, bypassCount, unlockCount }], streakStartedAt }, token);
  expect(res.status).toBe(204);
}

const board = (token: string, groupId: string, window = "day") =>
  call("GET", `/groups/${groupId}/board?window=${window}&date=${today()}`, undefined, token);

describe("GET /groups/:id/board", () => {
  it("ranks members of that group only and marks me", async () => {
    const ana = await newUser("Ana");
    const ben = await join(ana.group.inviteCode, "Ben");
    const outsider = await newUser("Zed");
    await syncDay(ana.token, 100, 1, 2, 777);
    await syncDay(ben.token, 300);
    await syncDay(outsider.token, 999);
    const res = await board(ana.token, ana.group.id);
    expect(res.body.rows.map((r: { name: string; rank: number }) => [r.name, r.rank])).toEqual([["Ben", 1], ["Ana", 2]]);
    expect(res.body.rows[1]).toMatchObject({ isMe: true, savedMs: 100, bypassCount: 1, unlockCount: 2, streak: { startedAt: 777 } });
  });

  it("per-group reciprocity", async () => {
    const ana = await newUser("Ana", share(true, true, true));
    const ben = await join(ana.group.inviteCode, "Ben", share(true, true, true));
    const second = await anotherGroup(ana.token, share(true, false, false));
    await join(second.inviteCode, "Ben", share(true, true, true), ben.token);
    await syncDay(ben.token, 50, 3, 1, 555);

    const open = (await board(ana.token, ana.group.id)).body.rows.find((r: { name: string }) => r.name === "Ben");
    expect(open).toMatchObject({ savedMs: 50, bypassCount: 3, streak: { startedAt: 555 } });

    const strict = (await board(ana.token, second.id)).body.rows.find((r: { name: string }) => r.name === "Ben");
    expect(strict.savedMs).toBe(50);
    expect(strict.streak).toBeUndefined();
    expect(strict.bypassCount).toBeUndefined();
  });

  it("a viewer hiding time gained gets no ranks, rows by name", async () => {
    const ana = await newUser("Ana");
    const zed = await join(ana.group.inviteCode, "Zed", share(false, true, true));
    await syncDay(ana.token, 999);
    const rows = (await board(zed.token, ana.group.id)).body.rows;
    expect(rows.map((r: { name: string }) => r.name)).toEqual(["Ana", "Zed"]);
    for (const r of rows) expect(r.rank).toBeUndefined();
  });

  it("a board for a group you're not in is not_member", async () => {
    const ana = await newUser("Ana");
    const ben = await newUser("Ben");
    expect(await board(ana.token, ben.group.id)).toEqual({ status: 404, body: { error: "not_member" } });
  });

  it("cleanup removes stale users from every group", async () => {
    const ana = await newUser("Ana");
    const ghost = await join(ana.group.inviteCode, "Ghost");
    const ghostOnly = await anotherGroup(ghost.token);
    await join(ana.group.inviteCode, "Recent");
    const old = Date.now() - 121 * 24 * 60 * 60 * 1000;
    await env.monolith_leaderboard.batch([
      env.monolith_leaderboard.prepare("UPDATE users SET created_at = ?1").bind(old),
      env.monolith_leaderboard.prepare("UPDATE users SET last_sync_at = ?1 WHERE display_name IN ('Ana', 'Recent')").bind(Date.now()),
    ]);
    const names = (await board(ana.token, ana.group.id)).body.rows.map((r: { name: string }) => r.name);
    expect(names).toEqual(["Ana", "Recent"]);
    const leftover = await env.monolith_leaderboard.prepare("SELECT COUNT(*) AS n FROM groups WHERE id = ?1").bind(ghostOnly.id).first<{ n: number }>();
    expect(leftover!.n).toBe(0);
    expect((await call("GET", "/me", undefined, ghost.token)).status).toBe(401);
  });

  it("rejects a bad window or date", async () => {
    const ana = await newUser();
    expect((await call("GET", `/groups/${ana.group.id}/board?window=year&date=${today()}`, undefined, ana.token)).status).toBe(400);
    expect((await call("GET", `/groups/${ana.group.id}/board?window=day&date=2026-13-01`, undefined, ana.token)).status).toBe(400);
  });
});
```

- [ ] **Step 2: Run to see it fail**

Run: `cd server && npx vitest run test/sync.test.ts test/board.test.ts`
Expected: FAIL (routes missing).

- [ ] **Step 3: Implement sync**

Replace `server/src/sync.ts`:
```ts
import { addDays, utcToday } from "./dates";
import { empty, readJson } from "./http";
import { RETENTION_DAYS, type Env, type Share, type UserRow } from "./types";
import { parseSync } from "./validate";

/** What at least one of the user's groups may see. Nothing else is accepted or stored. */
async function unionShare(db: D1Database, userId: string): Promise<Share> {
  const row = await db
    .prepare("SELECT MAX(share_saved) AS saved, MAX(share_streak) AS streak, MAX(share_pauses) AS pauses FROM memberships WHERE user_id = ?1")
    .bind(userId)
    .first<{ saved: number | null; streak: number | null; pauses: number | null }>();
  return { saved: row?.saved === 1, streak: row?.streak === 1, pauses: row?.pauses === 1 };
}

export async function sync(req: Request, env: Env, user: UserRow, now: number): Promise<Response> {
  const db = env.monolith_leaderboard;
  const body = parseSync(await readJson(req), await unionShare(db, user.id), now);
  const statements = body.days.map((d) =>
    db
      .prepare(
        `INSERT INTO days (user_id, date, saved_ms, bypass_count, unlock_count) VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT (user_id, date) DO UPDATE SET
           saved_ms = excluded.saved_ms, bypass_count = excluded.bypass_count, unlock_count = excluded.unlock_count`,
      )
      .bind(user.id, d.date, d.savedMs, d.bypassCount, d.unlockCount),
  );
  statements.push(
    db.prepare("UPDATE users SET streak_started_at = ?2, last_sync_at = ?3 WHERE id = ?1").bind(user.id, body.streakStartedAt, now),
    // Same edge as parseSync's earliest accepted date, so nothing accepted is pruned right away.
    db.prepare("DELETE FROM days WHERE user_id = ?1 AND date < ?2").bind(user.id, addDays(utcToday(now), -(RETENTION_DAYS + 1))),
  );
  await db.batch(statements);
  return empty();
}
```

- [ ] **Step 4: Implement the board**

In `server/src/board.ts` keep the interfaces and `rankRows` exactly; replace the `board` function and its imports:
```ts
import { isIsoDate, windowRange, type BoardWindow } from "./dates";
import { HttpError, json } from "./http";
import { invariantStatements } from "./invariants";
import { DAY_MS, INACTIVE_DAYS, shareOf, type Env, type Share, type ShareColumns, type UserRow } from "./types";

// ... BoardQueryRow, BoardRow, rankRows unchanged ...

/**
 * Lazy cleanup instead of a cron: a flat group has no admin, so this is what frees seats.
 * A stale user is deleted outright, which removes them from every group they were in.
 */
async function removeStaleMembers(db: D1Database, groupId: string, viewerId: string, now: number) {
  const staleBefore = now - INACTIVE_DAYS * DAY_MS;
  const { results: stale } = await db
    .prepare(
      `SELECT u.id FROM memberships m JOIN users u ON u.id = m.user_id
       WHERE m.group_id = ?1 AND u.id != ?2 AND COALESCE(u.last_sync_at, u.created_at) < ?3`,
    )
    .bind(groupId, viewerId, staleBefore)
    .all<{ id: string }>();
  if (stale.length === 0) return;
  const userIds = stale.map((s) => s.id);
  const placeholders = userIds.map((_, i) => `?${i + 1}`).join(", ");
  const { results: touched } = await db
    .prepare(`SELECT DISTINCT group_id FROM memberships WHERE user_id IN (${placeholders})`)
    .bind(...userIds)
    .all<{ group_id: string }>();
  await db.batch([
    db.prepare(`DELETE FROM memberships WHERE user_id IN (${placeholders})`).bind(...userIds),
    ...invariantStatements(db, userIds, touched.map((t) => t.group_id)),
  ]);
}

export async function board(req: Request, env: Env, user: UserRow, now: number, groupId: string): Promise<Response> {
  const db = env.monolith_leaderboard;
  const viewer = await db
    .prepare("SELECT share_saved, share_streak, share_pauses FROM memberships WHERE user_id = ?1 AND group_id = ?2")
    .bind(user.id, groupId)
    .first<ShareColumns>();
  if (!viewer) throw new HttpError(404, "not_member");

  const params = new URL(req.url).searchParams;
  const window = params.get("window");
  const date = params.get("date");
  if ((window !== "day" && window !== "week" && window !== "month") || !isIsoDate(date)) {
    throw new HttpError(400, "invalid_body");
  }
  const { from, to } = windowRange(window as BoardWindow, date);

  await removeStaleMembers(db, groupId, user.id, now);

  const { results } = await db
    .prepare(
      `SELECT u.id, u.display_name, m.share_saved, m.share_streak, m.share_pauses, u.streak_started_at, u.last_sync_at,
              SUM(d.saved_ms) AS saved, SUM(d.bypass_count) AS bypass, SUM(d.unlock_count) AS unlock
       FROM memberships m
       JOIN users u ON u.id = m.user_id
       LEFT JOIN days d ON d.user_id = u.id AND d.date BETWEEN ?2 AND ?3
       WHERE m.group_id = ?1
       GROUP BY u.id`,
    )
    .bind(groupId, from, to)
    .all<BoardQueryRow>();

  return json({ rows: rankRows(results, user.id, shareOf(viewer) as Share) });
}
```
(`BoardQueryRow` field names are unchanged; `id` is now the user id.)

In `server/src/index.ts`: add `"POST /sync": sync` to `authedRoutes` and `"GET /board": board` to `groupRoutes`.

- [ ] **Step 5: Run everything**

Run: `cd server && npx vitest run && npx tsc`
Expected: all PASS; tsc 0.

- [ ] **Step 6: Commit**

```bash
git add -A server
git commit -m "feat(server): per-group boards and one upload per user"
```

---

### Task 4: Deploy the server (controller)

- [ ] Run `cd server && npx wrangler d1 migrations apply monolith-leaderboard --remote` (applies `0002`, resetting data), then `npx wrangler deploy`.
- [ ] Smoke test against production: create a user and group, create a second group with the token, `GET /me` lists 2, board for each returns one row, leave both, `GET /me` is 401.

No commit.

---

### Task 5: Android domain, wire format and API

**Files:**
- Modify: `app/src/main/kotlin/com/monolith/app/domain/model/Leaderboard.kt`
- Create: `app/src/main/kotlin/com/monolith/app/domain/usecase/Groups.kt`
- Modify: `app/src/main/kotlin/com/monolith/app/data/leaderboard/LeaderboardDtos.kt`, `LeaderboardApi.kt`
- Test: `app/src/test/kotlin/com/monolith/app/domain/usecase/GroupsTest.kt`, update `app/src/test/kotlin/com/monolith/app/data/leaderboard/LeaderboardDtosTest.kt`

This task changes types used by the repository, syncer and UI; those will not compile until Task 6 and 7. Keep this task's commit compiling by doing Tasks 5 and 6 as one commit series on the same dispatch: implement Task 5, run only `GroupsTest` and `LeaderboardDtosTest` via `./gradlew testDebugUnitTest --tests '...'` after Task 6 compiles. The controller dispatches Tasks 5 and 6 to one implementer.

**Interfaces:**
- Produces (domain):
```kotlin
data class GroupInfo(val id: String, val inviteCode: String, val name: String?, val memberCount: Int, val otherMembers: List<String>, val share: ShareSettings)
data class Identity(val token: String, val displayName: String, val groups: List<GroupInfo>)
data class GroupLabel(val text: String, val isCode: Boolean)
enum class LeaderboardError { NETWORK, UNAUTHORIZED, INVITE_NOT_FOUND, GROUP_FULL, TOO_MANY_GROUPS, ALREADY_MEMBER, NAME_NEEDS_THREE, NOT_MEMBER, HIDDEN_SIGNAL, INVALID, SERVER }
fun shareUnion(groups: List<GroupInfo>): ShareSettings
fun groupLabel(group: GroupInfo): GroupLabel
```
`GroupMembership` is deleted.
- Produces (data): `GroupDto`, `GroupResponse(token: String? = null, group: GroupDto)`, `MeResponse(displayName, groups: List<GroupDto>)`, `CreateGroupRequest(displayName: String? = null, share: ShareDto)`, `JoinRequest(inviteCode, displayName: String? = null, share)`, `UpdateMeRequest(displayName: String)`, `UpdateGroupRequest(share: ShareDto? = null, name: String? = null)` (clear a name by sending `""`), `GroupDto.toDomain()`, `LeaderboardApi` methods:
```kotlin
suspend fun createGroup(token: String?, request: CreateGroupRequest): LeaderboardResult<GroupResponse>
suspend fun join(token: String?, request: JoinRequest): LeaderboardResult<GroupResponse>
suspend fun me(token: String): LeaderboardResult<MeResponse>
suspend fun updateMe(token: String, request: UpdateMeRequest): LeaderboardResult<MeResponse>
suspend fun updateGroup(token: String, groupId: String, request: UpdateGroupRequest): LeaderboardResult<GroupResponse>
suspend fun leaveGroup(token: String, groupId: String): LeaderboardResult<Unit>
suspend fun sync(token: String, request: SyncRequest): LeaderboardResult<Unit>
suspend fun board(token: String, groupId: String, window: BoardWindow, date: LocalDate): LeaderboardResult<BoardResponse>
```

- [ ] **Step 1: Failing tests**

`GroupsTest.kt`:
```kotlin
package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.GroupLabel
import com.monolith.app.domain.model.ShareSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupsTest {
    private fun group(memberCount: Int, others: List<String>, name: String? = null, share: ShareSettings = ShareSettings(true, true, true)) =
        GroupInfo("g", "ABCDEFGH", name, memberCount, others, share)

    @Test
    fun `a signal is uploaded when any group shares it`() {
        val union = shareUnion(listOf(group(2, listOf("Sam"), share = ShareSettings(true, false, false)), group(2, listOf("Lea"), share = ShareSettings(false, false, true))))
        assertEquals(ShareSettings(saved = true, streak = false, pauses = true), union)
        assertEquals(ShareSettings(false, false, false), shareUnion(emptyList()))
    }

    @Test
    fun `labels follow group size`() {
        assertEquals(GroupLabel("ABCDEFGH", isCode = true), groupLabel(group(1, emptyList())))
        assertEquals(GroupLabel("Sam", isCode = false), groupLabel(group(2, listOf("Sam"))))
        assertEquals(GroupLabel("Lea, Max", isCode = false), groupLabel(group(3, listOf("Max", "Lea"))))
        assertEquals(GroupLabel("Flat", isCode = false), groupLabel(group(3, listOf("Max", "Lea"), name = "Flat")))
    }
}
```

In `LeaderboardDtosTest.kt` replace tests referencing removed types and add:
```kotlin
    @Test
    fun `group responses decode, with or without a token`() {
        val withToken = LeaderboardJson.decodeFromString<GroupResponse>(
            """{"token":"t","group":{"id":"g","inviteCode":"ABCDEFGH","name":null,"memberCount":2,"otherMembers":["Sam"],"share":{"saved":true,"streak":false,"pauses":true}}}""",
        )
        assertEquals("t", withToken.token)
        assertEquals(GroupInfo("g", "ABCDEFGH", null, 2, listOf("Sam"), ShareSettings(true, false, true)), withToken.group.toDomain())
        val without = LeaderboardJson.decodeFromString<GroupResponse>("""{"group":{"id":"g","inviteCode":"ABCDEFGH","memberCount":1,"otherMembers":[],"share":{"saved":true,"streak":true,"pauses":true}}}""")
        assertEquals(null, without.token)
    }

    @Test
    fun `new error codes map`() {
        assertEquals(LeaderboardError.TOO_MANY_GROUPS, errorOf("too_many_groups"))
        assertEquals(LeaderboardError.ALREADY_MEMBER, errorOf("already_member"))
        assertEquals(LeaderboardError.NAME_NEEDS_THREE, errorOf("name_needs_three"))
        assertEquals(LeaderboardError.NOT_MEMBER, errorOf("not_member"))
    }

    @Test
    fun `a create with a token sends no display name`() {
        assertEquals(
            """{"share":{"saved":true,"streak":true,"pauses":true}}""",
            LeaderboardJson.encodeToString(CreateGroupRequest(share = ShareDto(true, true, true))),
        )
    }
```
Keep the existing `syncRequestOf` tests (the function is unchanged).

- [ ] **Step 2: Implement**

`Leaderboard.kt`: delete `GroupMembership`, add `GroupInfo`, `Identity`, `GroupLabel` and the new error values as in Interfaces.

`domain/usecase/Groups.kt`:
```kotlin
package com.monolith.app.domain.usecase

import com.monolith.app.domain.model.GroupInfo
import com.monolith.app.domain.model.GroupLabel
import com.monolith.app.domain.model.ShareSettings

/** A signal leaves the phone only if at least one group may see it. */
fun shareUnion(groups: List<GroupInfo>): ShareSettings = ShareSettings(
    saved = groups.any { it.share.saved },
    streak = groups.any { it.share.streak },
    pauses = groups.any { it.share.pauses },
)

/**
 * Two people: the group is the other person. Three or more: its shared name, else everyone
 * else's names. Alone (just created): the invite code, which is what you'd share next anyway.
 */
fun groupLabel(group: GroupInfo): GroupLabel = when {
    group.memberCount <= 1 || group.otherMembers.isEmpty() -> GroupLabel(group.inviteCode, isCode = true)
    group.memberCount == 2 -> GroupLabel(group.otherMembers.first(), isCode = false)
    else -> GroupLabel(group.name ?: group.otherMembers.sorted().joinToString(", "), isCode = false)
}
```

`LeaderboardDtos.kt`: add
```kotlin
@Serializable
data class GroupDto(
    val id: String,
    val inviteCode: String,
    val name: String? = null,
    val memberCount: Int,
    val otherMembers: List<String> = emptyList(),
    val share: ShareDto,
)
@Serializable data class GroupResponse(val token: String? = null, val group: GroupDto)
@Serializable data class UpdateGroupRequest(val share: ShareDto? = null, val name: String? = null)

fun GroupDto.toDomain() = GroupInfo(id, inviteCode, name, memberCount, otherMembers, share.toDomain())
```
Change `CreateGroupRequest(val displayName: String? = null, val share: ShareDto)`, `JoinRequest(val inviteCode: String, val displayName: String? = null, val share: ShareDto)`, `MeResponse(val displayName: String, val groups: List<GroupDto>)`, `UpdateMeRequest(val displayName: String)`. Remove `JoinResponse`. Extend `errorOf` with the four new codes.

`LeaderboardApi.kt`: update the interface to the Interfaces block; in `HttpLeaderboardApi`, map: `createGroup` → `POST /groups` (token optional), `join` → `POST /join`, `me` → `GET /me`, `updateMe` → `POST /me`, `updateGroup` → `POST /groups/{id}`, `leaveGroup` → `DELETE /groups/{id}`, `sync` → `POST /sync`, `board` → `GET /groups/{id}/board?window=..&date=..`. Encode `groupId` with `java.net.URLEncoder.encode(groupId, "UTF-8")` in paths. The private `call` already takes a nullable token.

- [ ] **Step 3:** Continue directly with Task 6 in the same dispatch (the tree compiles again at the end of Task 6). Run the Task 5 tests there.

---

### Task 6: Identity store, repository, syncer

**Files:**
- Create: `app/src/main/kotlin/com/monolith/app/data/leaderboard/IdentityStore.kt`; delete `MembershipStore.kt`
- Rewrite: `domain/repository/LeaderboardRepository.kt`, `data/repository/LeaderboardRepositoryImpl.kt`
- Modify: `service/LeaderboardSyncer.kt` (observe identity), `di/RepositoryModule.kt`, test fakes (`FakeLeaderboardRepository`, the fakes in `LeaderboardRepositoryImplTest`)
- Test: rewrite `app/src/test/kotlin/com/monolith/app/data/repository/LeaderboardRepositoryImplTest.kt`

**Interfaces:**
```kotlin
interface IdentityStore {
    val identity: Flow<Identity?>
    val selectedGroupId: Flow<String?>
    val removedNotice: Flow<Boolean>
    suspend fun save(identity: Identity)          // clears the removed notice
    suspend fun saveGroups(groups: List<GroupInfo>)
    suspend fun select(groupId: String?)
    suspend fun clear(removed: Boolean = false)
    suspend fun dismissRemovedNotice()
}

interface LeaderboardRepository {
    fun observeIdentity(): Flow<Identity?>
    fun observeSelectedGroupId(): Flow<String?>
    suspend fun selectGroup(groupId: String)
    fun observeRemovedNotice(): Flow<Boolean>
    suspend fun dismissRemovedNotice()
    suspend fun createGroup(displayName: String?, share: ShareSettings): LeaderboardResult<Unit>
    suspend fun joinGroup(inviteCode: String, displayName: String?, share: ShareSettings): LeaderboardResult<Unit>
    suspend fun restore(recoveryCode: String): LeaderboardResult<Unit>
    suspend fun rename(displayName: String): LeaderboardResult<Unit>
    suspend fun refreshGroups(): LeaderboardResult<Unit>
    suspend fun updateGroup(groupId: String, share: ShareSettings? = null, name: String? = null): LeaderboardResult<Unit>
    suspend fun leaveGroup(groupId: String): LeaderboardResult<Unit>
    suspend fun sync(days: List<DayAggregate>, streakStartedAt: Long?): LeaderboardResult<Unit>
    suspend fun board(groupId: String, window: BoardWindow, today: LocalDate): LeaderboardResult<List<BoardRow>>
}
```
Behavior of `LeaderboardRepositoryImpl`:
- `createGroup` / `joinGroup`: send the stored token if any (then `displayName` is omitted); on Ok, if the response carries a token, save a new `Identity(token, displayName.trim(), emptyList())` first; then `refreshGroups()` and `select` the new group's id.
- `restore(code)`: `me(code.trim())`; on Ok save `Identity(token, displayName, groups)` and select the first group.
- `rename`: `updateMe`; save displayName and groups from the response.
- `refreshGroups`: `me(token)`; save groups; if the selected group is gone, select the first group (or null).
- `updateGroup`: send `UpdateGroupRequest(share?.toDto(), name?.let { it.trim() })` (pass `""` to clear); on Ok replace that group in the cached list.
- `leaveGroup`: if it's the only cached group and the call succeeds, `store.clear()` (no notice). Otherwise refresh groups.
- `sync`: filter with `syncRequestOf(days, streak, shareUnion(identity.groups))`. On `HIDDEN_SIGNAL`, `refreshGroups()` then retry once with the refreshed union.
- `board`: on `NOT_MEMBER`, `refreshGroups()` and return the error.
- Every authed call: on `UNAUTHORIZED`, clear with `removed = true` only if the stored token still equals the one used (keep the existing guard).
- `IdentityStore` implementation (`DataStoreIdentityStore`): same DataStore file name `leaderboard_prefs` and corruption handler; keys `token`, `display_name`, `groups` (JSON of `List<GroupDto>` via `LeaderboardJson`), `selected_group`, `removed_notice`. An undecodable `groups` value reads as an empty list. Old single-group keys from the previous version are ignored.
- `LeaderboardSyncer`: replace `observeMembership()` with `observeIdentity()` and gate on `identity != null && identity.groups.isNotEmpty()`.

- [ ] **Step 1: Failing repository tests** (rewrite the test file; fakes implement the new interfaces)

Cover, each as its own `@Test` with `runBlocking`:
1. `create without identity saves the token and selects the new group` (fake api returns `GroupResponse(token = "tok", group = g1)` and `me` returns `MeResponse("Ana", listOf(g1))`; assert store identity token "tok", groups [g1], selected "g1").
2. `create with identity sends the token and no display name` (assert captured request `displayName == null` and token passed).
3. `create while offline saves nothing`.
4. `restore brings every group back` (`me` returns two groups; both cached, first selected).
5. `sync filters by the union of group shares` (groups share saved in one, pauses in another; captured request has savedMs and bypassCount, no streak).
6. `hidden_signal refreshes groups and retries once` (first sync Err HIDDEN_SIGNAL, `me` returns groups with narrower share, second sync Ok; assert 2 sync calls and the retry filtered by the refreshed union).
7. `not_member on a board drops the group` (board Err NOT_MEMBER, `me` returns only the other group; cached groups no longer contain it and selection moves).
8. `leaving the last group clears without a notice` (one cached group, leave Ok; identity null, removedNotice false).
9. `leaving one of two keeps the identity` (leave Ok, `me` returns the remaining group).
10. `401 clears with a notice` and `a late 401 does not clear a newer identity` (existing guard).
11. `updateGroup replaces that group in the cache`.

- [ ] **Step 2: Run to see failures**

Run: `./gradlew testDebugUnitTest --tests 'com.monolith.app.data.repository.LeaderboardRepositoryImplTest'`
Expected: compile errors until implemented.

- [ ] **Step 3: Implement** the store, repository, syncer change and Hilt binding (`bindIdentityStore(impl: DataStoreIdentityStore): IdentityStore` replacing the MembershipStore binding). Update `FakeLeaderboardRepository` and every other compile error the change causes outside `ui/friends/` (leave the UI for Task 7 but make it compile with the smallest possible edits: e.g. temporarily derive the old single `membership` from `identity` and `selectedGroupId` inside `FriendsViewModel`, marked with a comment that Task 7 replaces it).

- [ ] **Step 4: Run all unit tests**

Run: `./gradlew testDebugUnitTest assembleDebug`
Expected: PASS, including GroupsTest, LeaderboardDtosTest, the new repository tests, SyncLeaderboardUseCaseTest, LeaderboardSyncerTest.

- [ ] **Step 5: Commit** (two commits are fine: one for Task 5, one for Task 6)

```bash
git add -A app/src
git commit -m "feat: one leaderboard identity with several groups"
```

---

### Task 7: Group switcher and per-group sheets

**Files:**
- Modify: `ui/friends/FriendsViewModel.kt`, `ui/friends/FriendsScreen.kt`, `ui/friends/GroupSheet.kt`
- Create: `ui/friends/AddGroupSheet.kt`
- Modify: `res/values/strings.xml`
- Test: `app/src/test/kotlin/com/monolith/app/ui/friends/FriendsViewModelTest.kt` (message mapping for the new errors)

**Interfaces:**
- Consumes: Task 6 repository, `groupLabel`, `GroupLabel`, `SettingsGroup`, `SettingsToggleRow`, `SettingsDivider(startInset)`, `TextStyle.mono()`, `copyRecoveryCode` (existing sensitive-clipboard helper in GroupSheet).
- `FriendsUiState` gains `identity: Identity?`, `selectedGroupId: String?`, `sheet: FriendsSheet?` (`enum class FriendsSheet { SETTINGS, ADD }`) and drops `membership`. `FriendsMessage` gains `TOO_MANY_GROUPS`, `ALREADY_MEMBER`, `NAME_NEEDS_THREE` mapped from the matching errors (`NOT_MEMBER` maps to `GENERIC`).

**New strings** (English, `values/strings.xml`):
```xml
<string name="friends_add_group">Add a group</string>
<string name="friends_add_join_heading">Join with a code</string>
<string name="friends_section_group">This group</string>
<string name="friends_section_you">You, in every group</string>
<string name="friends_group_name_label">Group name</string>
<string name="friends_group_name_hint">Groups of two go by the other person\'s name.</string>
<string name="friends_leave_group">Leave this group</string>
<string name="friends_leave_group_confirm_title">Leave this group?</string>
<string name="friends_leave_group_confirm_body">You\'ll stop seeing this board. You can rejoin with the invite code.</string>
<string name="friends_error_too_many">You\'re already in 10 groups.</string>
<string name="friends_error_already_member">You\'re already in that group.</string>
<string name="friends_error_name_needs_three">Only groups of three or more can have a name.</string>
```
Keep `friends_leave`, `friends_leave_confirm_*` for the last-group case (its body says your stats are deleted, which is then true).

**Behavior**
- ViewModel:
  - Collect `observeIdentity()` and `observeSelectedGroupId()`. When the selected group changes (or on first identity), cancel the board job, clear rows, request an immediate sync if the identity just appeared, and refresh the board for the selected group.
  - On opening the screen, call `refreshGroups()` once (names and member counts change as friends join).
  - `selectGroup(id)`, `create(name?, share)`, `join(code, name?, share)` (`name` passed only when there's no identity), `restore(code)`, `rename(name)`, `renameGroup(name)`, `updateShare(share)` (for the selected group), `leaveSelectedGroup()`, `openSheet(sheet)`, `closeSheet()`.
  - After a successful create/join from the ADD sheet, close the sheet.
  - Keep: removed notice handling, board ordering guard, `lastSyncedAt` refresh, ticker, try/finally in `submit`.
- Screen:
  - No identity or no groups: the existing join form, unchanged.
  - With groups: under the top bar, a `LazyRow` (16dp content padding, 8dp spacing) of `FilterChip`s, one per group, label from `groupLabel` (mono when `isCode`), selected chip filled; then an `AssistChip` with `Icons.Outlined.Add` and label `friends_add_group` opening the ADD sheet. Then the existing Day / Week / Month control and board for the selected group.
  - Top-bar action (existing tune icon) opens the SETTINGS sheet.
- ADD sheet (`AddGroupSheet.kt`, `ModalBottomSheet`): the share toggles card (defaults: the currently selected group's share, else all on), a full-width filled "Create a group" button, then a `friends_add_join_heading` label, invite field + outlined Join button. Errors shown inside the sheet (existing message pattern).
- SETTINGS sheet (`GroupSheet.kt`), two sections, each with a `labelLarge` muted header:
  - `friends_section_group`: invite code card (existing); when `memberCount >= 3`, a "Group name" field (prefilled with the name, empty allowed to clear) + Save button; when fewer, the muted hint `friends_group_name_hint` instead; the share toggles card bound to the selected group; the leave button (`friends_leave_group`, error color) with `friends_leave_group_confirm_*`, or the last-group wording (`friends_leave`, `friends_leave_confirm_*`) when it's the only group.
  - `friends_section_you`: display name field + "Save name" (existing), recovery card (existing, collapsed, sensitive clipboard).

- [ ] **Step 1:** Add the new `FriendsMessage` mappings with a failing test in `FriendsViewModelTest` (assert the three new errors map to their messages and `NOT_MEMBER` to `GENERIC`), run it, see it fail.
- [ ] **Step 2:** Implement the ViewModel, sheets and screen per the Behavior list.
- [ ] **Step 3:** Run `./gradlew testDebugUnitTest assembleDebug`. Expected: PASS.
- [ ] **Step 4:** Commit: `feat: switch between several friends groups`.

---

### Task 8: Translations, release, device check (controller)

- [ ] Write the 12 new strings in the six locales with the existing apply script (prunes removed strings, updates `state.json`); commit `feat: translate the multi-group friends copy`.
- [ ] `./gradlew assembleRelease`.
- [ ] Verify the signer matches the installed app, `adb install -r` (never uninstall), confirm `firstInstallTime` unchanged and the accessibility service enabled.
- [ ] With the user: they create a group; the controller scripts two members ("curl" joins it; "lea" creates a second group the user joins via code). Check chip labels (code while alone, other person's name at 2, names list then custom name at 3), per-group share toggles changing only that board, switching boards, leaving one group.
