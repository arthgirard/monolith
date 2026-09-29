import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { call, createGroup, share, today } from "./helpers";

const days = () =>
  env.monolith_leaderboard
    .prepare("SELECT date, saved_ms, bypass_count, unlock_count FROM days ORDER BY date")
    .all()
    .then((r) => r.results);

describe("POST /sync", () => {
  it("stores days, streak and sync time", async () => {
    const { token } = await createGroup();
    const res = await call(
      "POST",
      "/sync",
      { days: [{ date: today(), savedMs: 3600000, bypassCount: 1, unlockCount: 2 }], streakStartedAt: 1234 },
      token,
    );
    expect(res.status).toBe(204);
    expect(await days()).toEqual([{ date: today(), saved_ms: 3600000, bypass_count: 1, unlock_count: 2 }]);
    const m = await env.monolith_leaderboard
      .prepare("SELECT streak_started_at, last_sync_at FROM members")
      .first<{ streak_started_at: number; last_sync_at: number }>();
    expect(m?.streak_started_at).toBe(1234);
    expect(m?.last_sync_at).toBeGreaterThan(Date.now() - 60000);
  });

  it("is idempotent and the latest upload wins", async () => {
    const { token } = await createGroup();
    const body = (saved: number) => ({ days: [{ date: today(), savedMs: saved, bypassCount: 0, unlockCount: 0 }] });
    await call("POST", "/sync", body(100), token);
    await call("POST", "/sync", body(100), token);
    await call("POST", "/sync", body(250), token);
    expect(await days()).toEqual([{ date: today(), saved_ms: 250, bypass_count: 0, unlock_count: 0 }]);
  });

  it("a missing streakStartedAt clears the streak", async () => {
    const { token } = await createGroup();
    await call("POST", "/sync", { days: [], streakStartedAt: 99 }, token);
    await call("POST", "/sync", { days: [] }, token);
    const m = await env.monolith_leaderboard.prepare("SELECT streak_started_at FROM members").first();
    expect(m).toEqual({ streak_started_at: null });
  });

  it("accepts the window edges and keeps them", async () => {
    const { token } = await createGroup();
    const edge = [today(-36), today(1)].map((date) => ({ date, savedMs: 1, bypassCount: 0, unlockCount: 0 }));
    expect((await call("POST", "/sync", { days: edge }, token)).status).toBe(204);
    expect((await days()).map((d) => d.date)).toEqual([today(-36), today(1)]);
  });

  it("prunes this member's days older than the window", async () => {
    const { token } = await createGroup();
    await env.monolith_leaderboard
      .prepare("INSERT INTO days (member_id, date, saved_ms) SELECT id, ?1, 1 FROM members")
      .bind(today(-40))
      .run();
    await call("POST", "/sync", { days: [] }, token);
    expect(await days()).toEqual([]);
  });

  it("rejects out-of-range values", async () => {
    const { token } = await createGroup();
    const bad = [
      { days: [{ date: today(-37), savedMs: 1 }] },
      { days: [{ date: today(2), savedMs: 1 }] },
      { days: [{ date: "2026-02-30", savedMs: 1 }] },
      { days: [{ date: today(), savedMs: 86400001 }] },
      { days: [{ date: today(), savedMs: -1 }] },
      { days: [{ date: today(), bypassCount: 1.5 }] },
      { days: [{ date: today() }, { date: today() }] },
      { days: Array.from({ length: 41 }, (_, i) => ({ date: today(-i % 30) })) },
      { days: "nope" },
      { days: [], streakStartedAt: Date.now() + 3600000 },
    ];
    for (const body of bad) expect(await call("POST", "/sync", body, token)).toEqual({ status: 400, body: { error: "invalid_body" } });
  });

  it("refuses values for hidden signals", async () => {
    const { token } = await createGroup("Ana", share(false, false, false));
    for (const body of [
      { days: [{ date: today(), savedMs: 1 }] },
      { days: [{ date: today(), bypassCount: 1 }] },
      { days: [{ date: today(), unlockCount: 1 }] },
      { days: [], streakStartedAt: 5 },
    ]) {
      expect(await call("POST", "/sync", body, token)).toEqual({ status: 422, body: { error: "hidden_signal" } });
    }
    expect((await call("POST", "/sync", { days: [{ date: today() }] }, token)).status).toBe(204);
  });
});
