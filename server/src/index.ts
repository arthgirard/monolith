import { authenticate } from "./auth";
import { createGroup, getMe, joinGroup, leave } from "./groups";
import { HttpError, json } from "./http";
import type { Env, MemberRow } from "./types";

type AuthedHandler = (req: Request, env: Env, member: MemberRow, now: number) => Promise<Response>;

export const authedRoutes: Record<string, AuthedHandler> = {
  "GET /me": getMe,
  "DELETE /me": leave,
};

async function route(req: Request, env: Env, now: number): Promise<Response> {
  const key = `${req.method} ${new URL(req.url).pathname}`;
  if (key === "POST /groups") return createGroup(req, env, now);
  if (key === "POST /join") return joinGroup(req, env, now);
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
