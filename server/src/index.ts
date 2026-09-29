import { authenticate } from "./auth";
import { board } from "./board";
import { createGroup, createUser, getMe, joinGroup, leaveGroup, rotateToken, updateGroup, updateMe } from "./groups";
import { HttpError, json } from "./http";
import { sync } from "./sync";
import type { Env, UserRow } from "./types";

type AuthedHandler = (req: Request, env: Env, user: UserRow, now: number) => Promise<Response>;
type GroupHandler = (req: Request, env: Env, user: UserRow, now: number, groupId: string) => Promise<Response>;

export const authedRoutes: Record<string, AuthedHandler> = {
  "GET /me": getMe,
  "POST /me": updateMe,
  "POST /me/token": rotateToken,
  "POST /sync": sync,
};

/** Keyed by method plus "" for /groups/:id or "/board" for /groups/:id/board. */
export const groupRoutes: Record<string, GroupHandler> = {
  "POST ": updateGroup,
  "DELETE ": leaveGroup,
  "GET /board": board,
};

const GROUP_PATH = /^\/groups\/([^/]+)(\/board)?$/;

async function route(req: Request, env: Env, now: number): Promise<Response> {
  const path = new URL(req.url).pathname;
  const key = `${req.method} ${path}`;
  if (key === "POST /users") return createUser(req, env, now);
  if (key === "POST /groups") return createGroup(req, env, now);
  if (key === "POST /join") return joinGroup(req, env, now);

  const groupMatch = path.match(GROUP_PATH);
  if (groupMatch) {
    const handler = groupRoutes[`${req.method} ${groupMatch[2] ?? ""}`];
    if (!handler) throw new HttpError(404, "not_found");
    let groupId: string;
    try {
      groupId = decodeURIComponent(groupMatch[1]);
    } catch {
      throw new HttpError(404, "not_found");
    }
    return handler(req, env, await authenticate(req, env), now, groupId);
  }

  const handler = authedRoutes[key];
  if (!handler) throw new HttpError(404, "not_found");
  return handler(req, env, await authenticate(req, env), now);
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    try {
      return await route(req, env, Date.now());
    } catch (e) {
      if (e instanceof HttpError) return json({ error: e.code }, e.status);
      return json({ error: "internal" }, 500);
    }
  },
} satisfies ExportedHandler<Env>;
