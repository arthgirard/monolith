import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { PROJECTION_MAX_MS, project, type BoardQueryRow } from "../src/board";
import { anotherGroup, call, join, newUser, share, today } from "./helpers";

async function syncDay(token: string, savedMs: number, bypassCount = 0, unlockCount = 0, streakStartedAt?: number) {
  const res = await call("POST", "/sync", { days: [{ date: today(), savedMs, bypassCount, unlockCount }], streakStartedAt }, token);
  expect(res.status).toBe(204);
}

const board = (token: string, groupId: string, window = "day") =>
  call("GET", `/groups/${groupId}/board?window=${window}&date=${today()}`, undefined, token);

describe("GET /groups/:id/board", () => {
  it("ranks members of that group only and marks me", async () => {
    const ana = await newUser("Ana");
    const ben = await join(ana.group.inviteCode, "Ben");
    const outsider = await newUser("Zed");
    await syncDay(ana.token, 100, 1, 2, 777);
    await syncDay(ben.token, 300);
    await syncDay(outsider.token, 999);
    const res = await board(ana.token, ana.group.id);
    expect(res.body.rows.map((r: { name: string; rank: number }) => [r.name, r.rank])).toEqual([["Ben", 1], ["Ana", 2]]);
    expect(res.body.rows[1]).toMatchObject({ isMe: true, savedMs: 100, bypassCount: 1, unlockCount: 2, streak: { startedAt: 777 } });
  });

  it("per-group reciprocity", async () => {
    const ana = await newUser("Ana", share(true, true, true));
    const ben = await join(ana.group.inviteCode, "Ben", share(true, true, true));
    const second = await anotherGroup(ana.token, share(true, false, false));
    await join(second.inviteCode, "Ben", share(true, true, true), ben.token);
    await syncDay(ben.token, 50, 3, 1, 555);

    const open = (await board(ana.token, ana.group.id)).body.rows.find((r: { name: string }) => r.name === "Ben");
    expect(open).toMatchObject({ savedMs: 50, bypassCount: 3, streak: { startedAt: 555 } });

    const strict = (await board(ana.token, second.id)).body.rows.find((r: { name: string }) => r.name === "Ben");
    expect(strict.savedMs).toBe(50);
    expect(strict.streak).toBeUndefined();
    expect(strict.bypassCount).toBeUndefined();
  });

  it("a viewer hiding time gained gets no ranks, rows by name", async () => {
    const ana = await newUser("Ana");
    const zed = await join(ana.group.inviteCode, "Zed", share(false, true, true));
    await syncDay(ana.token, 999);
    const rows = (await board(zed.token, ana.group.id)).body.rows;
    expect(rows.map((r: { name: string }) => r.name)).toEqual(["Ana", "Zed"]);
    for (const r of rows) expect(r.rank).toBeUndefined();
  });

  it("blocked apps show only when both share them", async () => {
    const ana = await newUser("Ana");
    const ben = await join(ana.group.inviteCode, "Ben");
    const apps = [{ packageName: "com.example.video", label: "Video" }];
    await call("POST", "/sync", { days: [], apps }, ben.token);
    const benRow = async () => (await board(ana.token, ana.group.id)).body.rows.find((r: { name: string }) => r.name === "Ben");
    expect((await benRow()).apps).toEqual(apps);
    // Ana shares but never uploaded: an empty list, not hidden.
    const anaRow = (await board(ben.token, ana.group.id)).body.rows.find((r: { name: string }) => r.name === "Ana");
    expect(anaRow.apps).toEqual([]);

    await call("POST", `/groups/${ana.group.id}`, { share: share(true, true, true, false) }, ana.token);
    expect((await benRow()).apps).toBeUndefined();
  });

  it("a board for a group you're not in is not_member", async () => {
    const ana = await newUser("Ana");
    const ben = await newUser("Ben");
    expect(await board(ana.token, ben.group.id)).toEqual({ status: 404, body: { error: "not_member" } });
  });

  it("cleanup removes stale users from every group", async () => {
    const ana = await newUser("Ana");
    const ghost = await join(ana.group.inviteCode, "Ghost");
    const ghostOnly = await anotherGroup(ghost.token);
    await join(ana.group.inviteCode, "Recent");
    const old = Date.now() - 121 * 24 * 60 * 60 * 1000;
    await env.monolith_leaderboard.batch([
      env.monolith_leaderboard.prepare("UPDATE users SET created_at = ?1").bind(old),
      env.monolith_leaderboard.prepare("UPDATE users SET last_sync_at = ?1 WHERE display_name IN ('Ana', 'Recent')").bind(Date.now()),
    ]);
    const names = (await board(ana.token, ana.group.id)).body.rows.map((r: { name: string }) => r.name);
    expect(names).toEqual(["Ana", "Recent"]);
    const leftover = await env.monolith_leaderboard.prepare("SELECT COUNT(*) AS n FROM groups WHERE id = ?1").bind(ghostOnly.id).first<{ n: number }>();
    expect(leftover!.n).toBe(0);
    expect((await call("GET", "/me", undefined, ghost.token)).status).toBe(401);
  });

  it("rejects a bad window or date", async () => {
    const ana = await newUser();
    expect((await call("GET", `/groups/${ana.group.id}/board?window=year&date=${today()}`, undefined, ana.token)).status).toBe(400);
    expect((await call("GET", `/groups/${ana.group.id}/board?window=day&date=2026-13-01`, undefined, ana.token)).status).toBe(400);
  });
});

