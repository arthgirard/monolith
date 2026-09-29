import { addDays, isIsoDate, utcToday } from "./dates";
import { HttpError, invalidBody } from "./http";
import { MAX_BLOCKED_APPS, RETENTION_DAYS, type Share } from "./types";

/** [apps] is undefined from a client older than the blocked-apps signal, which never shares it. */
export type ShareInput = Omit<Share, "apps"> & { apps: boolean | undefined };

export function parseShare(v: unknown): ShareInput {
  if (typeof v !== "object" || v === null) throw invalidBody();
  const s = v as Record<string, unknown>;
  if (typeof s.saved !== "boolean" || typeof s.streak !== "boolean" || typeof s.pauses !== "boolean") {
    throw invalidBody();
  }
  if (s.apps !== undefined && typeof s.apps !== "boolean") throw invalidBody();
  return { saved: s.saved, streak: s.streak, pauses: s.pauses, apps: s.apps };
}

export function parseDisplayName(v: unknown): string {
  if (typeof v !== "string") throw invalidBody();
  const name = v.trim();
  const length = [...name].length;
  if (length < 1 || length > 24) throw invalidBody();
  return name;
}

export function parseToken(v: unknown): string {
  if (typeof v !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(v)) throw invalidBody();
  return v;
}

/** A group name, or null to clear it ("" and null both clear). */
export function parseGroupName(v: unknown): string | null {
  if (v === null) return null;
  if (typeof v !== "string") throw invalidBody();
  const name = v.trim();
  if (name === "") return null;
  if ([...name].length > 32) throw invalidBody();
  return name;
}

export interface SyncDay {
  date: string;
  savedMs: number | null;
  bypassCount: number | null;
  unlockCount: number | null;
}

export interface BlockedApp {
  packageName: string;
  label: string;
}

/** [apps] undefined leaves the stored list alone: a client older than the signal never sends it. */
export interface SyncBody {
  days: SyncDay[];
  streakStartedAt: number | null;
  apps: BlockedApp[] | null | undefined;
  /**
   * When credit starts accruing again without another upload: now while a block runs, the end of
   * a running pause, or null while Monolith is off. Absent (null) from an older client.
   */
  accruingSince: number | null;
  /** The phone's offset from UTC, to split projected time into its local days. */
  utcOffsetMinutes: number | null;
}

const PACKAGE_NAME = /^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*$/;

function parseBlockedApps(v: unknown): BlockedApp[] | null | undefined {
  if (v === undefined || v === null) return v;
  if (!Array.isArray(v) || v.length > MAX_BLOCKED_APPS) throw invalidBody();
  const seen = new Set<string>();
  return v.map((raw): BlockedApp => {
    if (typeof raw !== "object" || raw === null) throw invalidBody();
    const a = raw as Record<string, unknown>;
    if (typeof a.packageName !== "string" || a.packageName.length > 255 || !PACKAGE_NAME.test(a.packageName)) throw invalidBody();
    if (seen.has(a.packageName) || typeof a.label !== "string") throw invalidBody();
    seen.add(a.packageName);
    const label = a.label.trim();
    const length = [...label].length;
    if (length < 1 || length > 64) throw invalidBody();
    return { packageName: a.packageName, label };
  });
}

const MAX_RESUMES_IN_MS = 30 * 24 * 60 * 60 * 1000;

const hidden = () => new HttpError(422, "hidden_signal");

function optionalInt(v: unknown, min: number, max: number): number | null {
  if (v === undefined || v === null) return null;
  if (typeof v !== "number" || !Number.isInteger(v) || v < min || v > max) throw invalidBody();
  return v;
}

export function parseSync(body: Record<string, unknown>, share: Share, now: number): SyncBody {
  if (!Array.isArray(body.days) || body.days.length > 40) throw invalidBody();
  const today = utcToday(now);
  // One day of slack on each side: the client keys days by its own local date.
  const earliest = addDays(today, -(RETENTION_DAYS + 1));
  const latest = addDays(today, 1);
  const seen = new Set<string>();

  const days = body.days.map((raw): SyncDay => {
    if (typeof raw !== "object" || raw === null) throw invalidBody();
    const d = raw as Record<string, unknown>;
    if (!isIsoDate(d.date) || d.date < earliest || d.date > latest || seen.has(d.date)) throw invalidBody();
    seen.add(d.date);
    const day = {
      date: d.date,
      savedMs: optionalInt(d.savedMs, 0, 86_400_000),
      bypassCount: optionalInt(d.bypassCount, 0, 10_000),
      unlockCount: optionalInt(d.unlockCount, 0, 10_000),
    };
    if (!share.saved && day.savedMs !== null) throw hidden();
    if (!share.pauses && (day.bypassCount !== null || day.unlockCount !== null)) throw hidden();
    return day;
  });

  // A phone clock slightly ahead of ours is not an error: clamp instead of dropping the sync.
  const rawStreak = optionalInt(body.streakStartedAt, 1, Number.MAX_SAFE_INTEGER);
  const streakStartedAt = rawStreak === null ? null : Math.min(rawStreak, now);
  if (!share.streak && streakStartedAt !== null) throw hidden();
  const apps = parseBlockedApps(body.apps);
  if (!share.apps && apps) throw hidden();
  // Relative to the upload, so a phone clock off from ours doesn't shift the projection.
  const resumesInMs = optionalInt(body.resumesInMs, 0, MAX_RESUMES_IN_MS);
  const utcOffsetMinutes = optionalInt(body.utcOffsetMinutes, -840, 840);
  if (!share.saved && !share.streak && (resumesInMs !== null || utcOffsetMinutes !== null)) throw hidden();
  const accruingSince = resumesInMs === null ? null : now + resumesInMs;
  return { days, streakStartedAt, apps, accruingSince, utcOffsetMinutes };
}
