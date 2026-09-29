import { sha256Hex } from "./codes";
import { HttpError } from "./http";
import type { Env, UserRow } from "./types";

async function userFor(header: string | null, env: Env): Promise<UserRow | null | undefined> {
  if (header === null) return undefined;
  const match = header.match(/^Bearer (\S+)$/);
  if (!match) return null;
  return env.monolith_leaderboard
    .prepare("SELECT id, display_name FROM users WHERE token_hash = ?1")
    .bind(await sha256Hex(match[1]))
    .first<UserRow>();
}

export async function authenticate(req: Request, env: Env): Promise<UserRow> {
  const user = await userFor(req.headers.get("authorization"), env);
  if (!user) throw new HttpError(401, "unauthorized");
  return user;
}

/** No header means "new user"; a header that doesn't resolve is still a 401, never a silent new identity. */
export async function optionalUser(req: Request, env: Env): Promise<UserRow | null> {
  const user = await userFor(req.headers.get("authorization"), env);
  if (user === undefined) return null;
  if (user === null) throw new HttpError(401, "unauthorized");
  return user;
}
