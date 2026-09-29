# Friends leaderboard design

## Goal

An optional leaderboard that lets a small circle of friends keep each other accountable: who is
actually holding their blocks, whose streak is running, and who keeps pausing. Friendly
competition is a side effect; accountability is the point.

Success: a few friends join one group, and each sees a board that reflects a finished session
within minutes, entirely on Cloudflare's free tier.

## Decisions

- **Signals:** time saved, current streak, pauses (emergency bypasses and per-app unlocks,
  counted separately). Block attempts are not shared.
- **Per-signal sharing, reciprocal:** each signal can be hidden. Hiding a signal also hides it for
  you on everyone else's rows, including your own row as the board shows it.
- **Groups:** one group per user, joined by invite code. Flat: no admin, no kicking, no code
  rotation. Hard cap of 20 members.
- **Board:** ranked by time saved, with Day / Week / Month tabs. Members hiding time saved are
  listed after the ranked rows, sorted by name, with a dash in place of the value.
- **Sync:** near-live, driven by local data changes plus app open.
- **Identity:** server-issued secret token, shown to the user as a recovery code. No accounts, no
  email.
- **Alerts:** none. Slips are visible when the board is opened.
- **Opt-in:** off by default. No network calls for this feature until the user creates or joins a
  group.
- **Privacy:** only daily aggregates leave the device. Hidden signals are never uploaded. No app
  names, no session timings. Leaving deletes the member's server data.
- **Trust:** the server validates plausible ranges and otherwise trusts the client. Cheating among
  friends is not defended against.
- **Inactive members:** removed after 120 days without a sync.

## Architecture

Approach: daily-bucket upsert. The client uploads its last 35 days of per-day aggregates on each
sync; the server upserts by `(member, date)` and aggregates on read. Idempotent and self-healing:
a failed or duplicated sync is harmless, and the next one fills any gap.

Server code lives in `server/` in this repo (TypeScript Cloudflare Worker, D1). The Android client
talks to it over plain HTTPS with `HttpURLConnection` and kotlinx.serialization, matching
`UpdateRepositoryImpl`.

## Server

### Infrastructure

- D1 database `monolith-leaderboard`, id `73dd5267-38f0-481d-bd7f-60a03e41be46`, binding
  `monolith_leaderboard`.
- Config in `server/wrangler.jsonc`. Workers observability disabled: no per-request logs, no IPs
  retained.
- Served at `https://monolith-leaderboard.<account>.workers.dev`.

### Schema (`server/migrations/0001_init.sql`)

```sql
CREATE TABLE groups (
  id          TEXT PRIMARY KEY,
  invite_code TEXT NOT NULL UNIQUE,
  created_at  INTEGER NOT NULL
);

CREATE TABLE members (
  id                TEXT PRIMARY KEY,
  group_id          TEXT NOT NULL REFERENCES groups(id),
  token_hash        TEXT NOT NULL UNIQUE,
  display_name      TEXT NOT NULL,
  share_saved       INTEGER NOT NULL,
  share_streak      INTEGER NOT NULL,
  share_pauses      INTEGER NOT NULL,
  streak_started_at INTEGER,          -- epoch ms; NULL when not enforcing or hidden
  last_sync_at      INTEGER,          -- epoch ms
  created_at        INTEGER NOT NULL
);
CREATE INDEX members_group ON members(group_id);

CREATE TABLE days (
  member_id    TEXT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  date         TEXT NOT NULL,         -- member's local date, YYYY-MM-DD
  saved_ms     INTEGER,
  bypass_count INTEGER,
  unlock_count INTEGER,
  PRIMARY KEY (member_id, date)
);
```

A membership is the identity. Leaving deletes the member row and its days. A group row is
deleted when its last member leaves or is removed.

Turning a share flag off nulls that signal's stored values for the member (`saved_ms`,
`streak_started_at`, or `bypass_count` + `unlock_count`).

### Endpoints

