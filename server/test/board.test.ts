import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
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
