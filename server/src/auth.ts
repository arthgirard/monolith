import { sha256Hex } from "./codes";
import { HttpError } from "./http";
import type { Env, MemberRow } from "./types";

export async function authenticate(req: Request, env: Env): Promise<MemberRow> {
  const match = req.headers.get("authorization")?.match(/^Bearer (\S+)$/);
  if (!match) throw new HttpError(401, "unauthorized");
  const member = await env.monolith_leaderboard
    .prepare(
      `SELECT m.id, m.group_id, m.display_name, m.share_saved, m.share_streak, m.share_pauses, g.invite_code
       FROM members m JOIN groups g ON g.id = m.group_id
       WHERE m.token_hash = ?1`,
    )
    .bind(await sha256Hex(match[1]))
    .first<MemberRow>();
  if (!member) throw new HttpError(401, "unauthorized");
  return member;
}
