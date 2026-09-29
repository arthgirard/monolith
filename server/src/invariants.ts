/**
 * Statements that restore the spec's invariants after memberships changed. Append them to the
 * same batch as the change so both commit together.
 *   1. A group with no members is deleted.
 *   2. A group with fewer than 3 members has no name.
 *   3. A signal none of a user's memberships share is not stored.
 *   4. A user with no memberships and no backup is deleted, with their days.
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
      db
        .prepare(
          `DELETE FROM users WHERE id = ?1
             AND NOT EXISTS (SELECT 1 FROM memberships WHERE user_id = ?1)
             AND NOT EXISTS (SELECT 1 FROM backups WHERE user_id = ?1)`,
        )
        .bind(u),
    );
  }
  return statements;
}
