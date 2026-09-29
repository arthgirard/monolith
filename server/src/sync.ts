import { addDays, utcToday } from "./dates";
import { empty, readJson } from "./http";
import { RETENTION_DAYS, shareOf, type Env, type UserRow } from "./types";
import { parseSync } from "./validate";

export async function sync(req: Request, env: Env, member: UserRow, now: number): Promise<Response> {
  const body = parseSync(await readJson(req), shareOf(member as never), now);
  const db = env.monolith_leaderboard;
  const statements = body.days.map((d) =>
    db
      .prepare(
        `INSERT INTO days (member_id, date, saved_ms, bypass_count, unlock_count) VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT (member_id, date) DO UPDATE SET
           saved_ms = excluded.saved_ms, bypass_count = excluded.bypass_count, unlock_count = excluded.unlock_count`,
      )
      .bind(member.id, d.date, d.savedMs, d.bypassCount, d.unlockCount),
  );
  statements.push(
    db.prepare("UPDATE members SET streak_started_at = ?2, last_sync_at = ?3 WHERE id = ?1").bind(member.id, body.streakStartedAt, now),
    // Same edge as parseSync's earliest accepted date, so nothing accepted is pruned right away.
    db.prepare("DELETE FROM days WHERE member_id = ?1 AND date < ?2").bind(member.id, addDays(utcToday(now), -(RETENTION_DAYS + 1))),
  );
  await db.batch(statements);
  return empty();
}
