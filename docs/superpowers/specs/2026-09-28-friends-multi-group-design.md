# Friends leaderboard: multiple groups

Amends `2026-09-28-friends-leaderboard-design.md`. Everything in that spec stands unless this
document changes it.

## Why

With one group per user, someone who only wants to compare with one friend has to join that
friend's whole group, including people they don't know. Users can now belong to several groups,
each a separate circle with its own board and its own sharing choices.

## Decisions

- **Several groups per user**, each with its own board. A chip row switches between them.
- **Sharing is per group.** Reciprocity is per group: a field shows on a row only when both the
  viewer's membership and that member's membership in the same group share it.
- **Group names depend on size.** With 2 members there is no shared name; each member sees the
  group under the other person's name. With 3 or more, the group has a shared name any member can
  set or clear (flat, no admin). Until one is set, the label is the other members' names joined
  with ", ". When a group drops to 2 members, its name is cleared.
- **One identity.** A user is one server identity with one token (the recovery code) and one
  display name used in every group. One sync upload feeds every group.
- **Upload rule.** A signal leaves the phone only if at least one of the user's groups shares it.
  The server stores a signal only while at least one membership shares it, and nulls it the
  moment none does.
- **Limits.** At most 10 groups per user; at most 20 members per group.
- **Leaving** removes one membership. Leaving the last group deletes the user and their days.
- **Existing data** on the deployed Worker is test data from an unreleased branch; the new
  migration resets the schema instead of migrating it.
- Unchanged: near-live sync, 120-day inactive cleanup, Day / Week / Month board, the 35-day
  window, validation limits, token hashing, invite code format, no request logging, the
  decluttered visual design.

## Server

### Schema (`server/migrations/0002_multi_group.sql`)

Drops `days`, `members`, `groups` and creates:

```sql
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

### Invariants, enforced after every membership change (create, join, share update, leave, cleanup)

1. A group with 0 memberships is deleted.
2. A group with fewer than 3 memberships has `name = NULL`.
3. For each user, a signal no membership shares is nulled in storage: `saved_ms` if no membership
   shares time gained, `streak_started_at` if none shares the streak, `bypass_count` and
   `unlock_count` if none shares pauses.
4. A user with 0 memberships is deleted, with their days.

### Endpoints

All bodies JSON. `Authorization: Bearer <token>` is required everywhere except that it is
optional on `POST /groups` and `POST /join`.

| Method | Path | Body | Response |
|---|---|---|---|
| POST | `/groups` | `{displayName?, share}` | no token: creates user, group, membership; `201 {token, group}`. With token: `201 {group}`. `displayName` required without a token, ignored with one. |
| POST | `/join` | `{inviteCode, displayName?, share}` | same token behavior; `201 {token?, group}` |
| GET | `/me` | | `{displayName, groups: [group]}` ordered by `joined_at` |
| POST | `/me` | `{displayName}` | `{displayName, groups}` |
| POST | `/groups/:id` | `{share?, name?}` | `{group}` |
| DELETE | `/groups/:id` | | 204 |
| POST | `/sync` | `{days: [...], streakStartedAt?}` | 204 |
| GET | `/groups/:id/board?window=day\|week\|month&date=YYYY-MM-DD` | | `{rows}` |

`group` = `{id, inviteCode, name: string | null, memberCount, otherMembers: [displayName], share}`,
where `share` is the caller's flags in that group and `otherMembers` excludes the caller.

Board rows keep the current shape (`name, isMe, rank?, savedMs?, streak?: {startedAt},
bypassCount?, unlockCount?, lastSyncAt`), with reciprocity and ranking rules computed from the
two memberships in that group.

### Validation and errors

- `displayName` 1 to 24 characters after trim; group `name` 1 to 32 after trim; `name: ""` or
  `null` clears it.
- Setting a name on a group with fewer than 3 members: `409 name_needs_three`.
- Creating or joining with 10 memberships already: `409 too_many_groups`.
- Joining a group you are in: `409 already_member`.
- Joining a full group: `409 group_full`. Unknown code: `404 invite_not_found`.
- Board or update or leave for a group you are not in (or that doesn't exist): `404 not_member`.
- Sync: a non-null value for a signal that none of your memberships share: `422 hidden_signal`.
- Other limits unchanged from the original spec (days window, 40 days per sync, value ranges,
  future streak start clamped to now).

### Inactive cleanup

When any board loads, users in that group (other than the viewer) whose
`COALESCE(last_sync_at, created_at)` is older than 120 days are deleted entirely, which removes
them from every group; the invariants then run for the affected groups.

## Android

### Storage

`IdentityStore` (own DataStore file, replaces `MembershipStore`, corruption handler kept) holds the
token, display name, the cached `GET /me` group list (JSON), the selected group id, and the
removed-notice flag. The cache is refreshed when Friends opens and after every mutating call.

### Repository

```
observeIdentity(): Flow<Identity?>              // token, displayName, groups
createGroup(displayName: String?, share): Result
joinGroup(inviteCode, displayName: String?, share): Result
restore(recoveryCode): Result
rename(displayName): Result
refreshGroups(): Result
updateGroup(groupId, share?, name?): Result
leaveGroup(groupId): Result
sync(days, streakStartedAt): Result
board(groupId, window, today): Result<List<BoardRow>>
```

- The sync payload is filtered by `shareUnion(groups)` (pure): a signal is sent only if any
  group shares it.
- 401 clears the identity and sets the removed notice (unchanged behavior).
- 404 `not_member` on a board or update drops that group from the cache and selects another.
- `hidden_signal` on sync refreshes groups from the server and retries once.

### UI

- **No identity or no groups:** the current join/create form, unchanged.
- **With groups:** under the top bar, a horizontally scrolling chip row: one chip per group, the
  selected one filled, then a `+` chip. Selection persists. Below it, the Day / Week / Month
  control and the board for the selected group, as today.
- **Chip label** comes from pure `groupLabel(group)`: 2 members, the other member's name; 1
  member (just created), the group's invite code in mono; 3 or more, the name if set, else
  `otherMembers` joined with ", ".
- **`+` sheet:** "Create a group" and "Join with a code". Share toggles default to the last
  choices used.
- **Settings sheet (top-bar icon)**, two headed sections:
  - *This group:* invite code card; name field + save, shown only at 3 or more members;
    this group's share card; "Leave this group" in the error color with a confirmation.
  - *You (all groups):* display name + save; recovery code card (collapsed, sensitive clipboard).
- Leaving the last group returns to the join form.

## Testing

- Server: vitest for every route, the four invariants, per-group reciprocity (same two users,
  different flags in two groups), `hidden_signal` on the union, naming rules, limits, cleanup
  across groups, and migration applying on a fresh database.
- Android: unit tests for `shareUnion`, `groupLabel`, the sync filter, and repository behavior
  (401 clears, `not_member` drops the group, last leave clears identity, restore brings every
  group back, `hidden_signal` retry).
- End to end: release build installed in place on the user's phone; two groups, with "curl" and
  a second scripted member, checking labels, per-group sharing and switching.
