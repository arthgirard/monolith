import { invalidBody } from "./http";
import type { Share } from "./types";

export function parseShare(v: unknown): Share {
  if (typeof v !== "object" || v === null) throw invalidBody();
  const s = v as Record<string, unknown>;
  if (typeof s.saved !== "boolean" || typeof s.streak !== "boolean" || typeof s.pauses !== "boolean") {
    throw invalidBody();
  }
  return { saved: s.saved, streak: s.streak, pauses: s.pauses };
}

export function parseDisplayName(v: unknown): string {
  if (typeof v !== "string") throw invalidBody();
  const name = v.trim();
  const length = [...name].length;
  if (length < 1 || length > 24) throw invalidBody();
  return name;
}
