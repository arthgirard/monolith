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

export async function createGroup(displayName = "Ana", s: Share = share()) {
  const res = await call("POST", "/groups", { displayName, share: s });
  return res.body as { token: string; inviteCode: string };
}

export async function join(inviteCode: string, displayName: string, s: Share = share()) {
  const res = await call("POST", "/join", { inviteCode, displayName, share: s });
  return res.body as { token: string; inviteCode: string };
}

export const today = (offset = 0) => addDays(utcToday(Date.now()), offset);
