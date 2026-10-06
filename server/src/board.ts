import { addDays, isIsoDate, windowRange, type BoardWindow } from "./dates";
import { HttpError, json } from "./http";
import { invariantStatements } from "./invariants";
import { DAY_MS, INACTIVE_DAYS, shareOf, type Env, type Share, type ShareColumns, type UserRow } from "./types";
import type { BlockedApp } from "./validate";

export interface BoardQueryRow {
  id: string;
  display_name: string;
  share_saved: number;
  share_streak: number;
  share_pauses: number;
  share_apps: number;
  blocked_apps: string | null;
  streak_started_at: number | null;
  last_sync_at: number | null;
  accruing_since: number | null;
  utc_offset_min: number | null;
  /** 1 only while Monolith is on and the last upload is recent enough to still vouch for it. */
  active: number;
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
  apps?: BlockedApp[];
  lastSyncAt: number | null;
  active: boolean;
}

/** How long after its last upload a phone is still trusted to be blocking: past this it may be dead. */
export const PROJECTION_MAX_MS = 48 * 60 * 60 * 1000;

const MINUTE_MS = 60 * 1000;

/**
 * Time gained stops moving between uploads, yet a phone left alone is the goal. Every way to stop
 * crediting (a pause, turning Monolith off) uploads, so until the next upload the member keeps
 * gaining in real time from [accruing_since]. That can sit in the future: the end of a pause,
 * which may run out while the phone is asleep and can't say so. The same moment restarts the streak.
 */
export function project(rows: BoardQueryRow[], from: string, to: string, now: number): BoardQueryRow[] {
  return rows.map((r) => {
    if (r.accruing_since === null || r.last_sync_at === null) return r;
    const until = Math.min(now, r.last_sync_at + PROJECTION_MAX_MS);
    if (r.accruing_since > until) return r;
    // The window's dates are the member's local days.
    const offset = (r.utc_offset_min ?? 0) * MINUTE_MS;
    const windowStart = Date.parse(`${from}T00:00:00Z`) - offset;
    const windowEnd = Date.parse(`${addDays(to, 1)}T00:00:00Z`) - offset;
    const gained = Math.max(0, Math.min(until, windowEnd) - Math.max(r.accruing_since, windowStart));
    return {
      ...r,
      saved: gained > 0 ? (r.saved ?? 0) + gained : r.saved,
      streak_started_at: r.streak_started_at ?? r.accruing_since,
    };
  });
}

/**
 * A signal shows only when both the row's member and the viewer share it. Ranking needs time
 * gained, so a viewer hiding it gets no ranks at all: ordering by numbers they cannot see would
 * leak them.
 */
export function rankRows(rows: BoardQueryRow[], meId: string, viewer: Share): BoardRow[] {
  const out = rows.map((r): BoardRow => {
    // Not a shared signal: every group sees it, so a member hiding every stat still shows up as on.
    const row: BoardRow = { name: r.display_name, isMe: r.id === meId, lastSyncAt: r.last_sync_at, active: r.active === 1 };
    if (viewer.saved && r.share_saved === 1) row.savedMs = r.saved ?? 0;
    if (viewer.streak && r.share_streak === 1) row.streak = { startedAt: r.streak_started_at };
    if (viewer.pauses && r.share_pauses === 1) {
      row.bypassCount = r.bypass ?? 0;
      row.unlockCount = r.unlock ?? 0;
    }
    // Shared but not uploaded yet (an older app, or no sync since) reads as an empty list.
    if (viewer.apps && r.share_apps === 1) row.apps = r.blocked_apps ? (JSON.parse(r.blocked_apps) as BlockedApp[]) : [];
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

/**
 * Lazy cleanup instead of a cron: a flat group has no admin, so this is what frees seats.
 * A stale user is deleted outright, which removes them from every group they were in.
 */
async function removeStaleMembers(db: D1Database, groupId: string, viewerId: string, now: number) {
  const staleBefore = now - INACTIVE_DAYS * DAY_MS;
  const { results: stale } = await db
    .prepare(
      `SELECT u.id FROM memberships m JOIN users u ON u.id = m.user_id
       LEFT JOIN backups b ON b.user_id = u.id
       WHERE m.group_id = ?1 AND u.id != ?2
         AND MAX(COALESCE(u.last_sync_at, u.created_at), COALESCE(b.updated_at, 0)) < ?3`,
    )
    .bind(groupId, viewerId, staleBefore)
    .all<{ id: string }>();
  if (stale.length === 0) return;
  const userIds = stale.map((s) => s.id);
  const placeholders = userIds.map((_, i) => `?${i + 1}`).join(", ");
  const { results: touched } = await db
    .prepare(`SELECT DISTINCT group_id FROM memberships WHERE user_id IN (${placeholders})`)
    .bind(...userIds)
    .all<{ group_id: string }>();
  await db.batch([
    db.prepare(`DELETE FROM backups WHERE user_id IN (${placeholders})`).bind(...userIds),
    db.prepare(`DELETE FROM memberships WHERE user_id IN (${placeholders})`).bind(...userIds),
    ...invariantStatements(db, userIds, touched.map((t) => t.group_id)),
  ]);
}

export async function board(req: Request, env: Env, user: UserRow, now: number, groupId: string): Promise<Response> {
  const db = env.monolith_leaderboard;
  const viewer = await db
    .prepare("SELECT share_saved, share_streak, share_pauses, share_apps FROM memberships WHERE user_id = ?1 AND group_id = ?2")
    .bind(user.id, groupId)
    .first<ShareColumns>();
  if (!viewer) throw new HttpError(404, "not_member");

  const params = new URL(req.url).searchParams;
  const window = params.get("window");
  const date = params.get("date");
  if ((window !== "day" && window !== "week" && window !== "month") || !isIsoDate(date)) {
    throw new HttpError(400, "invalid_body");
  }
  const { from, to } = windowRange(window as BoardWindow, date);

  await removeStaleMembers(db, groupId, user.id, now);

  const { results } = await db
    .prepare(
      `SELECT u.id, u.display_name, m.share_saved, m.share_streak, m.share_pauses, m.share_apps, u.blocked_apps,
              u.streak_started_at, u.last_sync_at, u.accruing_since, u.utc_offset_min,
              CASE WHEN u.block_active = 1 AND u.last_sync_at >= ?4 THEN 1 ELSE 0 END AS active,
              SUM(d.saved_ms) AS saved, SUM(d.bypass_count) AS bypass, SUM(d.unlock_count) AS unlock
       FROM memberships m
       JOIN users u ON u.id = m.user_id
       LEFT JOIN days d ON d.user_id = u.id AND d.date BETWEEN ?2 AND ?3
       WHERE m.group_id = ?1
       GROUP BY u.id`,
    )
    // A phone silent past the projection limit may be dead: it no longer vouches for being on.
    .bind(groupId, from, to, now - PROJECTION_MAX_MS)
    .all<BoardQueryRow>();

  return json({ rows: rankRows(project(results, from, to, now), user.id, shareOf(viewer)) });
}
