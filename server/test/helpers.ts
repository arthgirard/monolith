import { SELF } from "cloudflare:test";
import { addDays, utcToday } from "../src/dates";
import type { Share } from "../src/types";

export const share = (saved = true, streak = true, pauses = true): Share => ({ saved, streak, pauses });

export async function call(method: string, path: string, body?: unknown, token?: string) {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["content-type"] = "application/json";
  if (token) headers.authorization = `Bearer ${token}`;
  const res = await SELF.fetch(`https://leaderboard.test${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return { status: res.status, body: (text ? JSON.parse(text) : null) as any };
}

export interface Group {
  id: string;
  inviteCode: string;
  name: string | null;
  memberCount: number;
  otherMembers: string[];
  share: Share;
}

/** New user + new group. */
export async function newUser(displayName = "Ana", s: Share = share()) {
  const res = await call("POST", "/groups", { displayName, share: s });
  if (res.status !== 201) throw new Error(`newUser ${res.status} ${JSON.stringify(res.body)}`);
  return res.body as { token: string; group: Group };
}

/** Existing user creates another group. */
export async function anotherGroup(token: string, s: Share = share()) {
  const res = await call("POST", "/groups", { share: s }, token);
  if (res.status !== 201) throw new Error(`anotherGroup ${res.status} ${JSON.stringify(res.body)}`);
  return res.body.group as Group;
}

/** Join as a new user (no token) or as an existing one. Returns the token in use and the group. */
export async function join(inviteCode: string, displayName: string, s: Share = share(), token?: string) {
  const res = await call("POST", "/join", { inviteCode, displayName, share: s }, token);
  if (res.status !== 201) throw new Error(`join ${res.status} ${JSON.stringify(res.body)}`);
  return { token: (res.body.token as string | undefined) ?? token!, group: res.body.group as Group };
}

export const today = (offset = 0) => addDays(utcToday(Date.now()), offset);
