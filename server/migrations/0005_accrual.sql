-- Lets the board keep time gained moving between uploads while a block runs unattended.
-- Null accruing_since: nothing is being credited, or an older app that never sends it.
ALTER TABLE users ADD COLUMN accruing_since INTEGER;
ALTER TABLE users ADD COLUMN utc_offset_min INTEGER;
