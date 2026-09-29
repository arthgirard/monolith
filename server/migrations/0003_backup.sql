CREATE TABLE backups (
  user_id    TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  blob       BLOB NOT NULL,
  updated_at INTEGER NOT NULL
);
