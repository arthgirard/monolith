import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { anotherGroup, call, genToken, join, newUser, register, share } from "./helpers";

const count = async (table: string) =>
  (await env.monolith_leaderboard.prepare(`SELECT COUNT(*) AS n FROM ${table}`).first<{ n: number }>())!.n;

describe("identity and groups", () => {
  it("creating makes a group and a membership for a registered identity", async () => {
    const token = await register("Ana");
    const res = await call("POST", "/groups", { share: share(true, false, true) }, token);
    expect(res.status).toBe(201);
    expect(res.body.token).toBeUndefined();
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

  it("a bad token is a 401, never a new identity", async () => {
    expect((await call("POST", "/groups", { displayName: "Ana", share: share() }, "nope")).status).toBe(401);
    expect(await count("users")).toBe(0);
  });

  it("joining as a new identity and as an existing user", async () => {
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
    const ben = await register("Ben");
    expect((await call("POST", "/join", { inviteCode: sloppy, share: share() }, ben)).status).toBe(201);
  });

  it("join errors: unknown code, already a member, full group", async () => {
    const ana = await newUser();
    expect(await call("POST", "/join", { inviteCode: "ZZZZZZZZ", share: share() }, await register("Ben")))
      .toEqual({ status: 404, body: { error: "invite_not_found" } });
    expect(await call("POST", "/join", { inviteCode: ana.group.inviteCode, share: share() }, ana.token))
      .toEqual({ status: 409, body: { error: "already_member" } });
    for (let i = 1; i < 20; i++) await join(ana.group.inviteCode, `m${i}`);
    expect(await call("POST", "/join", { inviteCode: ana.group.inviteCode, share: share() }, await register("late")))
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

describe("updating and leaving groups", () => {
  const stored = (userName: string) =>
    env.monolith_leaderboard
      .prepare(
        `SELECT u.streak_started_at, d.saved_ms, d.bypass_count, d.unlock_count
         FROM users u JOIN days d ON d.user_id = u.id WHERE u.display_name = ?1`,
      )
      .bind(userName)
      .first();

  async function seed(userName: string) {
    await env.monolith_leaderboard.batch([
      env.monolith_leaderboard.prepare("UPDATE users SET streak_started_at = 1000 WHERE display_name = ?1").bind(userName),
      env.monolith_leaderboard
        .prepare("INSERT INTO days (user_id, date, saved_ms, bypass_count, unlock_count) SELECT id, '2026-09-01', 5, 1, 2 FROM users WHERE display_name = ?1")
        .bind(userName),
    ]);
  }

  it("share changes are per group and returned in the group", async () => {
    const ana = await newUser("Ana");
    const second = await anotherGroup(ana.token);
    const res = await call("POST", `/groups/${second.id}`, { share: share(false, true, true) }, ana.token);
    expect(res.status).toBe(200);
    expect(res.body.group.share).toEqual({ saved: false, streak: true, pauses: true, apps: true });
    const me = await call("GET", "/me", undefined, ana.token);
    expect(me.body.groups[0].share.saved).toBe(true);
  });

  it("a client without the apps flag joins hiding it and keeps it on update", async () => {
    const ana = await newUser("Ana");
    const legacy = { saved: true, streak: true, pauses: true };
    const second = (await call("POST", "/groups", { share: legacy }, ana.token)).body.group;
    expect(second.share.apps).toBe(false);
    const res = await call("POST", `/groups/${ana.group.id}`, { share: legacy }, ana.token);
    expect(res.body.group.share.apps).toBe(true);
    expect((await call("POST", "/groups", { share: { ...legacy, apps: "yes" } }, ana.token)).status).toBe(400);
  });

  it("hiding a signal in one group keeps the data while another group shares it", async () => {
    const ana = await newUser("Ana");
    const second = await anotherGroup(ana.token);
    await seed("Ana");
    await call("POST", `/groups/${second.id}`, { share: share(false, false, false) }, ana.token);
    expect(await stored("Ana")).toEqual({ streak_started_at: 1000, saved_ms: 5, bypass_count: 1, unlock_count: 2 });
  });

  it("hiding a signal everywhere nulls it", async () => {
    const ana = await newUser("Ana");
    const second = await anotherGroup(ana.token);
    await seed("Ana");
    await call("POST", `/groups/${ana.group.id}`, { share: share(false, true, false) }, ana.token);
    await call("POST", `/groups/${second.id}`, { share: share(false, false, false) }, ana.token);
    expect(await stored("Ana")).toEqual({ streak_started_at: 1000, saved_ms: null, bypass_count: null, unlock_count: null });
    await call("POST", `/groups/${ana.group.id}`, { share: share(false, false, false) }, ana.token);
    expect(await stored("Ana")).toMatchObject({ streak_started_at: null });
  });

  it("names need three and vanish below three", async () => {
    const ana = await newUser("Ana");
    const ben = await join(ana.group.inviteCode, "Ben");
    expect(await call("POST", `/groups/${ana.group.id}`, { name: "Flat" }, ana.token))
      .toEqual({ status: 409, body: { error: "name_needs_three" } });

    const cat = await join(ana.group.inviteCode, "Cat");
    const named = await call("POST", `/groups/${ana.group.id}`, { name: "  Flatmates " }, ben.token);
    expect(named.body.group.name).toBe("Flatmates");
    expect((await call("POST", `/groups/${ana.group.id}`, { name: "x".repeat(33) }, ana.token)).status).toBe(400);

    expect((await call("DELETE", `/groups/${ana.group.id}`, undefined, cat.token)).status).toBe(204);
    const me = await call("GET", "/me", undefined, ana.token);
    expect(me.body.groups[0]).toMatchObject({ name: null, memberCount: 2, otherMembers: ["Ben"] });
  });

  it("an empty name clears it", async () => {
    const ana = await newUser("Ana");
    await join(ana.group.inviteCode, "Ben");
    await join(ana.group.inviteCode, "Cat");
    await call("POST", `/groups/${ana.group.id}`, { name: "Flat" }, ana.token);
    const cleared = await call("POST", `/groups/${ana.group.id}`, { name: "" }, ana.token);
    expect(cleared.body.group.name).toBeNull();
  });

  it("leaving one group keeps the user; leaving the last deletes them and their days", async () => {
    const ana = await newUser("Ana");
    const second = await anotherGroup(ana.token);
    await seed("Ana");
    expect((await call("DELETE", `/groups/${ana.group.id}`, undefined, ana.token)).status).toBe(204);
    expect((await call("GET", "/me", undefined, ana.token)).body.groups).toHaveLength(1);
    expect((await call("DELETE", `/groups/${second.id}`, undefined, ana.token)).status).toBe(204);
    expect((await call("GET", "/me", undefined, ana.token)).status).toBe(401);
    expect(await count("users")).toBe(0);
    expect(await count("days")).toBe(0);
    expect(await count("groups")).toBe(0);
  });

  it("a group is deleted when its last member leaves, others remain", async () => {
    const ana = await newUser("Ana");
    const ben = await join(ana.group.inviteCode, "Ben");
    await anotherGroup(ben.token);
    await call("DELETE", `/groups/${ana.group.id}`, undefined, ana.token);
    expect(await count("groups")).toBe(2);
    await call("DELETE", `/groups/${ana.group.id}`, undefined, ben.token);
    expect(await count("groups")).toBe(1);
  });

  it("updates and leaves for a group you're not in are not_member", async () => {
    const ana = await newUser("Ana");
    const ben = await newUser("Ben");
    expect(await call("POST", `/groups/${ben.group.id}`, { share: share() }, ana.token))
      .toEqual({ status: 404, body: { error: "not_member" } });
    expect((await call("DELETE", `/groups/${ben.group.id}`, undefined, ana.token)).status).toBe(404);
    expect((await call("DELETE", "/groups/nope", undefined, ana.token)).status).toBe(404);
  });

  it("a malformed escape in the group id is not_found", async () => {
    const ana = await newUser("Ana");
    expect(await call("DELETE", "/groups/%zz", undefined, ana.token))
      .toEqual({ status: 404, body: { error: "not_found" } });
  });
});

describe("phone-generated identity", () => {
  it("registers a token and rejects a duplicate or malformed one", async () => {
    const token = genToken();
    expect((await call("POST", "/users", { token })).status).toBe(201);
    expect(await call("POST", "/users", { token })).toEqual({ status: 409, body: { error: "token_taken" } });
    for (const bad of ["short", "x".repeat(44), "!".repeat(43), 42]) {
      expect((await call("POST", "/users", { token: bad })).status).toBe(400);
    }
  });

  it("create and join need a bearer", async () => {
    expect((await call("POST", "/groups", { displayName: "Ana", share: share() })).status).toBe(401);
    expect((await call("POST", "/join", { inviteCode: "ABCDEFGH", displayName: "Ana", share: share() })).status).toBe(401);
  });

  it("a nameless identity must name itself when it first creates or joins", async () => {
    const token = await register();
    expect(await call("POST", "/groups", { share: share() }, token)).toEqual({ status: 400, body: { error: "invalid_body" } });
    const res = await call("POST", "/groups", { displayName: "Ana", share: share() }, token);
    expect(res.status).toBe(201);
    expect((await call("GET", "/me", undefined, token)).body.displayName).toBe("Ana");
  });

  it("a named identity's body name is ignored", async () => {
    const token = await register("Ana");
    await call("POST", "/groups", { displayName: "Zed", share: share() }, token);
    expect((await call("GET", "/me", undefined, token)).body.displayName).toBe("Ana");
  });

  it("token rotation keeps groups", async () => {
    const ana = await newUser("Ana");
    const next = genToken();
    expect((await call("POST", "/me/token", { token: next }, ana.token)).status).toBe(204);
    expect((await call("GET", "/me", undefined, ana.token)).status).toBe(401);
    const me = await call("GET", "/me", undefined, next);
    expect(me.body.groups.map((g: { id: string }) => g.id)).toEqual([ana.group.id]);
    const other = await register("Ben");
    expect(await call("POST", "/me/token", { token: other }, next)).toEqual({ status: 409, body: { error: "token_taken" } });
  });

  it("GET /me reports backupAt as null without a backup", async () => {
    const token = await register("Ana");
    expect((await call("GET", "/me", undefined, token)).body).toEqual({ displayName: "Ana", groups: [], backupAt: null });
  });
});