All bodies are JSON. Every endpoint except `POST /groups` and `POST /join` requires
`Authorization: Bearer <token>`.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/groups` | `{displayName, share}` | `{token, inviteCode}`; creates group and first member |
| POST | `/join` | `{inviteCode, displayName, share}` | `{token, inviteCode}` |
| GET | `/me` | | `{displayName, share, inviteCode}`; also validates a recovery code |
| POST | `/me` | `{displayName?, share?}` | same shape as `GET /me` |
| POST | `/sync` | `{days: [{date, savedMs?, bypassCount?, unlockCount?}], streakStartedAt?}` | 204 |
| GET | `/board?window=day\|week\|month&date=YYYY-MM-DD` | | `{rows: [...]}` |
| DELETE | `/me` | | 204; leaves and deletes the member's data |

`share` is `{saved: bool, streak: bool, pauses: bool}`.

Board row: `{name, isMe, rank?, savedMs?, streak?: {startedAt}, bypassCount?, unlockCount?,
lastSyncAt}`. A field is omitted when either the row's member or the requester hides that signal.
`rank` is omitted for members hiding time saved.

### Board semantics

- `date` is the requester's local today. Day is that date; week is Monday through Sunday
  containing it; month is its calendar month.
- Each member's days are keyed by their own local date, so members in other time zones are offset
  by a few hours at window edges. Accepted.
- Ranked rows are ordered by `SUM(saved_ms)` over the window, descending. Unranked rows follow,
  ordered by name.
- Loading a board first deletes members of that group whose `last_sync_at` (or `created_at` if
  never synced) is more than 120 days old.

### Validation

- `savedMs` in `[0, 86_400_000]`; counts non-negative integers.
- `date` within the last 35 days, up to 1 day ahead of server UTC today.
- At most 40 day entries per sync.
- `displayName` 1 to 24 characters after trimming.
- Group size at most 20.
- A field for a signal the member hides is rejected with `hidden_signal`.

### Behavior details

- Group creation inserts group and member in one `db.batch`. On invite code collision, regenerate,
  up to 3 attempts.
- Join counts then inserts; a simultaneous join race may yield 21 members. Accepted.
- `/sync` upserts day rows, updates `streak_started_at` and `last_sync_at`, and deletes the
  member's days older than 35 days, all in one batch. No cron.
- Invite codes: 8 characters of Crockford base32. Tokens: 32 random bytes, base64url. Only the
  SHA-256 of the token is stored.

### Errors

All errors are `{"error": "<code>"}`.

| HTTP | Code | Client reaction |
|---|---|---|
| 400 | `invalid_body` | treated as a bug; logged locally, sync dropped |
| 401 | `unauthorized` | clear local group state; show "You're no longer in a group" |
| 404 | `invite_not_found` | inline error on the join form |
| 409 | `group_full` | inline error on the join form |
| 422 | `hidden_signal` | re-send share flags via `POST /me`, retry once |

### Layout

```
server/
  package.json             # devDependencies only: wrangler, vitest, @cloudflare/vitest-pool-workers
  wrangler.jsonc
  migrations/0001_init.sql
  src/index.ts             # fetch handler and router, no framework
  src/auth.ts              # bearer token -> SHA-256 -> member
  src/validate.ts          # body parsing and limits
  src/board.ts             # window ranges, ranking, reciprocity filter, inactive cleanup
  src/codes.ts             # invite code and token generation
  test/*.test.ts
```

## Android client

### Pause log (only change to enforcement code)

New DataStore key `PAUSE_LOG`: a JSON list of `{type: BYPASS | UNLOCK, at}`, retained 35 days.
Appended inside the existing `startBypass` and `grantAppUnlock` edit transactions in
`MonolithPreferences`, so a pause and its log entry are written atomically.

### Data layer

- `LeaderboardApi`: thin HTTP wrapper over the endpoints. Base URL from
  `BuildConfig.LEADERBOARD_URL`.
- `LeaderboardRepository` (domain interface) and implementation: holds token, share flags,
  display name and invite code in DataStore (app-private storage, same protection as the tag
  UID).
- `DailyAggregates`: pure function `(sessions, pauseLog, zone, now) -> 35 day rows`. Reuses
  `TimeSavedCalculator`'s day-overlap logic so the board never disagrees with the time saved
  screen. Includes the ongoing session segments.
- Hidden signals are stripped from the payload before it reaches the network layer.
- `streakStartedAt` is the start of the current enforcing segment, or null when Monolith is off
  or a pause is running.

### Sync

`LeaderboardSyncer`: app-scoped singleton started from `MonolithApplication`, idle unless joined.
When joined, it observes block sessions, the pause log and block state, and syncs after 5 seconds
of quiet. It also syncs on app open. No retry queue and no WorkManager: a failed sync is dropped
and the next trigger re-sends all 35 days.

### UI

Entry point: a group icon button (`Icons.Outlined.Group`) in the home header, left of the
settings button, opening a new `Friends` destination. Nothing in Settings.

- **Not joined:** create a group, or join with an invite code. Display name field and the three
  share toggles, with one line explaining reciprocity.
- **Board:** Day / Week / Month control reused from the time saved screen. Rows show rank, name,
  time saved, live-ticking streak (computed from `streakStartedAt`), bypass and unlock counts,
  and sync age ("synced 3h ago"). Hidden values show as a dash. Offline: last board fetched this
  session with an "Offline" hint, or an empty state with retry.
- **Group sheet:** invite code with copy, rename, share toggles, recovery code (show, copy,
  restore), and leave with a confirmation that it deletes your data. Restore warns: "Only use
  this on one phone."
- Visual direction: smooth brutalist. Inter, mono only for numbers, near-monochrome, no all-caps.

## Deployment

1. `npx wrangler login` (done).
2. `npx wrangler d1 create monolith-leaderboard` (done).
3. `npx wrangler d1 migrations apply monolith-leaderboard --remote`.
4. `npx wrangler deploy`.
5. Set `buildConfigField("String", "LEADERBOARD_URL", ...)` in `app/build.gradle.kts`.

Manual deploys, no CI.

## Testing

- **Server:** vitest with `@cloudflare/vitest-pool-workers` against local D1. Create and join,
  member cap, idempotent sync, reciprocity filtering, week and month boundaries, 401 after leave,
  inactive cleanup at 120 days, share flag off nulls stored data.
- **Client:** unit tests for `DailyAggregates` (day boundaries, bypass carve-out, time zones,
  ongoing session), payload filtering by share flags, pause log retention.
- **End to end:** release build (`./gradlew assembleRelease`) against the deployed Worker, with two
  phones or one phone plus `curl` acting as a second member.

## Out of scope

Rate limiting, invite code rotation, admin roles, multiple groups, notifications, block attempt
sharing, history beyond 35 days, custom domain, CI deploys.

## Planning amendments

- `POST /me` replaces `PATCH /me`: `HttpURLConnection` cannot send `PATCH`.
- A board row carries `streak: {startedAt}` when the streak is visible (`startedAt` null when not
  enforcing) and omits it when hidden.
- A viewer hiding time gained sees no ranks; rows are ordered by name.
- Sync accepts dates from UTC today minus 36 days, and the pause log keeps 36 days, so zones
  behind UTC can send all 35 local days.
- Numbers use the app's tabular Inter style; the app ships no mono font.
