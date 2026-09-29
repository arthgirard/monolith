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
