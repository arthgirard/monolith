import { empty, HttpError } from "./http";
import { invariantStatements } from "./invariants";
import { DAY_MS, INACTIVE_DAYS, type Env, type UserRow } from "./types";

const MAX_BACKUP_BYTES = 1_048_576;

/** Backup-only users are never on a board, so their inactivity is swept here instead. */
async function removeStaleBackupOnlyUsers(db: D1Database, now: number) {
  const staleBefore = now - INACTIVE_DAYS * DAY_MS;
  const { results } = await db
    .prepare(
      `SELECT u.id FROM users u LEFT JOIN backups b ON b.user_id = u.id
       WHERE NOT EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = u.id)
         AND MAX(COALESCE(u.last_sync_at, u.created_at), COALESCE(b.updated_at, 0)) < ?1
       LIMIT 50`,
    )
    .bind(staleBefore)
    .all<{ id: string }>();
  if (results.length === 0) return;
  await db.batch(results.map((r) => db.prepare("DELETE FROM users WHERE id = ?1").bind(r.id)));
}

export async function putBackup(req: Request, env: Env, user: UserRow, now: number): Promise<Response> {
  const bytes = new Uint8Array(await req.arrayBuffer());
  if (bytes.byteLength === 0) throw new HttpError(400, "invalid_body");
  if (bytes.byteLength > MAX_BACKUP_BYTES) throw new HttpError(413, "too_large");
  const db = env.monolith_leaderboard;
  await db
    .prepare(
      `INSERT INTO backups (user_id, blob, updated_at) VALUES (?1, ?2, ?3)
       ON CONFLICT (user_id) DO UPDATE SET blob = excluded.blob, updated_at = excluded.updated_at`,
    )
    .bind(user.id, bytes, now)
    .run();
  await removeStaleBackupOnlyUsers(db, now);
  return empty();
}

export async function getBackup(_req: Request, env: Env, user: UserRow): Promise<Response> {
  const row = await env.monolith_leaderboard
    .prepare("SELECT blob FROM backups WHERE user_id = ?1")
    .bind(user.id)
    .first<{ blob: ArrayBuffer | number[] }>();
  if (!row) throw new HttpError(404, "no_backup");
  const bytes = row.blob instanceof ArrayBuffer ? new Uint8Array(row.blob) : Uint8Array.from(row.blob);
  return new Response(bytes, { headers: { "content-type": "application/octet-stream" } });
}

export async function deleteBackup(_req: Request, env: Env, user: UserRow): Promise<Response> {
  const db = env.monolith_leaderboard;
  await db.batch([
    db.prepare("DELETE FROM backups WHERE user_id = ?1").bind(user.id),
    ...invariantStatements(db, [user.id], []),
  ]);
  return empty();
}
