import { authenticate } from "./auth";
import { newInviteCode, normalizeInviteCode, sha256Hex } from "./codes";
import { empty, HttpError, json, readJson } from "./http";
import { MAX_GROUPS, MAX_MEMBERS, shareOf, type Env, type Share, type ShareColumns, type UserRow } from "./types";
import { invariantStatements } from "./invariants";
import { parseDisplayName, parseGroupName, parseShare, parseToken } from "./validate";

export interface GroupInfo {
  id: string;
  inviteCode: string;
  name: string | null;
  memberCount: number;
  otherMembers: string[];
  share: Share;
}

interface GroupInfoRow extends ShareColumns {
  id: string;
  invite_code: string;
  name: string | null;
  member_count: number;
  others: string;
}

export async function groupInfos(db: D1Database, userId: string): Promise<GroupInfo[]> {
  const { results } = await db
    .prepare(
      `SELECT g.id, g.invite_code, g.name, m.share_saved, m.share_streak, m.share_pauses,
              (SELECT COUNT(*) FROM memberships c WHERE c.group_id = g.id) AS member_count,
              (SELECT json_group_array(u.display_name) FROM memberships o JOIN users u ON u.id = o.user_id
                 WHERE o.group_id = g.id AND o.user_id != ?1) AS others
       FROM memberships m JOIN groups g ON g.id = m.group_id
       WHERE m.user_id = ?1
       ORDER BY m.joined_at, g.id`,
    )
    .bind(userId)
    .all<GroupInfoRow>();
  return results.map((r) => ({
    id: r.id,
    inviteCode: r.invite_code,
    name: r.name,
    memberCount: r.member_count,
    otherMembers: (JSON.parse(r.others) as string[]).sort((a, b) => a.localeCompare(b)),
    share: shareOf(r),
  }));
}

export async function groupInfo(db: D1Database, userId: string, groupId: string): Promise<GroupInfo> {
  const info = (await groupInfos(db, userId)).find((g) => g.id === groupId);
  if (!info) throw new HttpError(404, "not_member");
  return info;
}

async function meBody(db: D1Database, userId: string, displayName: string) {
  const backup = await db.prepare("SELECT updated_at FROM backups WHERE user_id = ?1").bind(userId).first<{ updated_at: number }>();
  return { displayName, groups: await groupInfos(db, userId), backupAt: backup?.updated_at ?? null };
}

function insertUser(db: D1Database, id: string, name: string, tokenHash: string, now: number) {
  return db
    .prepare("INSERT INTO users (id, token_hash, display_name, created_at) VALUES (?1, ?2, ?3, ?4)")
    .bind(id, tokenHash, name, now);
}

function insertMembership(db: D1Database, userId: string, groupId: string, share: Share, now: number) {
  return db
    .prepare(
      `INSERT INTO memberships (user_id, group_id, share_saved, share_streak, share_pauses, joined_at)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6)`,
    )
    .bind(userId, groupId, +share.saved, +share.streak, +share.pauses, now);
}

async function assertRoomForAnotherGroup(db: D1Database, userId: string) {
  const row = await db.prepare("SELECT COUNT(*) AS n FROM memberships WHERE user_id = ?1").bind(userId).first<{ n: number }>();
  if ((row?.n ?? 0) >= MAX_GROUPS) throw new HttpError(409, "too_many_groups");
}

/** A backup-only identity has no name yet; its first group needs one. */
function nameForFirstGroup(db: D1Database, user: UserRow, body: Record<string, unknown>): D1PreparedStatement[] {
  if (user.display_name !== "") return [];
  const name = parseDisplayName(body.displayName);
  return [db.prepare("UPDATE users SET display_name = ?2 WHERE id = ?1").bind(user.id, name)];
}

export async function createUser(req: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(req);
  const token = parseToken(body.token);
  const name = body.displayName === undefined ? "" : parseDisplayName(body.displayName);
  try {
    await insertUser(env.monolith_leaderboard, crypto.randomUUID(), name, await sha256Hex(token), now).run();
  } catch (e) {
    if (String(e).includes("UNIQUE")) throw new HttpError(409, "token_taken");
    throw e;
  }
  return json({}, 201);
}

export async function rotateToken(req: Request, env: Env, user: UserRow): Promise<Response> {
  const token = parseToken((await readJson(req)).token);
  try {
    await env.monolith_leaderboard
      .prepare("UPDATE users SET token_hash = ?2 WHERE id = ?1")
      .bind(user.id, await sha256Hex(token))
      .run();
  } catch (e) {
    if (String(e).includes("UNIQUE")) throw new HttpError(409, "token_taken");
    throw e;
  }
  return empty();
}

