import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { anotherGroup, call, newUser, share, today } from "./helpers";

const days = () =>
  env.monolith_leaderboard.prepare("SELECT date, saved_ms, bypass_count, unlock_count FROM days ORDER BY date").all().then((r) => r.results);

describe("POST /sync", () => {
  it("stores one copy of the days for the user, streak and sync time", async () => {
    const ana = await newUser();
    await anotherGroup(ana.token);
    const res = await call(
      "POST",
      "/sync",
      { days: [{ date: today(), savedMs: 3600000, bypassCount: 1, unlockCount: 2 }], streakStartedAt: 1234 },
      ana.token,
    );
    expect(res.status).toBe(204);
    expect(await days()).toEqual([{ date: today(), saved_ms: 3600000, bypass_count: 1, unlock_count: 2 }]);
    const u = await env.monolith_leaderboard.prepare("SELECT streak_started_at, last_sync_at FROM users").first<{ streak_started_at: number; last_sync_at: number }>();
    expect(u!.streak_started_at).toBe(1234);
    expect(u!.last_sync_at).toBeGreaterThan(Date.now() - 60000);
  });

  it("is idempotent and the latest upload wins", async () => {
    const ana = await newUser();
    const body = (saved: number) => ({ days: [{ date: today(), savedMs: saved, bypassCount: 0, unlockCount: 0 }] });
    await call("POST", "/sync", body(100), ana.token);
    await call("POST", "/sync", body(250), ana.token);
    expect(await days()).toEqual([{ date: today(), saved_ms: 250, bypass_count: 0, unlock_count: 0 }]);
  });

  it("union decides hidden_signal", async () => {
    const ana = await newUser("Ana", share(false, false, false));
    const second = await anotherGroup(ana.token, share(true, false, false));
    expect((await call("POST", "/sync", { days: [{ date: today(), savedMs: 1 }] }, ana.token)).status).toBe(204);
    expect(await call("POST", "/sync", { days: [{ date: today(), bypassCount: 1 }] }, ana.token))
      .toEqual({ status: 422, body: { error: "hidden_signal" } });
    expect(await call("POST", "/sync", { days: [], streakStartedAt: 5 }, ana.token))
      .toEqual({ status: 422, body: { error: "hidden_signal" } });
    await call("POST", `/groups/${second.id}`, { share: share(false, false, false) }, ana.token);
    expect((await call("POST", "/sync", { days: [{ date: today(), savedMs: 1 }] }, ana.token)).status).toBe(422);
  });

  it("accepts the window edges, clamps a future streak, prunes old days", async () => {
    const ana = await newUser();
    await env.monolith_leaderboard.prepare("INSERT INTO days (user_id, date, saved_ms) SELECT id, ?1, 1 FROM users").bind(today(-40)).run();
    const edge = [today(-36), today(1)].map((date) => ({ date, savedMs: 1, bypassCount: 0, unlockCount: 0 }));
    const future = Date.now() + 3_600_000;
    expect((await call("POST", "/sync", { days: edge, streakStartedAt: future }, ana.token)).status).toBe(204);
    expect((await days()).map((d) => d.date)).toEqual([today(-36), today(1)]);
    const u = await env.monolith_leaderboard.prepare("SELECT streak_started_at FROM users").first<{ streak_started_at: number }>();
    expect(u!.streak_started_at).toBeLessThanOrEqual(Date.now());
  });

  it("stores when credit accrues again, relative to our clock, and the phone's offset", async () => {
    const ana = await newUser();
    const accrual = () =>
      env.monolith_leaderboard.prepare("SELECT accruing_since, utc_offset_min, last_sync_at FROM users")
        .first<{ accruing_since: number | null; utc_offset_min: number | null; last_sync_at: number }>();
    await call("POST", "/sync", { days: [], resumesInMs: 60000, utcOffsetMinutes: -240 }, ana.token);
    const paused = await accrual();
    expect(paused!.accruing_since).toBe(paused!.last_sync_at + 60000);
    expect(paused!.utc_offset_min).toBe(-240);
    // An older client sends neither: nothing accrues, the offset stays.
    await call("POST", "/sync", { days: [] }, ana.token);
    expect(await accrual()).toMatchObject({ accruing_since: null, utc_offset_min: -240 });
  });

  it("accrual needs time gained or streak shared", async () => {
    const ana = await newUser("Ana", share(false, true, false));
    expect((await call("POST", "/sync", { days: [], resumesInMs: 0, utcOffsetMinutes: 0 }, ana.token)).status).toBe(204);
    await call("POST", `/groups/${ana.group.id}`, { share: share(false, false, true) }, ana.token);
    expect(await call("POST", "/sync", { days: [], resumesInMs: 0 }, ana.token))
      .toEqual({ status: 422, body: { error: "hidden_signal" } });
  });

  it("rejects out-of-range bodies", async () => {
    const ana = await newUser();
    for (const body of [
      { days: [{ date: today(-37), savedMs: 1 }] },
      { days: [{ date: today(), savedMs: 86400001 }] },
      { days: [{ date: today() }, { date: today() }] },
      { days: "nope" },
      { days: [], resumesInMs: -1 },
      { days: [], utcOffsetMinutes: 900 },
    ]) {
      expect((await call("POST", "/sync", body, ana.token)).status).toBe(400);
    }
  });

  describe("blocked apps", () => {
    const stored = () =>
      env.monolith_leaderboard.prepare("SELECT blocked_apps FROM users").first<{ blocked_apps: string | null }>().then((r) => r!.blocked_apps);
    const apps = [{ packageName: "com.example.video", label: " Video " }];

    it("stores the trimmed list, keeps it when absent, clears it on null", async () => {
      const ana = await newUser();
      expect((await call("POST", "/sync", { days: [], apps }, ana.token)).status).toBe(204);
      expect(JSON.parse((await stored())!)).toEqual([{ packageName: "com.example.video", label: "Video" }]);
      await call("POST", "/sync", { days: [] }, ana.token);
      expect(await stored()).not.toBeNull();
      await call("POST", "/sync", { days: [], apps: null }, ana.token);
      expect(await stored()).toBeNull();
    });

    it("is a hidden signal when no group shares it, and is nulled when the last one stops", async () => {
      const hiding = await newUser("Ben", share(true, true, true, false));
      expect(await call("POST", "/sync", { days: [], apps }, hiding.token)).toEqual({ status: 422, body: { error: "hidden_signal" } });

      await env.monolith_leaderboard.prepare("DELETE FROM users").run();
      const ana = await newUser();
      await call("POST", "/sync", { days: [], apps }, ana.token);
      await call("POST", `/groups/${ana.group.id}`, { share: share(true, true, true, false) }, ana.token);
      expect(await stored()).toBeNull();
    });

    it("rejects malformed lists", async () => {
      const ana = await newUser();
      const many = Array.from({ length: 201 }, (_, i) => ({ packageName: `com.example.a${i}`, label: "A" }));
      for (const bad of [
        "nope",
        many,
        [{ packageName: "not a package", label: "A" }],
        [{ packageName: "com.example.a", label: "  " }],
        [{ packageName: "com.example.a", label: "x".repeat(65) }],
        [{ packageName: "com.example.a", label: "A" }, { packageName: "com.example.a", label: "B" }],
      ]) {
        expect((await call("POST", "/sync", { days: [], apps: bad }, ana.token)).status).toBe(400);
      }
    });
  });
});
