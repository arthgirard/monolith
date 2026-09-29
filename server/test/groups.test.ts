import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { call, createGroup, join, share } from "./helpers";

describe("groups", () => {
  it("create returns a token and invite code usable with /me", async () => {
    const res = await call("POST", "/groups", { displayName: "  Ana ", share: share(true, false, true) });
    expect(res.status).toBe(201);
    expect(res.body.inviteCode).toMatch(/^[0-9A-HJKMNP-TV-Z]{8}$/);

    const me = await call("GET", "/me", undefined, res.body.token);
    expect(me.status).toBe(200);
    expect(me.body).toEqual({
      displayName: "Ana",
      share: { saved: true, streak: false, pauses: true },
      inviteCode: res.body.inviteCode,
    });
  });

  it("only a hash of the token is stored", async () => {
    const { token } = await createGroup();
    const row = await env.monolith_leaderboard.prepare("SELECT token_hash FROM members").first<{ token_hash: string }>();
    expect(row?.token_hash).not.toBe(token);
    expect(row?.token_hash).toMatch(/^[0-9a-f]{64}$/);
  });

  it("join puts a second member in the same group", async () => {
    const owner = await createGroup("Ana");
    const res = await call("POST", "/join", { inviteCode: owner.inviteCode, displayName: "Ben", share: share() });
    expect(res.status).toBe(201);
    expect(res.body.inviteCode).toBe(owner.inviteCode);
    const count = await env.monolith_leaderboard.prepare("SELECT COUNT(*) AS n FROM members").first<{ n: number }>();
    expect(count?.n).toBe(2);
  });

  it("join normalizes a sloppy invite code", async () => {
    const owner = await createGroup();
    const sloppy = `${owner.inviteCode.slice(0, 4).toLowerCase()} - ${owner.inviteCode.slice(4).toLowerCase()}`;
    const res = await call("POST", "/join", { inviteCode: sloppy, displayName: "Ben", share: share() });
    expect(res.status).toBe(201);
  });

  it("join with an unknown code is invite_not_found", async () => {
    const res = await call("POST", "/join", { inviteCode: "ZZZZZZZZ", displayName: "Ben", share: share() });
    expect(res).toEqual({ status: 404, body: { error: "invite_not_found" } });
  });

  it("the 21st member is refused with group_full", async () => {
    const owner = await createGroup("m0");
    for (let i = 1; i < 20; i++) await join(owner.inviteCode, `m${i}`);
    const res = await call("POST", "/join", { inviteCode: owner.inviteCode, displayName: "late", share: share() });
    expect(res).toEqual({ status: 409, body: { error: "group_full" } });
  });

  it("rejects bad names and shares with invalid_body", async () => {
    for (const body of [
      { displayName: "   ", share: share() },
      { displayName: "x".repeat(25), share: share() },
      { displayName: "Ana", share: { saved: true, streak: true } },
      { displayName: "Ana" },
    ]) {
      expect(await call("POST", "/groups", body)).toEqual({ status: 400, body: { error: "invalid_body" } });
    }
  });

  it("rejects a non-JSON body with invalid_body", async () => {
    const res = await call("POST", "/groups", "not json");
    expect(res.status).toBe(400);
  });

  it("requires a valid bearer token", async () => {
    expect(await call("GET", "/me")).toEqual({ status: 401, body: { error: "unauthorized" } });
    expect(await call("GET", "/me", undefined, "nope")).toEqual({ status: 401, body: { error: "unauthorized" } });
  });

  it("a left member's token is rejected", async () => {
    const owner = await createGroup();
    const ben = await join(owner.inviteCode, "Ben");
    expect((await call("DELETE", "/me", undefined, ben.token)).status).toBe(204);
    expect((await call("GET", "/me", undefined, ben.token)).status).toBe(401);
    expect((await call("GET", "/me", undefined, owner.token)).status).toBe(200);
  });

  it("the last member leaving deletes the group and its days", async () => {
    const owner = await createGroup();
    await env.monolith_leaderboard
      .prepare("INSERT INTO days (member_id, date, saved_ms) SELECT id, '2026-09-01', 1 FROM members")
      .run();
    await call("DELETE", "/me", undefined, owner.token);
    for (const table of ["groups", "members", "days"]) {
      const row = await env.monolith_leaderboard.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first<{ n: number }>();
      expect(row?.n).toBe(0);
    }
  });

  it("unknown routes are not_found", async () => {
    expect((await call("GET", "/nope")).status).toBe(404);
  });
});
