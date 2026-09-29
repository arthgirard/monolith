import { isIsoDate, windowRange, type BoardWindow } from "./dates";
import { HttpError, json } from "./http";
import { DAY_MS, INACTIVE_DAYS, shareOf, type Env, type MemberRow, type Share } from "./types";

export interface BoardQueryRow {
  id: string;
  display_name: string;
  share_saved: number;
  share_streak: number;
  share_pauses: number;
  streak_started_at: number | null;
  last_sync_at: number | null;
  saved: number | null;
  bypass: number | null;
  unlock: number | null;
}

export interface BoardRow {
  name: string;
  isMe: boolean;
  rank?: number;
  savedMs?: number;
  streak?: { startedAt: number | null };
  bypassCount?: number;
  unlockCount?: number;
  lastSyncAt: number | null;
}

/**
 * A signal shows only when both the row's member and the viewer share it. Ranking needs time
 * gained, so a viewer hiding it gets no ranks at all: ordering by numbers they cannot see would
 * leak them.
 */
export function rankRows(rows: BoardQueryRow[], meId: string, viewer: Share): BoardRow[] {
  const out = rows.map((r): BoardRow => {
    const row: BoardRow = { name: r.display_name, isMe: r.id === meId, lastSyncAt: r.last_sync_at };
    if (viewer.saved && r.share_saved === 1) row.savedMs = r.saved ?? 0;
    if (viewer.streak && r.share_streak === 1) row.streak = { startedAt: r.streak_started_at };
    if (viewer.pauses && r.share_pauses === 1) {
      row.bypassCount = r.bypass ?? 0;
      row.unlockCount = r.unlock ?? 0;
    }
    return row;
  });
  const byName = (a: BoardRow, b: BoardRow) => a.name.localeCompare(b.name);
  const ranked = out.filter((r) => r.savedMs !== undefined).sort((a, b) => b.savedMs! - a.savedMs! || byName(a, b));
  ranked.forEach((r, i) => {
    r.rank = i > 0 && ranked[i - 1].savedMs === r.savedMs ? ranked[i - 1].rank : i + 1;
  });
  const unranked = out.filter((r) => r.savedMs === undefined).sort(byName);
  return [...ranked, ...unranked];
}

export async function board(req: Request, env: Env, member: MemberRow, now: number): Promise<Response> {
  const params = new URL(req.url).searchParams;
  const window = params.get("window");
  const date = params.get("date");
  if ((window !== "day" && window !== "week" && window !== "month") || !isIsoDate(date)) {
    throw new HttpError(400, "invalid_body");
  }
  const { from, to } = windowRange(window as BoardWindow, date);
  const db = env.monolith_leaderboard;
  const staleBefore = now - INACTIVE_DAYS * DAY_MS;

  // Lazy cleanup instead of a cron: a flat group has no admin, so this is what frees seats.
  // The viewer is excluded; they are clearly not inactive.
  const stale = "SELECT id FROM members WHERE group_id = ?1 AND id != ?2 AND COALESCE(last_sync_at, created_at) < ?3";
  await db.batch([
    db.prepare(`DELETE FROM days WHERE member_id IN (${stale})`).bind(member.group_id, member.id, staleBefore),
    db.prepare(`DELETE FROM members WHERE id IN (${stale})`).bind(member.group_id, member.id, staleBefore),
  ]);

  const { results } = await db
    .prepare(
      `SELECT m.id, m.display_name, m.share_saved, m.share_streak, m.share_pauses, m.streak_started_at, m.last_sync_at,
              SUM(d.saved_ms) AS saved, SUM(d.bypass_count) AS bypass, SUM(d.unlock_count) AS unlock
       FROM members m
       LEFT JOIN days d ON d.member_id = m.id AND d.date BETWEEN ?2 AND ?3
       WHERE m.group_id = ?1
       GROUP BY m.id`,
    )
    .bind(member.group_id, from, to)
    .all<BoardQueryRow>();

  return json({ rows: rankRows(results, member.id, shareOf(member)) });
}
