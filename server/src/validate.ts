import { addDays, isIsoDate, utcToday } from "./dates";
import { HttpError, invalidBody } from "./http";
import { RETENTION_DAYS, type Share } from "./types";

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

export interface SyncDay {
  date: string;
  savedMs: number | null;
  bypassCount: number | null;
  unlockCount: number | null;
}

export interface SyncBody {
  days: SyncDay[];
  streakStartedAt: number | null;
}

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
  return { days, streakStartedAt };
}
