import { newInviteCode, newToken, normalizeInviteCode, sha256Hex } from "./codes";
import { empty, HttpError, json, readJson } from "./http";
import { MAX_MEMBERS, shareOf, type Env, type MemberRow, type Share } from "./types";
import { parseDisplayName, parseShare } from "./validate";

export function meBody(displayName: string, share: Share, inviteCode: string) {
  return { displayName, share, inviteCode };
}

function insertMember(db: D1Database, groupId: string, name: string, share: Share, tokenHash: string, now: number) {
  return db
    .prepare(
      `INSERT INTO members (id, group_id, token_hash, display_name, share_saved, share_streak, share_pauses, created_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)`,
    )
    .bind(crypto.randomUUID(), groupId, tokenHash, name, +share.saved, +share.streak, +share.pauses, now);
}

export async function createGroup(req: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(req);
  const name = parseDisplayName(body.displayName);
  const share = parseShare(body.share);
  const db = env.monolith_leaderboard;
  // An invite code collision is astronomically unlikely, but it is a UNIQUE violation, not a crash.
  for (let attempt = 0; attempt < 3; attempt++) {
    const groupId = crypto.randomUUID();
    const inviteCode = newInviteCode();
    const token = newToken();
    try {
      await db.batch([
        db.prepare("INSERT INTO groups (id, invite_code, created_at) VALUES (?1, ?2, ?3)").bind(groupId, inviteCode, now),
        insertMember(db, groupId, name, share, await sha256Hex(token), now),
      ]);
      return json({ token, inviteCode }, 201);
    } catch (e) {
      if (!String(e).includes("UNIQUE")) throw e;
    }
  }
  throw new HttpError(500, "internal");
}

export async function joinGroup(req: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(req);
  const name = parseDisplayName(body.displayName);
  const share = parseShare(body.share);
  if (typeof body.inviteCode !== "string") throw new HttpError(400, "invalid_body");
  const code = normalizeInviteCode(body.inviteCode);
  if (!code) throw new HttpError(404, "invite_not_found");

  const db = env.monolith_leaderboard;
  const group = await db.prepare("SELECT id, invite_code FROM groups WHERE invite_code = ?1").bind(code).first<{
    id: string;
    invite_code: string;
  }>();
  if (!group) throw new HttpError(404, "invite_not_found");
  const count = await db.prepare("SELECT COUNT(*) AS n FROM members WHERE group_id = ?1").bind(group.id).first<{ n: number }>();
  // Count then insert: two joins racing for the last seat can make it 21. Accepted for friends.
  if ((count?.n ?? 0) >= MAX_MEMBERS) throw new HttpError(409, "group_full");

  const token = newToken();
  await insertMember(db, group.id, name, share, await sha256Hex(token), now).run();
  return json({ token, inviteCode: group.invite_code }, 201);
}

export async function getMe(_req: Request, _env: Env, member: MemberRow): Promise<Response> {
  return json(meBody(member.display_name, shareOf(member), member.invite_code));
}

export async function leave(_req: Request, env: Env, member: MemberRow): Promise<Response> {
  const db = env.monolith_leaderboard;
  await db.batch([
    db.prepare("DELETE FROM days WHERE member_id = ?1").bind(member.id),
    db.prepare("DELETE FROM members WHERE id = ?1").bind(member.id),
    db.prepare("DELETE FROM groups WHERE id = ?1 AND NOT EXISTS (SELECT 1 FROM members WHERE group_id = ?1)").bind(member.group_id),
  ]);
  return empty();
}

export async function updateMe(req: Request, env: Env, member: MemberRow): Promise<Response> {
  const body = await readJson(req);
  const name = body.displayName === undefined ? member.display_name : parseDisplayName(body.displayName);
  const share = body.share === undefined ? shareOf(member) : parseShare(body.share);
  const db = env.monolith_leaderboard;
  const statements = [
    db
      .prepare("UPDATE members SET display_name = ?2, share_saved = ?3, share_streak = ?4, share_pauses = ?5 WHERE id = ?1")
      .bind(member.id, name, +share.saved, +share.streak, +share.pauses),
  ];
  // Hiding a signal deletes it: the server never holds what the member chose not to share.
  if (!share.saved) statements.push(db.prepare("UPDATE days SET saved_ms = NULL WHERE member_id = ?1").bind(member.id));
  if (!share.streak) statements.push(db.prepare("UPDATE members SET streak_started_at = NULL WHERE id = ?1").bind(member.id));
  if (!share.pauses) {
    statements.push(db.prepare("UPDATE days SET bypass_count = NULL, unlock_count = NULL WHERE member_id = ?1").bind(member.id));
  }
  await db.batch(statements);
  return json(meBody(name, share, member.invite_code));
}
