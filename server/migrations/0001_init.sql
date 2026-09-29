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
  streak_started_at INTEGER,
  last_sync_at      INTEGER,
  created_at        INTEGER NOT NULL
);
CREATE INDEX members_group ON members(group_id);

CREATE TABLE days (
  member_id    TEXT NOT NULL REFERENCES members(id) ON DELETE CASCADE,
  date         TEXT NOT NULL,
  saved_ms     INTEGER,
  bypass_count INTEGER,
  unlock_count INTEGER,
  PRIMARY KEY (member_id, date)
);
