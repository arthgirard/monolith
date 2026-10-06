import { addDays, utcToday } from "./dates";
import { empty, readJson } from "./http";
import { RETENTION_DAYS, type Env, type Share, type UserRow } from "./types";
import { parseSync } from "./validate";

/** What at least one of the user's groups may see. Nothing else is accepted or stored. */
async function unionShare(db: D1Database, userId: string): Promise<Share> {
  const row = await db
    .prepare(
      `SELECT MAX(share_saved) AS saved, MAX(share_streak) AS streak, MAX(share_pauses) AS pauses, MAX(share_apps) AS apps
       FROM memberships WHERE user_id = ?1`,
    )
    .bind(userId)
    .first<{ saved: number | null; streak: number | null; pauses: number | null; apps: number | null }>();
  return { saved: row?.saved === 1, streak: row?.streak === 1, pauses: row?.pauses === 1, apps: row?.apps === 1 };
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
    db
      .prepare(
        `UPDATE users SET streak_started_at = ?2, last_sync_at = ?3, accruing_since = ?4,
           utc_offset_min = COALESCE(?5, utc_offset_min), block_active = ?6 WHERE id = ?1`,
      )
      .bind(user.id, body.streakStartedAt, now, body.accruingSince, body.utcOffsetMinutes, body.active === null ? null : body.active ? 1 : 0),
    // Same edge as parseSync's earliest accepted date, so nothing accepted is pruned right away.
    db.prepare("DELETE FROM days WHERE user_id = ?1 AND date < ?2").bind(user.id, addDays(utcToday(now), -(RETENTION_DAYS + 1))),
  );
  if (body.apps !== undefined) {
    statements.push(
      db.prepare("UPDATE users SET blocked_apps = ?2 WHERE id = ?1").bind(user.id, body.apps === null ? null : JSON.stringify(body.apps)),
    );
  }
  await db.batch(statements);
  return empty();
}
