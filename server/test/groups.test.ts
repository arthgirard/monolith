import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { anotherGroup, call, join, newUser, share } from "./helpers";

const count = async (table: string) =>
  (await env.monolith_leaderboard.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first<{ n: number }>())!.n;

describe("identity and groups", () => {
  it("creating without a token makes a user, a group and a membership", async () => {
    const res = await call("POST", "/groups", { displayName: " Ana ", share: share(true, false, true) });
    expect(res.status).toBe(201);
    expect(res.body.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(res.body.group).toMatchObject({
      name: null,
      memberCount: 1,
      otherMembers: [],
      share: { saved: true, streak: false, pauses: true },
    });
    expect(res.body.group.inviteCode).toMatch(/^[0-9A-HJKMNP-TV-Z]{8}$/);
    expect(await count("users")).toBe(1);
    expect(await count("memberships")).toBe(1);
  });

  it("only a hash of the token is stored", async () => {
    const { token } = await newUser();
    const row = await env.monolith_leaderboard.prepare("SELECT token_hash FROM users").first<{ token_hash: string }>();
    expect(row!.token_hash).toMatch(/^[0-9a-f]{64}$/);
    expect(row!.token_hash).not.toBe(token);
  });

  it("creating with a token adds a group to the same user and returns no new token", async () => {
    const { token } = await newUser();
    const res = await call("POST", "/groups", { share: share() }, token);
    expect(res.status).toBe(201);
    expect(res.body.token).toBeUndefined();
    expect(await count("users")).toBe(1);
    const me = await call("GET", "/me", undefined, token);
    expect(me.body.groups).toHaveLength(2);
  });

  it("a creation without a token needs a valid display name", async () => {
    for (const body of [{ share: share() }, { displayName: "  ", share: share() }, { displayName: "x".repeat(25), share: share() }]) {
      expect(await call("POST", "/groups", body)).toEqual({ status: 400, body: { error: "invalid_body" } });
    }
  });

  it("a bad token is a 401, never a new identity", async () => {
    expect((await call("POST", "/groups", { displayName: "Ana", share: share() }, "nope")).status).toBe(401);
    expect(await count("users")).toBe(0);
  });

  it("joining as a new user and as an existing user", async () => {
    const ana = await newUser("Ana");
    const ben = await join(ana.group.inviteCode, "Ben");
    expect(ben.group).toMatchObject({ memberCount: 2, otherMembers: ["Ana"] });

    const cat = await newUser("Cat");
    const res = await call("POST", "/join", { inviteCode: ana.group.inviteCode, share: share() }, cat.token);
    expect(res.status).toBe(201);
    expect(res.body.token).toBeUndefined();
    expect(res.body.group.otherMembers.sort()).toEqual(["Ana", "Ben"]);
  });

  it("join normalizes a sloppy invite code", async () => {
    const ana = await newUser();
    const code = ana.group.inviteCode;
    const sloppy = `${code.slice(0, 4).toLowerCase()} - ${code.slice(4).toLowerCase()}`;
    expect((await call("POST", "/join", { inviteCode: sloppy, displayName: "Ben", share: share() })).status).toBe(201);
  });

  it("join errors: unknown code, already a member, full group", async () => {
    const ana = await newUser();
    expect(await call("POST", "/join", { inviteCode: "ZZZZZZZZ", displayName: "Ben", share: share() }))
      .toEqual({ status: 404, body: { error: "invite_not_found" } });
    expect(await call("POST", "/join", { inviteCode: ana.group.inviteCode, share: share() }, ana.token))
      .toEqual({ status: 409, body: { error: "already_member" } });
    for (let i = 1; i < 20; i++) await join(ana.group.inviteCode, `m${i}`);
    expect(await call("POST", "/join", { inviteCode: ana.group.inviteCode, displayName: "late", share: share() }))
      .toEqual({ status: 409, body: { error: "group_full" } });
  });

  it("at most 10 groups per user, for creating and for joining", async () => {
    const ana = await newUser();
    for (let i = 1; i < 10; i++) await anotherGroup(ana.token);
    expect(await call("POST", "/groups", { share: share() }, ana.token))
      .toEqual({ status: 409, body: { error: "too_many_groups" } });
    const other = await newUser("Ben");
    expect(await call("POST", "/join", { inviteCode: other.group.inviteCode, share: share() }, ana.token))
      .toEqual({ status: 409, body: { error: "too_many_groups" } });
  });

  it("GET /me lists groups oldest first; POST /me renames everywhere", async () => {
    const ana = await newUser("Ana");
    const second = await anotherGroup(ana.token, share(false, false, false));
    const me = await call("GET", "/me", undefined, ana.token);
    expect(me.body.displayName).toBe("Ana");
    expect(me.body.groups.map((g: { id: string }) => g.id)).toEqual([ana.group.id, second.id]);

    const ben = await join(ana.group.inviteCode, "Ben");
    const renamed = await call("POST", "/me", { displayName: "Anna" }, ana.token);
    expect(renamed.body.displayName).toBe("Anna");
    const benView = await call("GET", "/me", undefined, ben.token);
    expect(benView.body.groups[0].otherMembers).toEqual(["Anna"]);
    expect((await call("POST", "/me", { displayName: "" }, ana.token)).status).toBe(400);
  });

  it("requires a valid token on authed routes", async () => {
    expect(await call("GET", "/me")).toEqual({ status: 401, body: { error: "unauthorized" } });
  });

  it("unknown routes are not_found", async () => {
    expect((await call("GET", "/nope")).status).toBe(404);
  });
});
