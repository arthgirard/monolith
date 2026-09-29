-- The blocked-apps signal. Existing memberships start hidden: nobody agreed to share it yet.
ALTER TABLE memberships ADD COLUMN share_apps INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN blocked_apps TEXT;
