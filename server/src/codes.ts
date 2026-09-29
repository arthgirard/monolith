const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const CODE_PATTERN = /^[0-9A-HJKMNP-TV-Z]{8}$/;

/** 8 Crockford base32 characters. 256 is a multiple of 32, so masking a byte is unbiased. */
export function newInviteCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  return Array.from(bytes, (b) => CROCKFORD[b & 31]).join("");
}

/** Forgives what people do when typing a code off a friend's screen. */
export function normalizeInviteCode(input: string): string | null {
  const code = input
    .toUpperCase()
    .replace(/[\s-]/g, "")
    .replace(/O/g, "0")
    .replace(/[IL]/g, "1");
  return CODE_PATTERN.test(code) ? code : null;
}

export function newToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return btoa(String.fromCharCode(...bytes)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function sha256Hex(s: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}
