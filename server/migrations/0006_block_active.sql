-- Whether Monolith is on, shown to every group whatever the member shares: with every stat
-- hidden it is still what a friend wants to know. Null: an older app that never sends it.
ALTER TABLE users ADD COLUMN block_active INTEGER;