describe("project", () => {
  const HOUR = 3_600_000;
  // 2026-09-29 10:00 UTC.
  const t0 = Date.parse("2026-09-29T10:00:00Z");
  const row = (over: Partial<BoardQueryRow>): BoardQueryRow => ({
    id: "u", display_name: "Ana", share_saved: 1, share_streak: 1, share_pauses: 1, share_apps: 1, blocked_apps: null,
    streak_started_at: t0 - HOUR, last_sync_at: t0, accruing_since: t0, utc_offset_min: 0,
    saved: 1000, bypass: 0, unlock: 0, ...over,
  });
  const day = (r: BoardQueryRow, now: number, date = "2026-09-29") => project([r], date, date, now)[0];

  it("keeps gaining from the last upload while a block runs", () => {
    expect(day(row({}), t0 + 2 * HOUR).saved).toBe(1000 + 2 * HOUR);
    expect(day(row({ saved: null }), t0 + HOUR).saved).toBe(HOUR);
  });

  it("leaves a member with nothing accruing, or an older app, alone", () => {
    const off = row({ accruing_since: null, streak_started_at: null });
    expect(day(off, t0 + HOUR)).toEqual(off);
  });

  it("splits at the member's own midnight", () => {
    // UTC-4: the member's 2026-09-29 ends at 2026-09-30 04:00 UTC.
    const r = row({ utc_offset_min: -240 });
    const now = Date.parse("2026-09-30T06:00:00Z");
    expect(day(r, now).saved).toBe(1000 + 18 * HOUR);
    expect(day(r, now, "2026-09-30").saved).toBe(1000 + 2 * HOUR);
    expect(project([r], "2026-09-28", "2026-10-04", now)[0].saved).toBe(1000 + 20 * HOUR);
  });

  it("resumes when a pause runs out unseen, streak included", () => {
    const paused = row({ streak_started_at: null, accruing_since: t0 + HOUR });
    const during = day(paused, t0 + HOUR / 2);
    expect(during.saved).toBe(1000);
    expect(during.streak_started_at).toBeNull();
    const after = day(paused, t0 + 3 * HOUR);
    expect(after.saved).toBe(1000 + 2 * HOUR);
    expect(after.streak_started_at).toBe(t0 + HOUR);
  });

  it("keeps a running streak's own start", () => {
    expect(day(row({}), t0 + HOUR).streak_started_at).toBe(t0 - HOUR);
  });

  it("stops 48h after the last upload", () => {
    const r = row({});
    const later = project([r], "2026-09-01", "2026-10-31", t0 + 100 * HOUR)[0];
    expect(later.saved).toBe(1000 + PROJECTION_MAX_MS);
    const pausedTooLong = row({ streak_started_at: null, accruing_since: t0 + 49 * HOUR });
    expect(day(pausedTooLong, t0 + 50 * HOUR).streak_started_at).toBeNull();
  });
});
