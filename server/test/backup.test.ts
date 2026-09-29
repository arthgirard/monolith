import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { call, callBytes, join, newUser, register, today } from "./helpers";

const blob = (n: number, fill = 7) => new Uint8Array(n).fill(fill);

describe("backup", () => {
  it("stores, returns and replaces a blob", async () => {
    const token = await register();
    expect((await callBytes("PUT", "/backup", token, blob(10))).status).toBe(204);
    expect((await callBytes("PUT", "/backup", token, blob(5, 9))).status).toBe(204);
    const got = await callBytes("GET", "/backup", token);
    expect(got.status).toBe(200);
    expect(Array.from(got.bytes)).toEqual([9, 9, 9, 9, 9]);
    const me = await call("GET", "/me", undefined, token);
    expect(me.body.backupAt).toBeGreaterThan(Date.now() - 60000);
  });

  it("no backup is 404 no_backup", async () => {
    const token = await register();
    const got = await callBytes("GET", "/backup", token);
    expect(got.status).toBe(404);
    expect(got.json()).toEqual({ error: "no_backup" });
  });

  it("rejects empty and oversized blobs", async () => {
    const token = await register();
    expect((await callBytes("PUT", "/backup", token, new Uint8Array(0))).status).toBe(400);
    const big = await callBytes("PUT", "/backup", token, blob(1_048_577));
    expect(big.status).toBe(413);
    expect(big.json()).toEqual({ error: "too_large" });
    expect((await callBytes("PUT", "/backup", token, blob(1_048_576))).status).toBe(204);
  });

  it("delete is idempotent and removes a groupless user", async () => {
    const token = await register();
    await callBytes("PUT", "/backup", token, blob(3));
    expect((await callBytes("DELETE", "/backup", token)).status).toBe(204);
    expect((await call("GET", "/me", undefined, token)).status).toBe(401);
  });

  it("delete keeps a user who is still in a group", async () => {
    const ana = await newUser("Ana");
    await callBytes("PUT", "/backup", ana.token, blob(3));
    expect((await callBytes("DELETE", "/backup", ana.token)).status).toBe(204);
    expect((await callBytes("DELETE", "/backup", ana.token)).status).toBe(204);
    const me = await call("GET", "/me", undefined, ana.token);
    expect(me.status).toBe(200);
    expect(me.body.backupAt).toBeNull();
  });

  it("leaving the last group keeps a user with a backup", async () => {
    const ana = await newUser("Ana");
    await call("POST", "/sync", { days: [{ date: today(), savedMs: 1000, bypassCount: 1, unlockCount: 1 }], streakStartedAt: null }, ana.token);
    await callBytes("PUT", "/backup", ana.token, blob(3));
    expect((await call("DELETE", `/groups/${ana.group.id}`, undefined, ana.token)).status).toBe(204);
    const me = await call("GET", "/me", undefined, ana.token);
    expect(me.body).toMatchObject({ displayName: "Ana", groups: [] });
    expect((await callBytes("GET", "/backup", ana.token)).status).toBe(200);
    const days = await env.monolith_leaderboard.prepare("SELECT COUNT(*) AS n FROM days").first<{ n: number }>();
    expect(days!.n).toBe(0);
  });

  it("a backup upload removes stale backup-only users", async () => {
    const stale = await register();
    await callBytes("PUT", "/backup", stale, blob(3));
    const old = Date.now() - 121 * 24 * 60 * 60 * 1000;
    await env.monolith_leaderboard.batch([
      env.monolith_leaderboard.prepare("UPDATE users SET created_at = ?1").bind(old),
      env.monolith_leaderboard.prepare("UPDATE backups SET updated_at = ?1").bind(old),
    ]);
    const fresh = await register();
    await callBytes("PUT", "/backup", fresh, blob(3));
    expect((await call("GET", "/me", undefined, stale)).status).toBe(401);
    expect((await call("GET", "/me", undefined, fresh)).status).toBe(200);
  });

  it("a recent backup keeps a group member off the board cleanup", async () => {
    const ana = await newUser("Ana");
    const ben = await join(ana.group.inviteCode, "Ben");
    await callBytes("PUT", "/backup", ben.token, blob(3));
    const old = Date.now() - 121 * 24 * 60 * 60 * 1000;
    await env.monolith_leaderboard.prepare("UPDATE users SET created_at = ?1").bind(old).run();
    await env.monolith_leaderboard.prepare("UPDATE users SET last_sync_at = ?1 WHERE display_name = 'Ana'").bind(Date.now()).run();
    const board = await call("GET", `/groups/${ana.group.id}/board?window=day&date=${today()}`, undefined, ana.token);
    expect(board.body.rows.map((r: { name: string }) => r.name).sort()).toEqual(["Ana", "Ben"]);
  });
});
