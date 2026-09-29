import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { call, createGroup, share } from "./helpers";

async function seed(token: string) {
  await env.monolith_leaderboard.batch([
    env.monolith_leaderboard.prepare("UPDATE members SET streak_started_at = 1000"),
    env.monolith_leaderboard.prepare(
      "INSERT INTO days (member_id, date, saved_ms, bypass_count, unlock_count) SELECT id, '2026-09-01', 5, 1, 2 FROM members",
    ),
  ]);
  return token;
}

const stored = () =>
  env.monolith_leaderboard
    .prepare(
      "SELECT m.streak_started_at, d.saved_ms, d.bypass_count, d.unlock_count FROM members m JOIN days d ON d.member_id = m.id",
    )
    .first();

describe("POST /me", () => {
  it("renames and returns the profile", async () => {
    const { token, inviteCode } = await createGroup("Ana");
    const res = await call("POST", "/me", { displayName: " Anna " }, token);
    expect(res).toEqual({ status: 200, body: { displayName: "Anna", share: share(), inviteCode } });
  });

  it("hiding time gained nulls stored saved_ms only", async () => {
    const { token } = await createGroup();
    await seed(token);
    await call("POST", "/me", { share: share(false, true, true) }, token);
    expect(await stored()).toEqual({ streak_started_at: 1000, saved_ms: null, bypass_count: 1, unlock_count: 2 });
  });

  it("hiding the streak nulls streak_started_at only", async () => {
    const { token } = await createGroup();
    await seed(token);
    await call("POST", "/me", { share: share(true, false, true) }, token);
    expect(await stored()).toEqual({ streak_started_at: null, saved_ms: 5, bypass_count: 1, unlock_count: 2 });
  });

  it("hiding pauses nulls both counts", async () => {
    const { token } = await createGroup();
    await seed(token);
    await call("POST", "/me", { share: share(true, true, false) }, token);
    expect(await stored()).toEqual({ streak_started_at: 1000, saved_ms: 5, bypass_count: null, unlock_count: null });
  });

  it("rejects an invalid name", async () => {
    const { token } = await createGroup();
    expect((await call("POST", "/me", { displayName: "" }, token)).status).toBe(400);
  });
});