export async function createGroup(req: Request, env: Env, now: number): Promise<Response> {
  const body = await readJson(req);
  const share = parseShare(body.share);
  const db = env.monolith_leaderboard;
  const user = await authenticate(req, env);
  const nameUpdate = nameForFirstGroup(db, user, body);
  await assertRoomForAnotherGroup(db, user.id);
  // An invite code collision is astronomically unlikely, but it is a UNIQUE violation, not a crash.
  for (let attempt = 0; attempt < 3; attempt++) {
    const groupId = crypto.randomUUID();
    try {
      await db.batch([
        ...nameUpdate,
        db.prepare("INSERT INTO groups (id, invite_code, created_at) VALUES (?1, ?2, ?3)").bind(groupId, newInviteCode(), now),
        insertMembership(db, user.id, groupId, share, now),
      ]);
      return json({ group: await groupInfo(db, user.id, groupId) }, 201);
    } catch (e) {
      if (!String(e).includes("UNIQUE")) throw e;
    }
  }
  throw new HttpError(500, "internal");
}

export async function joinGroup(req: Request, env: Env, now: number): Promise<Response> {
  const user = await authenticate(req, env);
  const body = await readJson(req);
  const share = parseShare(body.share);
  if (typeof body.inviteCode !== "string") throw new HttpError(400, "invalid_body");
  const code = normalizeInviteCode(body.inviteCode);
  if (!code) throw new HttpError(404, "invite_not_found");
  const db = env.monolith_leaderboard;
  const group = await db.prepare("SELECT id FROM groups WHERE invite_code = ?1").bind(code).first<{ id: string }>();
  if (!group) throw new HttpError(404, "invite_not_found");

  const already = await db
    .prepare("SELECT 1 AS x FROM memberships WHERE user_id = ?1 AND group_id = ?2")
    .bind(user.id, group.id)
    .first();
  if (already) throw new HttpError(409, "already_member");
  const size = await db.prepare("SELECT COUNT(*) AS n FROM memberships WHERE group_id = ?1").bind(group.id).first<{ n: number }>();
  // Count then insert: two joins racing for the last seat can make it 21. Accepted for friends.
  if ((size?.n ?? 0) >= MAX_MEMBERS) throw new HttpError(409, "group_full");
  await assertRoomForAnotherGroup(db, user.id);
  const nameUpdate = nameForFirstGroup(db, user, body);

  await db.batch([...nameUpdate, insertMembership(db, user.id, group.id, share, now)]);
  return json({ group: await groupInfo(db, user.id, group.id) }, 201);
}

export async function getMe(_req: Request, env: Env, user: UserRow): Promise<Response> {
  return json(await meBody(env.monolith_leaderboard, user.id, user.display_name));
}

export async function updateMe(req: Request, env: Env, user: UserRow): Promise<Response> {
  const body = await readJson(req);
  const name = parseDisplayName(body.displayName);
  const db = env.monolith_leaderboard;
  await db.prepare("UPDATE users SET display_name = ?2 WHERE id = ?1").bind(user.id, name).run();
  return json(await meBody(db, user.id, name));
}

async function assertMember(db: D1Database, userId: string, groupId: string) {
  const row = await db
    .prepare("SELECT 1 AS x FROM memberships WHERE user_id = ?1 AND group_id = ?2")
    .bind(userId, groupId)
    .first();
  if (!row) throw new HttpError(404, "not_member");
}

export async function updateGroup(req: Request, env: Env, user: UserRow, _now: number, groupId: string): Promise<Response> {
  const db = env.monolith_leaderboard;
  await assertMember(db, user.id, groupId);
  const body = await readJson(req);
  const statements: D1PreparedStatement[] = [];
  if (body.share !== undefined) {
    const share = parseShare(body.share);
    statements.push(
      db
        .prepare("UPDATE memberships SET share_saved = ?3, share_streak = ?4, share_pauses = ?5 WHERE user_id = ?1 AND group_id = ?2")
        .bind(user.id, groupId, +share.saved, +share.streak, +share.pauses),
    );
  }
  if (body.name !== undefined) {
    const name = parseGroupName(body.name);
    if (name !== null) {
      const size = await db.prepare("SELECT COUNT(*) AS n FROM memberships WHERE group_id = ?1").bind(groupId).first<{ n: number }>();
      if ((size?.n ?? 0) < 3) throw new HttpError(409, "name_needs_three");
    }
    statements.push(db.prepare("UPDATE groups SET name = ?2 WHERE id = ?1").bind(groupId, name));
  }
  if (statements.length > 0) await db.batch([...statements, ...invariantStatements(db, [user.id], [groupId])]);
  return json({ group: await groupInfo(db, user.id, groupId) });
}

export async function leaveGroup(_req: Request, env: Env, user: UserRow, _now: number, groupId: string): Promise<Response> {
  const db = env.monolith_leaderboard;
  await assertMember(db, user.id, groupId);
  await db.batch([
    db.prepare("DELETE FROM memberships WHERE user_id = ?1 AND group_id = ?2").bind(user.id, groupId),
    ...invariantStatements(db, [user.id], [groupId]),
  ]);
  return empty();
}
