import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { call, createGroup, join, share, today } from "./helpers";

async function syncDay(token: string, date: string, savedMs: number, bypassCount = 0, unlockCount = 0, streakStartedAt?: number) {
  const res = await call("POST", "/sync", { days: [{ date, savedMs, bypassCount, unlockCount }], streakStartedAt }, token);
  expect(res.status).toBe(204);
}

const board = (token: string, window = "week", date = today()) =>
  call("GET", `/board?window=${window}&date=${date}`, undefined, token);

describe("GET /board", () => {
  it("ranks by time gained over the window and marks me", async () => {
    const ana = await createGroup("Ana");
    const ben = await join(ana.inviteCode, "Ben");
    await syncDay(ana.token, today(), 100, 1, 2, 777);
    await syncDay(ben.token, today(), 300);

    const res = await board(ana.token, "day");
    expect(res.status).toBe(200);
    expect(res.body.rows.map((r: { name: string; rank: number }) => [r.name, r.rank])).toEqual([["Ben", 1], ["Ana", 2]]);
    expect(res.body.rows[1]).toMatchObject({
      isMe: true,
      savedMs: 100,
      bypassCount: 1,
      unlockCount: 2,
      streak: { startedAt: 777 },
    });
    expect(res.body.rows[0].streak).toEqual({ startedAt: null });
  });

  it("sums only days inside the window", async () => {
    const ana = await createGroup("Ana");
    await call(
      "POST",
      "/sync",
      { days: [today(), today(-1), today(-35)].map((date) => ({ date, savedMs: 10, bypassCount: 0, unlockCount: 0 })) },
      ana.token,
    );
    const day = await board(ana.token, "day");
    expect(day.body.rows[0].savedMs).toBe(10);
  });

  it("members with no days in the window rank at zero", async () => {
    const ana = await createGroup("Ana");
    await join(ana.inviteCode, "Ben");
    const res = await board(ana.token, "day");
    expect(res.body.rows.map((r: { savedMs: number }) => r.savedMs)).toEqual([0, 0]);
    expect(res.body.rows.map((r: { name: string }) => r.name)).toEqual(["Ana", "Ben"]);
  });

  it("ties share a rank", async () => {
    const ana = await createGroup("Ana");
    const ben = await join(ana.inviteCode, "Ben");
    const cat = await join(ana.inviteCode, "Cat");
    await syncDay(ana.token, today(), 50);
    await syncDay(ben.token, today(), 50);
    await syncDay(cat.token, today(), 10);
    const ranks = (await board(ana.token, "day")).body.rows.map((r: { rank: number }) => r.rank);
    expect(ranks).toEqual([1, 1, 3]);
  });

  it("a member hiding time gained is unranked and listed after the ranked rows", async () => {
    const ana = await createGroup("Ana");
    const zed = await join(ana.inviteCode, "Aaron", share(false, true, true));
    await syncDay(ana.token, today(), 50);
    await call("POST", "/sync", { days: [{ date: today(), bypassCount: 3, unlockCount: 0 }] }, zed.token);
    const rows = (await board(ana.token, "day")).body.rows;
    expect(rows.map((r: { name: string }) => r.name)).toEqual(["Ana", "Aaron"]);
    expect(rows[1].rank).toBeUndefined();
    expect(rows[1].savedMs).toBeUndefined();
    expect(rows[1].bypassCount).toBe(3);
  });

  it("a viewer hiding time gained gets no ranks, rows by name", async () => {
    const ana = await createGroup("Ana");
    const zed = await join(ana.inviteCode, "Zed", share(false, true, true));
    await syncDay(ana.token, today(), 999);
    const rows = (await board(zed.token, "day")).body.rows;
    expect(rows.map((r: { name: string }) => r.name)).toEqual(["Ana", "Zed"]);
    for (const r of rows) {
      expect(r.rank).toBeUndefined();
      expect(r.savedMs).toBeUndefined();
    }
  });

  it("reciprocity hides streak and pauses the viewer hides, including on their own row", async () => {
    const ana = await createGroup("Ana", share(true, false, false));
    const ben = await join(ana.inviteCode, "Ben");
    await syncDay(ben.token, today(), 1, 4, 4, 555);
    for (const r of (await board(ana.token, "day")).body.rows) {
      expect(r.streak).toBeUndefined();
      expect(r.bypassCount).toBeUndefined();
      expect(r.unlockCount).toBeUndefined();
    }
  });

  it("removes members inactive for 120 days, but never the viewer", async () => {
    const ana = await createGroup("Ana");
    await join(ana.inviteCode, "Ghost");
    await join(ana.inviteCode, "Recent");
    const old = Date.now() - 121 * 24 * 60 * 60 * 1000;
    await env.monolith_leaderboard.batch([
      env.monolith_leaderboard.prepare("UPDATE members SET created_at = ?1").bind(old),
      env.monolith_leaderboard.prepare("UPDATE members SET last_sync_at = ?1 WHERE display_name = 'Recent'").bind(Date.now()),
    ]);
    const names = (await board(ana.token)).body.rows.map((r: { name: string }) => r.name);
    expect(names).toEqual(["Ana", "Recent"]);
  });

  it("rejects a bad window or date", async () => {
    const ana = await createGroup();
    expect((await board(ana.token, "year")).status).toBe(400);
    expect((await board(ana.token, "day", "2026-13-01")).status).toBe(400);
  });
});
