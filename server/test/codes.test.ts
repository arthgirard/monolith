import { describe, expect, it } from "vitest";
import { newInviteCode, newToken, normalizeInviteCode, sha256Hex } from "../src/codes";

describe("codes", () => {
  it("invite codes are 8 Crockford base32 characters", () => {
    for (let i = 0; i < 50; i++) expect(newInviteCode()).toMatch(/^[0-9A-HJKMNP-TV-Z]{8}$/);
  });

  it("normalizes case, separators and look-alike letters", () => {
    expect(normalizeInviteCode(" ab-cd efgh ")).toBe("ABCDEFGH");
    expect(normalizeInviteCode("oOiIlL12")).toBe("00111112");
  });

  it("rejects codes that cannot be valid", () => {
    expect(normalizeInviteCode("ABC")).toBeNull();
    expect(normalizeInviteCode("ABCDEFGU")).toBeNull();
  });

  it("tokens are 43 base64url characters and unique", () => {
    const a = newToken();
    expect(a).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(newToken()).not.toBe(a);
  });

  it("sha256Hex matches a known vector", async () => {
    expect(await sha256Hex("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });
});
